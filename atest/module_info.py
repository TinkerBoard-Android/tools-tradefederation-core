# Copyright 2018, The Android Open Source Project
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
Module Info class used to hold cached module-info.json.
"""

import json
import logging
import os

import atest_utils
import constants

# JSON file generated by build system that lists all buildable targets.
_MODULE_INFO = 'module-info.json'
_MODULE_NAME = 'module_name'
_MODULE_PATH = 'path'


class ModuleInfo(object):
    """Class that offers fast/easy lookup for Module related details."""

    def __init__(self, force_build=False, module_file=None):
        """Initialize the ModuleInfo object.

        Load up the module-info.json file and initialize the helper vars.

        Args:
            force_build: Boolean to indicate if we should rebuild the
                         module_info file regardless if it's created or not.
            module_file: String of path to file to load up. Used for testing.
        """
        module_info_target, name_to_module_info = self._load_module_info_file(
            force_build, module_file)
        self.name_to_module_info = name_to_module_info
        self.module_info_target = module_info_target
        self.path_to_module_info = self._get_path_to_module_info(
            self.name_to_module_info)

    @staticmethod
    def _discover_mod_file_and_target(force_build):
        """Find the module file.

        Args:
            force_build: Boolean to indicate if we should rebuild the
                         module_info file regardless if it's created or not.

        Returns:
            Tuple of module_info_target and path to module file.
        """
        module_info_target = None
        root_dir = os.environ.get(constants.ANDROID_BUILD_TOP, '/')
        out_dir = os.environ.get(constants.ANDROID_OUT, root_dir)
        module_file_path = os.path.join(out_dir, _MODULE_INFO)

        # Check for custom out dir.
        out_dir_base = os.environ.get(constants.ANDROID_OUT_DIR)
        if out_dir_base is None or not os.path.isabs(out_dir_base):
            # Make target is simply file path relative to root
            module_info_target = os.path.relpath(module_file_path, root_dir)
        else:
            # Chances are a custom absolute out dir is used, use
            # ANDROID_PRODUCT_OUT instead.
            module_file_path = os.path.join(
                os.environ.get('ANDROID_PRODUCT_OUT'), _MODULE_INFO)
            module_info_target = module_file_path
        if not os.path.isfile(module_file_path) or force_build:
            logging.info('Generating %s - this is required for '
                         'initial runs.', _MODULE_INFO)
            atest_utils.build([module_info_target],
                              logging.getLogger().isEnabledFor(logging.DEBUG))
        return module_info_target, module_file_path

    def _load_module_info_file(self, force_build, module_file):
        """Load the module file.

        Args:
            force_build: Boolean to indicate if we should rebuild the
                         module_info file regardless if it's created or not.
            module_file: String of path to file to load up. Used for testing.

        Returns:
            Tuple of module_info_target and dict of json.
        """
        # If module_file is specified, we're testing so we don't care if
        # module_info_target stays None.
        module_info_target = None
        file_path = module_file
        if not file_path:
            module_info_target, file_path = self._discover_mod_file_and_target(
                force_build)
        with open(file_path) as json_file:
            mod_info = json.load(json_file)
        return module_info_target, mod_info

    @staticmethod
    def _get_path_to_module_info(name_to_module_info):
        """Return the path_to_module_info dict.

        Args:
            name_to_module_info: Dict of module name to module info dict.

        Returns:
            Dict of module path to module info dict.
        """
        path_to_module_info = {}
        for mod_name, mod_info in name_to_module_info.iteritems():
            for path in mod_info.get(_MODULE_PATH, []):
                mod_info[_MODULE_NAME] = mod_name
                # There could be multiple modules in a path.
                if path in path_to_module_info:
                    path_to_module_info[path].append(mod_info)
                else:
                    path_to_module_info[path] = [mod_info]
        return path_to_module_info

    def is_module(self, name):
        """Return True if name is a module, False otherwise."""
        return name in self.name_to_module_info

    def get_paths(self, name):
        """Return paths of supplied module name, Empty list if non-existent."""
        info = self.name_to_module_info.get(name)
        if info:
            return info.get(_MODULE_PATH, [])
        return []

    def get_module_name(self, rel_module_path):
        """Get the modules that all have module_path.

        Args:
            rel_module_path: path of module in module-info.json

        Returns:
            List of module names representing installed modules.
        """
        mods = self.path_to_module_info.get(rel_module_path, [])
        mods_to_return = []
        for mod in mods:
            if mod.get('installed'):
                # TODO: return all modules for the caller to sort through.
                mods_to_return.append(mod.get(_MODULE_NAME))
                break
        return mods_to_return[0] if mods_to_return else None

    def get_module_info(self, mod_name):
        """Return dict of info for given module name, None if non-existent."""
        return self.name_to_module_info.get(mod_name)
