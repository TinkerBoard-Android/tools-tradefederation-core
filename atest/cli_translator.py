#
# Copyright 2017, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Command Line Translator for atest.
"""

import itertools
import json
import logging
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from collections import namedtuple

import atest_utils

RUN_CMD = ('atest_tradefed.sh run commandAndExit %s --template:map '
           'test=atest %s')
TF_TEMPLATE = 'template/local_min'
MODULES_IN = 'MODULES-IN-%s'
MODULE_CONFIG = 'AndroidTest.xml'
# JSON file generated by build system that lists all buildable targets.
MODULE_INFO = 'module-info.json'
TF_TARGETS = frozenset(['tradefed', 'tradefed-contrib'])
GTF_TARGETS = frozenset(['google-tradefed', 'google-tradefed-contrib'])
ATEST_SPONGE_LABEL = 'atest'
PERF_SETUP_LABEL = 'perf-setup.sh'

# Helps find apk files listed in a test config (AndroidTest.xml) file.
# Matches "filename.apk" in <option name="foo", value="bar/filename.apk" />
APK_RE = re.compile(r'^[^/]+\.apk$', re.I)
# Find integration name based on file path of integration config xml file.
# Group matches "foo/bar" given "blah/res/config/blah/res/config/foo/bar.xml
INT_NAME_RE = re.compile(r'^.*\/res\/config\/(?P<int_name>.*).xml$')
# Parse package name from the package declaration line of a java file.
# Group matches "foo.bar" of line "package foo.bar;"
PACKAGE_RE = re.compile(r'\s*package\s+(?P<package>[^;]+)\s*;\s*', re.I)
TEST_MODULE_NAME = 'test-module-name'

class NoTestFoundError(Exception):
    """Raised when no tests are found."""

class TestWithNoModuleError(Exception):
    """Raised when test files have no parent module directory."""

class UnregisteredModuleError(Exception):
    """Raised when module is not in module-info.json."""

class MissingPackageNameError(Exception):
    """Raised when the test class java file does not contain a package name."""

class TooManyMethodsError(Exception):
    """Raised when input string contains more than one # character."""

class Enum(tuple):
    """enum library isn't a Python 2.7 built-in, so roll our own."""
    __getattr__ = tuple.index

# Explanation of REFERENCE_TYPEs:
# ----------------------------------
# 0. MODULE: LOCAL_MODULE or LOCAL_PACKAGE_NAME value in Android.mk/Android.bp.
# 1. MODULE_CLASS: Combo of MODULE and CLASS as "module:class".
# 2. PACKAGE: package in java file. Same as file path to java file.
# 3. MODULE_PACKAGE: Combo of MODULE and PACKAGE as "module:package".
# 4. FILE_PATH: file path to dir of tests or test itself.
# 5. INTEGRATION: xml file name in one of the 4 integration config directories.
# 6. SUITE: Value of the "run-suite-tag" in xml config file in 4 config dirs.
#           Same as value of "test-suite-tag" in AndroidTest.xml files.
REFERENCE_TYPE = Enum(['MODULE', 'CLASS', 'QUALIFIED_CLASS', 'MODULE_CLASS',
                       'PACKAGE', 'MODULE_PACKAGE', 'FILE_PATH', 'INTEGRATION',
                       'SUITE'])

# Unix find commands for searching for test files based on test type input.
# Note: Find (unlike grep) exits with status 0 if nothing found.
FIND_CMDS = {
    REFERENCE_TYPE.CLASS : r"find %s -type d -name \".*\" -prune -o -type f "
                           r"-name '%s.java' -print",
    REFERENCE_TYPE.QUALIFIED_CLASS: r"find %s -type d -name \".*\" -prune -o "
                                    r"-wholename '*%s.java' -print",
    REFERENCE_TYPE.INTEGRATION: r"find %s -type d -name \".*\" -prune -o "
                                r"-wholename '*%s.xml' -print"
}

TestInfoBase = namedtuple('TestInfo', ['rel_config', 'module_name',
                                       'integrated_name', 'filters'])
class TestInfo(TestInfoBase):
    """Information needed to identify and run a test."""

    def to_tf_dict(self):
        """Return dict representation of TestInfo suitable to be saved
        to test_info.json file and loaded by TradeFed's AtestRunner."""
        filters = set()
        for test_filter in self.filters:
            filters.update(test_filter.to_set_of_tf_strings())
        return {
            'test': self.integrated_name or self.module_name,
            'filters': list(filters)}

TestFilterBase = namedtuple('TestFilter', ['class_name', 'methods'])

class TestFilter(TestFilterBase):
    """Information needed to filter a test in Tradefed"""

    def to_set_of_tf_strings(self):
        """Return TestFilter as set of strings in TradeFed filter format."""
        if self.methods:
            return {'%s#%s' % (self.class_name, m) for m in self.methods}
        return {self.class_name}

#pylint: disable=no-self-use
class CLITranslator(object):
    """
    CLITranslator class contains public method translate() and some private
    helper methods. The atest tool can call the translate() method with a list
    of strings, each string referencing a test to run. Translate() will
    "translate" this list of test strings into a list of build targets and a
    list of TradeFederation run commands.

    Translation steps for a test string reference:
        1. Narrow down the type of reference the test string could be, i.e.
           whether it could be referencing a Module, Class, Package, etc.
        2. Try to find the test files assuming the test string is one of these
           types of reference.
        3. If test files found, generate Build Targets and the Run Command.
    """

    def __init__(self, results_dir, root_dir='/'):
        if not os.path.isdir(root_dir):
            raise ValueError('%s is not valid dir.' % root_dir)
        self.results_dir = results_dir
        self.root_dir = os.path.realpath(root_dir)
        self.out_dir = os.environ.get('OUT')
        self.ref_type_to_func_map = {
            REFERENCE_TYPE.MODULE: self._find_test_by_module_name,
            REFERENCE_TYPE.CLASS: self._find_test_by_class_name,
            REFERENCE_TYPE.MODULE_CLASS: self._find_test_by_module_and_class,
            REFERENCE_TYPE.QUALIFIED_CLASS: self._find_test_by_class_name,
            REFERENCE_TYPE.FILE_PATH: self._find_test_by_path,
            REFERENCE_TYPE.INTEGRATION: self._find_test_by_integration_name,
        }
        self.module_info_target, self.module_info = self._load_module_info()
        self.tf_dirs, self.gtf_dirs = self._get_integration_dirs()
        self.integration_dirs = self.tf_dirs + self.gtf_dirs

    def _load_module_info(self):
        """Make (if not exists) and load into memory MODULE_INFO file

        Returns:
            A tuple containing the module-info build target and a dict of data
            about module names and dir locations.
        """
        file_path = os.path.join(self.out_dir, MODULE_INFO)
        # Make target is simply file path relative to root.
        module_info_target = os.path.relpath(file_path, self.root_dir)
        if not os.path.isfile(file_path):
            logging.info('Generating %s - this is required for '
                         'initial runs.', MODULE_INFO)
            atest_utils.build([module_info_target],
                              logging.getLogger().isEnabledFor(logging.DEBUG))
        with open(file_path) as json_file:
            return (module_info_target, json.load(json_file))

    def _get_integration_dirs(self):
        """Get integration dirs from MODULE_INFO based on targets.

        Returns:
            A tuple of lists of strings of integration dir rel to repo root.
        """
        tf_dirs = filter(None, [self._get_module_path(x) for x in TF_TARGETS])
        gtf_dirs = filter(None, [self._get_module_path(x) for x in GTF_TARGETS])
        return tf_dirs, gtf_dirs

    def _get_test_reference_types(self, ref):
        """Determine type of test reference based on the content of string.

        Examples:
            The string 'SequentialRWTest' could be a reference to
            a Module or a Class name.

            The string 'cts/tests/filesystem' could be a Path, Integration
            or Suite reference.

        Args:
            ref: A string referencing a test.

        Returns:
            A list of possible REFERENCE_TYPEs (ints) for reference string.
        """
        if ref.startswith('.') or '..' in ref:
            return [REFERENCE_TYPE.FILE_PATH]
        if '/' in ref:
            if ref.startswith('/'):
                return [REFERENCE_TYPE.FILE_PATH]
            return [REFERENCE_TYPE.FILE_PATH,
                    REFERENCE_TYPE.INTEGRATION,
                    # Comment in SUITE when it's supported
                    # REFERENCE_TYPE.SUITE
                   ]
        if ':' in ref:
            if '.' in ref:
                return [REFERENCE_TYPE.MODULE_CLASS,
                        REFERENCE_TYPE.MODULE_PACKAGE,
                        REFERENCE_TYPE.INTEGRATION]
            return [REFERENCE_TYPE.MODULE_CLASS,
                    REFERENCE_TYPE.INTEGRATION]
        if '.' in ref:
            return [REFERENCE_TYPE.FILE_PATH,
                    REFERENCE_TYPE.QUALIFIED_CLASS,
                    REFERENCE_TYPE.PACKAGE]
        # Note: We assume that if you're referencing a file in your cwd,
        # that file must have a '.' in its name, i.e. foo.java, foo.xml.
        # If this ever becomes not the case, then we need to include path below.
        return [REFERENCE_TYPE.INTEGRATION,
                # Comment in SUITE when it's supported
                # REFERENCE_TYPE.SUITE,
                REFERENCE_TYPE.MODULE, REFERENCE_TYPE.CLASS]

    def _is_equal_or_sub_dir(self, sub_dir, parent_dir):
        """Return True sub_dir is sub dir or equal to parent_dir.

        Args:
          sub_dir: A string of the sub directory path.
          parent_dir: A string of the parent directory path.

        Returns:
            A boolean of whether both are dirs and sub_dir is sub of parent_dir
            or is equal to parent_dir.
        """
        # avoid symlink issues with real path
        parent_dir = os.path.realpath(parent_dir)
        sub_dir = os.path.realpath(sub_dir)
        if not os.path.isdir(sub_dir) or not os.path.isdir(parent_dir):
            return False
        return os.path.commonprefix([sub_dir, parent_dir]) == parent_dir

    def _find_parent_module_dir(self, start_dir):
        """From current dir search up file tree until root dir for module dir.

        Args:
          start_dir: A string of the dir to start searching up from.

        Returns:
            A string of the module dir relative to root.

        Exceptions:
            ValueError: Raised if cur_dir not dir or not subdir of root dir.
            TestWithNoModuleError: Raised if no Module Dir found.
        """
        if not self._is_equal_or_sub_dir(start_dir, self.root_dir):
            raise ValueError('%s not in repo %s' % (start_dir, self.root_dir))
        current_dir = start_dir
        while current_dir != self.root_dir:
            if os.path.isfile(os.path.join(current_dir, MODULE_CONFIG)):
                return os.path.relpath(current_dir, self.root_dir)
            current_dir = os.path.dirname(current_dir)
        raise TestWithNoModuleError('No Parent Module Dir for: %s' % start_dir)

    def _extract_test_path(self, output):
        """Extract the test path from the output of a unix 'find' command.

        Example of find output for CLASS find cmd:
        /<some_root>/cts/tests/jank/src/android/jank/cts/ui/CtsDeviceJankUi.java

        Args:
            output: A string output of a unix 'find' command.

        Returns:
            A string of the test path or None if output is '' or None.
        """
        if not output:
            return None
        tests = output.strip('\n').split('\n')
        count = len(tests)
        test_index = 0
        if count > 1:
            numbered_list = ['%s: %s' % (i, t) for i, t in enumerate(tests)]
            print 'Multiple tests found:\n%s' % '\n'.join(numbered_list)
            test_index = int(raw_input('Please enter number of test to use:'))
        return tests[test_index]

    def _get_module_name(self, rel_module_path):
        """Get the name of a module given its dir relative to repo root.

        Example of module_info.json line:

        'AmSlam':
        {
        'class': ['APPS'],
        'path': ['frameworks/base/tests/AmSlam'],
        'tags': ['tests'],
        'installed': ['out/target/product/bullhead/data/app/AmSlam/AmSlam.apk']
        }

        Args:
            rel_module_path: A string of module's dir relative to repo root.

        Returns:
            A string of the module name, else None if not found.

        Exceptions:
            UnregisteredModuleError: Raised if module not in MODULE_INFO.
        """
        for name, info in self.module_info.iteritems():
            if (rel_module_path == info.get('path', [])[0] and
                    info.get('installed')):
                return name
        raise UnregisteredModuleError('%s not in %s' %
                                      (rel_module_path, MODULE_INFO))

    def _get_module_path(self, module_name):
        """Get path from MODULE_INFO given a module name.

        Args:
            module_name: A string of the module name.

        Returns:
            A string of path to the module, else None if no module found.
        """
        info = self.module_info.get(module_name)
        if info:
            return info.get('path', [])[0]
        return None

    def _get_targets_from_xml(self, xml_file):
        """Retrieve build targets from the given xml.

        We're going to pull the following bits of info:
          - Parse any .apk files listed in the config file.
          - Parse option value for "test-module-name" (for vts tests).

        Args:
            xml_file: abs path to xml file.

        Returns:
            A set of build targets based on the signals found in the xml file.
        """
        target_to_add = None
        targets = set()
        tree = ET.parse(xml_file)
        root = tree.getroot()
        option_tags = root.findall('.//option')
        for tag in option_tags:
            name = tag.attrib['name'].strip()
            value = tag.attrib['value'].strip()
            if APK_RE.match(value):
                target_to_add = value[:-len('.apk')]
            elif name == TEST_MODULE_NAME:
                target_to_add = value
	    elif PERF_SETUP_LABEL in value:
                target_to_add = PERF_SETUP_LABEL

            # Let's make sure we can actually build the target.
            if target_to_add and target_to_add in self.module_info:
                targets.add(target_to_add)
                target_to_add = None
            elif target_to_add:
                logging.warning('Build target (%s) parsed out of %s but not '
                                'present in %s, skipping build', target_to_add,
                                xml_file, MODULE_INFO)
        logging.debug('Targets found in config file: %s', targets)
        return targets

    def _get_fully_qualified_class_name(self, test_path):
        """Parse the fully qualified name from the class java file.

        Args:
            test_path: A string of absolute path to the java class file.

        Returns:
            A string of the fully qualified class name.
        """
        with open(test_path) as class_file:
            for line in class_file:
                match = PACKAGE_RE.match(line)
                if match:
                    package = match.group('package')
                    cls = os.path.splitext(os.path.split(test_path)[1])[0]
                    return '%s.%s' % (package, cls)
        raise MissingPackageNameError(test_path)

    def _split_methods(self, user_input):
        """Split user input string into test reference and list of methods.

        Args:
            user_input: A string of the user's input.
                        Examples:
                            class_name
                            class_name#method1,method2
                            path
                            path#method1,method2
        Returns:
            A tuple. First element is String of test ref and second element is
            a set of method name strings or empty list if no methods included.
        Exception:
            TooManyMethodsError raised when input string is trying to specify
            too many methods in a single positional argument.

            Examples of unsupported input strings:
                module:class#method,class#method
                class1#method,class2#method
                path1#method,path2#method
        """
        parts = user_input.split('#')
        if len(parts) == 1:
            return parts[0], frozenset()
        elif len(parts) == 2:
            return parts[0], frozenset(parts[1].split(','))
        else:
            raise TooManyMethodsError(
                'Too many methods specified with # character in user input: %s.'
                '\n\nOnly one class#method combination supported per positional'
                ' argument. Multiple classes should be separated by spaces: '
                'class#method class#method')

    def _find_test_by_module_name(self, module_name):
        """Find test for the given module name.

        Args:
            module_name: A string of the test's module name.

        Returns:
            A populated TestInfo namedtuple if found, else None.
        """
        info = self.module_info.get(module_name)
        if info and info.get('installed'):
            # path is a list with only 1 element.
            rel_config = os.path.join(info['path'][0], MODULE_CONFIG)
            return TestInfo(rel_config, module_name, None, frozenset())
        return None

    def _find_class_file(self, class_name, search_dir):
        """Find a java class file given a class name and search dir.

        Args:
            class_name: A string of the test's class name.
            search_dir: A string of the dirpath to search in.

        Return:
            A string of the path to the java file.
        """
        if '.' in class_name:
            find_cmd = FIND_CMDS[REFERENCE_TYPE.QUALIFIED_CLASS] % (
                search_dir, class_name.replace('.', '/'))
        else:
            find_cmd = FIND_CMDS[REFERENCE_TYPE.CLASS] % (
                search_dir, class_name)
        # TODO: Pull out common find cmd and timing code.
        start = time.time()
        logging.debug('Executing: %s', find_cmd)
        out = subprocess.check_output(find_cmd, shell=True)
        logging.debug('Find completed in %ss', time.time() - start)
        logging.debug('Class - Find Cmd Out: %s', out)
        return self._extract_test_path(out)


    def _find_test_by_class_name(self, class_name, module_name=None,
                                 rel_config=None):
        """Find test files given a class name.  If module_name and rel_config
        not given it will calculate it determine it by looking up the tree
        from the class file.

        Args:
            class_name: A string of the test's class name.
            module_name: Optional. A string of the module name to use.
            rel_config: Optional. A string of module dir relative to repo root.

        Returns:
            A populated TestInfo namedtuple if test found, else None.
        """
        class_name, methods = self._split_methods(class_name)
        if rel_config:
            search_dir = os.path.join(self.root_dir,
                                      os.path.dirname(rel_config))
        else:
            search_dir = self.root_dir
        test_path = self._find_class_file(class_name, search_dir)
        if not test_path:
            return None
        full_class_name = self._get_fully_qualified_class_name(test_path)
        test_filter = TestFilter(full_class_name, methods)
        if not rel_config:
            test_dir = os.path.dirname(test_path)
            rel_module_dir = self._find_parent_module_dir(test_dir)
            rel_config = os.path.join(rel_module_dir, MODULE_CONFIG)
        if not module_name:
            module_name = self._get_module_name(os.path.dirname(rel_config))
        return TestInfo(rel_config, module_name, None, frozenset([test_filter]))

    def _find_test_by_module_and_class(self, module_class):
        """Find the test info given a MODULE:CLASS string.

        Args:
            module_class: A string of form MODULE:CLASS or MODULE:CLASS#METHOD.

        Returns:
            A populated TestInfo namedtuple if found, else None.
        """
        module_name, class_name = module_class.split(':')
        module_info = self._find_test_by_module_name(module_name)
        if not module_info:
            return None
        return self._find_test_by_class_name(class_name,
                                             module_info.module_name,
                                             module_info.rel_config)

    def _find_test_by_integration_name(self, name):
        """Find the test info matching the given integration name.

        Args:
            name: A string of integration name as seen in tf's list configs.

        Returns:
            A populated TestInfo namedtuple if test found, else None
        """
        filters = frozenset()
        if ':' in name:
            name, class_name = name.split(':')
            class_name, methods = self._split_methods(class_name)
            if '.' not in class_name:
                logging.warn('Looking up fully qualified class name for: %s.'
                             'Improve speed by using fully qualified names.',
                             class_name)
                path = self._find_class_file(class_name, self.root_dir)
                if not path:
                    return None
                class_name = self._get_fully_qualified_class_name(path)
            filters = frozenset([TestFilter(class_name, methods)])
        for integration_dir in self.integration_dirs:
            abs_path = os.path.join(self.root_dir, integration_dir)
            find_cmd = FIND_CMDS[REFERENCE_TYPE.INTEGRATION] % (abs_path, name)
            logging.debug('Executing: %s', find_cmd)
            out = subprocess.check_output(find_cmd, shell=True)
            logging.debug('Integration - Find Cmd Out: %s', out)
            test_file = self._extract_test_path(out)
            if test_file:
                # Don't use names that simply match the path,
                # must be the actual name used by TF to run the test.
                match = INT_NAME_RE.match(test_file)
                if not match:
                    logging.error('Integration test outside config dir: %s',
                                  test_file)
                    return None
                int_name = match.group('int_name')
                if int_name != name:
                    logging.warn('Input (%s) not valid integration name, '
                                 'did you mean: %s?', name, int_name)
                    return None
                rel_config = os.path.relpath(test_file, self.root_dir)
                return TestInfo(rel_config, None, name, filters)
        return None

    def _find_tests_by_test_mapping(self, path=''):
        """Find test infos defined in TEST_MAPPING of the given path and its
        parent directories if required.

        Args:
            path: A string of path in source. Default is set to '', i.e., CWD.

        Returns:
            A set of populated TestInfo namedtuples that's defined in
            TEST_MAPPING file of the given path, and its parent directories if
            TEST_MAPPING in the given directory has `include_parent` set to
            True.
        """
        directory = os.path.realpath(path)
        if directory == atest_utils.ANDROID_BUILD_TOP or directory == os.sep:
            return None
        tests = set()
        test_mapping = None
        test_mapping_file = os.path.join(directory, 'TEST_MAPPING')
        if os.path.exists(test_mapping_file):
            with open(test_mapping_file) as json_file:
                test_mapping = json.load(json_file)
            for test in test_mapping.get('presubmit', []):
                name = test['name']
                test_info = None
                # Name referenced in TEST_MAPPING can only be module name or
                # integration test name.
                for find_method in [self._find_test_by_module_name,
                                    self._find_test_by_integration_name]:
                    test_info = find_method(name)
                    if test_info:
                        tests.add(test_info)
                        break
                else:
                    logging.warn('Failed to locate test %s', name)
        if not test_mapping or test_mapping.get('include_parent'):
            parent_dir_tests = self._find_tests_by_test_mapping(
                os.path.dirname(directory))
            if parent_dir_tests:
                tests |= parent_dir_tests
        return tests

    def _find_test_by_path(self, path):
        """Find the first test info matching the given path.

        Strategy:
            path_to_java_file --> Resolve to CLASS
            path_to_module_dir -> Resolve to MODULE
            path_to_class_dir --> Resolve to MODULE (TODO: Maybe all classes)
            path_to_integration_file --> Resolve to INTEGRATION
            path_to_random_dir --> try to resolve to MODULE
            # TODO:
            path_to_dir_with_integration_files --> Resolve to ALL Integrations

        Args:
            path: A string of the test's path.

        Returns:
            A populated TestInfo namedtuple if test found, else None
        """
        path, methods = self._split_methods(path)
        # TODO: See if this can be generalized and shared with methods above
        # create absolute path from cwd and remove symbolic links
        path = os.path.realpath(path)
        if not os.path.exists(path):
            return None
        if os.path.isfile(path):
            dir_path, file_name = os.path.split(path)
        else:
            dir_path, file_name = path, None

        # Integration/Suite
        int_dir = None
        for possible_dir in self.integration_dirs:
            abs_int_dir = os.path.join(self.root_dir, possible_dir)
            if self._is_equal_or_sub_dir(dir_path, abs_int_dir):
                int_dir = abs_int_dir
                break
        if int_dir:
            if not file_name:
                logging.warn('Found dir (%s) matching input (%s).'
                             ' Referencing an entire Integration/Suite dir'
                             ' is not supported. If you are trying to reference'
                             ' a test by its path, please input the path to'
                             ' the integration/suite config file itself.'
                             ' Continuing to try to resolve input (%s)'
                             ' as a non-path reference...',
                             int_dir, path, path)
                return None
            rel_config = os.path.relpath(path, self.root_dir)
            match = INT_NAME_RE.match(rel_config)
            if not match:
                logging.error('Integration test outside config dir: %s',
                              rel_config)
                return None
            int_name = match.group('int_name')
            return TestInfo(rel_config, None, int_name, frozenset())

        # Module/Class
        rel_module_dir = self._find_parent_module_dir(dir_path)
        if not rel_module_dir:
            return None
        module_name = self._get_module_name(rel_module_dir)
        rel_config = os.path.join(rel_module_dir, MODULE_CONFIG)
        test_filter = None
        if file_name and file_name.endswith('.java'):
            full_class_name = self._get_fully_qualified_class_name(path)
            test_filter = TestFilter(full_class_name, methods)
        return TestInfo(rel_config, module_name, None,
                        frozenset([test_filter])
                        if test_filter else frozenset())

    def _sort_and_group(self, iterable, key):
        """Sort and group helper function."""
        return itertools.groupby(sorted(iterable, key=key), key=key)

    def _flatten_test_filters(self, filters):
        """Sort and group test_filters by class_name.

            Example of three test_filters in a frozenset:
                classA, {}
                classB, {Method1}
                classB, {Method2}
            Becomes a frozenset with these elements:
                classA, {}
                classB, {Method1, Method2}
            Where:
                Each line is a TestFilter namedtuple
                {} = Frozenset

        Args:
            filters: A frozenset of test_filters.

        Returns:
            A frozenset of test_filters flattened.
        """
        results = set()
        key = lambda x: x.class_name
        for class_name, group in self._sort_and_group(filters, key):
            # class_name is a string, group is a generator of TestFilters
            assert class_name is not None
            methods = set()
            for test_filter in group:
                if not test_filter.methods:
                    # Whole class should be run
                    methods = set()
                    break
                methods |= test_filter.methods
            results.add(TestFilter(class_name, frozenset(methods)))
        return frozenset(results)

    def _flatten_test_infos(self, test_infos):
        """Sort and group test_infos by module_name and sort and group filters
        by class name.

            Example of three test_infos in a set:
                Module1, {(classA, {})}
                Module1, {(classB, {Method1})}
                Module1, {(classB, {Method2}}
            Becomes a set with one element:
                Module1, {(ClassA, {}), (ClassB, {Method1, Method2})}
            Where:
                  Each line is a test_info namedtuple
                  {} = Frozenset
                  () = TestFilter namedtuple

        Args:
            test_infos: A set of TestInfo namedtuples.

        Returns:
            A set of TestInfos flattened.
        """
        results = set()
        key = lambda x: x.module_name
        for module, group in self._sort_and_group(test_infos, key):
            # module is a string, group is a generator of grouped TestInfos.
            if module is None:
                # Integration Test
                results.update(group)
                continue
            # Module Test, so flatten test_infos:
            rel_config, filters = None, set()
            for test_info in group:
                # rel_config should be same for all, so just take last.
                rel_config = test_info.rel_config
                if not test_info.filters:
                    # test_info wants whole module run, so hardcode no filters.
                    filters = set()
                    break
                filters |= test_info.filters
            filters = self._flatten_test_filters(filters)
            results.add(TestInfo(rel_config, module, None, frozenset(filters)))
        return results

    def _parse_build_targets(self, test_info):
        """Parse a list of build targets from a single TestInfo.

        Args:
            test_info: A TestInfo instance.

        Returns:
            A set of strings of the build targets.
        """
        config_file = os.path.join(self.root_dir, test_info.rel_config)
        targets = self._get_targets_from_xml(config_file)
        if self.gtf_dirs:
            targets.add('google-tradefed-core')
        else:
            targets.add('tradefed-core')
        if test_info.module_name:
            mod_dir = os.path.dirname(test_info.rel_config).replace('/', '-')
            targets.add(MODULES_IN % mod_dir)
        return targets

    def _generate_build_targets(self, test_infos):
        """Generate a set of build targets for a list of test_infos.

        Args:
            test_infos: A set of TestInfo instances.

        Returns:
            A set of strings of build targets.
        """
        build_targets = set()
        for test_info in test_infos:
            build_targets |= self._parse_build_targets(test_info)
        # Since we don't initialize module-info if it already exists, add it to
        # the list of build targets to keep the file up to date.
        build_targets.add(self.module_info_target)
        return build_targets

    def _create_test_info_file(self, test_infos):
        """Create the test info file.

        Args:
            test_infos: A set of TestInfo instances.

        Returns:
            A string of the filepath.
        """
        filepath = os.path.join(self.results_dir, 'test_info.json')
        infos = [test_info.to_tf_dict() for test_info in test_infos]
        logging.debug('Test info: %s', infos)
        logging.info('Writing test info to: %s', filepath)
        with open(filepath, 'w') as test_info_file:
            json.dump(infos, test_info_file)
        return filepath

    def _generate_run_commands(self, filepath):
        """Generate a list of run commands from TestInfos.

        Args:
            filepath: A string of the filepath to the test_info file.

        Returns:
            A list of strings of the TradeFederation run commands.
        """
        if logging.getLogger().isEnabledFor(logging.DEBUG):
            log_level = 'VERBOSE'
        else:
            log_level = 'WARN'
        args = ['--test-info-file', filepath, '--log-level', log_level]
        args.extend(atest_utils.get_result_server_args())
        return [RUN_CMD % (TF_TEMPLATE, ' '.join(args))]

    def _get_test_info(self, test_name, reference_types):
        """Tries to find a TestInfo matches reference else returns None

        Args:
            test_name: A string referencing a test.
            reference_types: A list of TetReferenceTypes (ints).

        Returns:
            A TestInfo namedtuple, else None if test files not found.
        """
        logging.debug('Finding test for "%s" using reference strategy: %s',
                      test_name, [REFERENCE_TYPE[x] for x in reference_types])
        for ref_type in reference_types:
            ref_name = REFERENCE_TYPE[ref_type]
            try:
                test_info = self.ref_type_to_func_map[ref_type](test_name)
                if test_info:
                    logging.debug('Found test for "%s" treating as'
                                  ' %s reference', test_name, ref_name)
                    logging.debug('Resolved "%s" to %s', test_name, test_info)
                    return test_info
                logging.debug('Failed to find %s as %s', test_name, ref_name)
            except KeyError:
                supported = ', '.join(REFERENCE_TYPE[k]
                                      for k in self.ref_type_to_func_map)
                logging.warn('"%s" as %s reference is unsupported. atest only '
                             'supports identifying a test by its: %s',
                             test_name, REFERENCE_TYPE[ref_type],
                             supported)
        return None

    def translate(self, tests):
        """Translate atest command line into build targets and run commands.

        Args:
            tests: A list of strings referencing the tests to run.

        Returns:
            A tuple with set of build_target strings and list of run command
            strings.
        """
        logging.info('Finding tests: %s', tests)
        start = time.time()
        test_infos = set()
        if not tests:
            test_infos = self._find_tests_by_test_mapping()
            if not test_infos:
                raise NoTestFoundError(
                    'Failed to find TEST_MAPPING at %s or its parent '
                    'directories.' % os.path.realpath(''))
        else:
            for test in tests:
                possible_reference_types = self._get_test_reference_types(test)
                test_info = self._get_test_info(test, possible_reference_types)
                if not test_info:
                    # TODO: Should we raise here, or just stdout a message?
                    raise NoTestFoundError('No test found for: %s' % test)
                test_infos.add(test_info)
        test_infos = self._flatten_test_infos(test_infos)
        build_targets = self._generate_build_targets(test_infos)
        filepath = self._create_test_info_file(test_infos)
        run_commands = self._generate_run_commands(filepath)
        end = time.time()
        logging.debug('Found tests in %ss', end - start)
        return build_targets, run_commands
