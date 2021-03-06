/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.command;

import static org.easymock.EasyMock.getCurrentArguments;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.ddmlib.IDevice;
import com.android.tradefed.command.CommandFileParser.CommandLine;
import com.android.tradefed.command.CommandScheduler.CommandTracker;
import com.android.tradefed.command.CommandScheduler.CommandTrackerIdComparator;
import com.android.tradefed.command.ICommandScheduler.IScheduledInvocationListener;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.DeviceConfigurationHolder;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationFactory;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.config.proxy.ProxyConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceSelectionOptions;
import com.android.tradefed.device.FreeDeviceState;
import com.android.tradefed.device.IDeviceManager;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.device.MockDeviceManager;
import com.android.tradefed.device.NoDeviceException;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.device.TcpDevice;
import com.android.tradefed.device.TestDeviceState;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.IRescheduler;
import com.android.tradefed.invoker.ITestInvocation;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.log.ILogRegistry.EventType;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.keystore.DryRunKeyStore;
import com.android.tradefed.util.keystore.IKeyStoreClient;

import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/** Unit tests for {@link CommandScheduler}. */
@RunWith(JUnit4.class)
public class CommandSchedulerTest {

    private static final long SHORT_WAIT_MS = 100L;

    private CommandScheduler mScheduler;
    private ITestInvocation mMockInvocation;
    private MockDeviceManager mMockManager;
    private IConfigurationFactory mMockConfigFactory;
    private IConfiguration mMockConfiguration;
    private CommandOptions mCommandOptions;
    private DeviceSelectionOptions mDeviceOptions;
    private CommandFileParser mMockCmdFileParser;
    private List<IDeviceConfiguration> mMockDeviceConfig;
    private ConfigurationDescriptor mMockConfigDescriptor;
    private IKeyStoreClient mMockKeyStoreClient;
    private IInvocationContext mContext;

    class TestableCommandScheduler extends CommandScheduler {

        @Override
        ITestInvocation createRunInstance() {
            return mMockInvocation;
        }

        @Override
        protected IDeviceManager getDeviceManager() {
            return mMockManager;
        }

        @Override
        protected IConfigurationFactory getConfigFactory() {
            return mMockConfigFactory;
        }

        @Override
        protected IInvocationContext createInvocationContext() {
            return mContext;
        }

        @Override
        protected void initLogging() {
            // ignore
        }

        @Override
        protected void cleanUp() {
            // ignore
        }

        @Override
        void logEvent(EventType event, Map<String, String> args) {
            // ignore
        }

        @Override
        void checkInvocations() {
            // ignore
        }

        @Override
        CommandFileParser createCommandFileParser() {
            return mMockCmdFileParser;
        }

        @Override
        protected IKeyStoreClient getKeyStoreClient() {
            return mMockKeyStoreClient;
        }
    }

    @Before
    public void setUp() throws Exception {
        mMockInvocation = EasyMock.createMock(ITestInvocation.class);
        mMockManager = new MockDeviceManager(0);
        mMockConfigFactory = EasyMock.createMock(IConfigurationFactory.class);
        mMockKeyStoreClient = EasyMock.createMock(IKeyStoreClient.class);
        mMockConfiguration = EasyMock.createMock(IConfiguration.class);
        EasyMock.expect(mMockConfiguration.getTests()).andStubReturn(new ArrayList<>());
        EasyMock.expect(
                        mMockConfiguration.getConfigurationObject(
                                ProxyConfiguration.PROXY_CONFIG_TYPE_KEY))
                .andStubReturn(null);
        mCommandOptions = new CommandOptions();
        // Avoid any issue related to env. variable.
        mDeviceOptions =
                new DeviceSelectionOptions() {
                    @Override
                    public String fetchEnvironmentVariable(String name) {
                        return null;
                    }
                };
        mMockDeviceConfig = new ArrayList<IDeviceConfiguration>();
        mMockConfigDescriptor = new ConfigurationDescriptor();
        mContext = new InvocationContext();

        mScheduler = new TestableCommandScheduler();
        // not starting the CommandScheduler yet because test methods need to setup mocks first
    }

    @After
    public void tearDown() throws Exception {
        if (mScheduler != null) {
            mScheduler.shutdown();
        }
    }

    /**
     * Switch all mock objects to replay mode
     */
    private void replayMocks(Object... additionalMocks) {
        EasyMock.replay(
                mMockConfigFactory, mMockConfiguration, mMockInvocation, mMockKeyStoreClient);
        for (Object mock : additionalMocks) {
            EasyMock.replay(mock);
        }
    }

    /**
     * Verify all mock objects
     */
    private void verifyMocks(Object... additionalMocks) {
        EasyMock.verify(
                mMockConfigFactory, mMockConfiguration, mMockInvocation, mMockKeyStoreClient);
        for (Object mock : additionalMocks) {
            EasyMock.verify(mock);
        }
        mMockManager.assertDevicesFreed();
    }

    /** Test {@link CommandScheduler#run()} when no configs have been added */
    @Test
    public void testRun_empty() throws InterruptedException {
        mMockManager.setNumDevices(1);
        replayMocks();
        mScheduler.start();
        while (!mScheduler.isAlive()) {
            Thread.sleep(10);
        }
        mScheduler.shutdown();
        // expect run not to block
        mScheduler.join();
        verifyMocks();
    }

    /** Test {@link CommandScheduler#addCommand(String[])} when help mode is specified */
    @Test
    public void testAddConfig_configHelp() throws ConfigurationException {
        String[] args = new String[] {"test"};
        mCommandOptions.setHelpMode(true);
        setCreateConfigExpectations(args, 1);
        // expect
        mMockConfigFactory.printHelpForConfig(EasyMock.aryEq(args), EasyMock.eq(true),
                EasyMock.eq(System.out));
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        verifyMocks();
    }

    /** Test {@link CommandScheduler#run()} when one config has been added */
    @Test
    public void testRun_oneConfig() throws Throwable {
        String[] args = new String[] {"test"};
        mMockManager.setNumDevices(2);
        setCreateConfigExpectations(args, 1);
        setExpectedInvokeCalls(1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        mScheduler.shutdownOnEmpty();
        mScheduler.join();
        verifyMocks();
    }

    /**
     * Test {@link CommandScheduler#removeAllCommands()} for idle case, where command is waiting for
     * device.
     */
    @Test
    public void testRemoveAllCommands() throws Throwable {
        String[] args = new String[] {"test"};
        mMockManager.setNumDevices(0);
        setCreateConfigExpectations(args, 1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        assertEquals(1, mScheduler.getAllCommandsSize());
        mScheduler.removeAllCommands();
        assertEquals(0, mScheduler.getAllCommandsSize());
        verifyMocks();
    }

    /** Test {@link CommandScheduler#run()} when one config has been added in dry-run mode */
    @Test
    public void testRun_dryRun() throws Throwable {
        String[] dryRunArgs = new String[] {"--dry-run"};
        mCommandOptions.setDryRunMode(true);
        mMockManager.setNumDevices(2);
        setCreateConfigExpectations(dryRunArgs, 1);

        // add a second command, to verify the first dry-run command did not get added
        String[] args2 = new String[] {"test"};
        setCreateConfigExpectations(args2, 1);
        setExpectedInvokeCalls(1);
        mMockConfiguration.validateOptions();
        EasyMock.expectLastCall().times(2);

        replayMocks();
        mScheduler.start();
        assertFalse(mScheduler.addCommand(dryRunArgs));
        // the same config object is being used, so clear its state
        mCommandOptions.setDryRunMode(false);
        assertTrue(mScheduler.addCommand(args2));
        mScheduler.shutdownOnEmpty();
        mScheduler.join();
        verifyMocks();
    }

    /**
     * Test {@link CommandScheduler#run()} when one config has been added in noisy-dry-run or
     * dry-run mode the keystore is properly faked by a {@link DryRunKeyStore}.
     */
    @Test
    public void testRun_dryRun_keystore() throws Throwable {
        mScheduler =
                new TestableCommandScheduler() {
                    @Override
                    protected IConfigurationFactory getConfigFactory() {
                        // Use the real factory for that loading test.
                        return ConfigurationFactory.getInstance();
                    }
                };
        String[] dryRunArgs =
                new String[] {"empty", "--noisy-dry-run", "--min-loop-time", "USE_KEYSTORE@fake"};
        mMockManager.setNumDevices(2);
        //setCreateConfigExpectations(dryRunArgs, 1);

        replayMocks();
        mScheduler.start();
        assertFalse(mScheduler.addCommand(dryRunArgs));
        mScheduler.shutdownOnEmpty();
        mScheduler.join();
        verifyMocks();
    }

    /**
     * Test simple case for {@link CommandScheduler#execCommand(IScheduledInvocationListener,
     * String[])}
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testExecCommand() throws Throwable {
        String[] args = new String[] {
            "foo"
        };
        setCreateConfigExpectations(args, 1);
        mMockInvocation.invoke(
                (IInvocationContext) EasyMock.anyObject(),
                (IConfiguration) EasyMock.anyObject(),
                (IRescheduler) EasyMock.anyObject(),
                (ITestInvocationListener) EasyMock.anyObject(),
                EasyMock.anyObject());
        EasyMock.expectLastCall().times(1);
        mMockConfiguration.validateOptions();
        IDevice mockIDevice = EasyMock.createMock(IDevice.class);
        ITestDevice mockDevice = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(mockDevice.getSerialNumber()).andStubReturn("serial");
        EasyMock.expect(mockDevice.getDeviceState()).andStubReturn(TestDeviceState.ONLINE);
        mockDevice.setRecoveryMode(EasyMock.eq(RecoveryMode.AVAILABLE));
        EasyMock.expect(mockDevice.getIDevice()).andStubReturn(mockIDevice);
        IScheduledInvocationListener mockListener = EasyMock
                .createMock(IScheduledInvocationListener.class);
        mockListener.invocationInitiated((IInvocationContext) EasyMock.anyObject());
        mockListener.invocationComplete((IInvocationContext)EasyMock.anyObject(),
                (Map<ITestDevice, FreeDeviceState>)EasyMock.anyObject());
        EasyMock.expect(mockDevice.waitForDeviceShell(EasyMock.anyLong())).andReturn(true);
        mScheduler =
                new TestableCommandScheduler() {
                    @Override
                    Map<String, ITestDevice> allocateDevices(
                            IConfiguration config, IDeviceManager manager) {
                        Map<String, ITestDevice> allocated = new HashMap<>();
                        ((MockDeviceManager) manager).addDevice(mockDevice);
                        allocated.put("device", ((MockDeviceManager) manager).allocateDevice());
                        return allocated;
                    }
                };
        replayMocks(mockDevice, mockListener);
        mScheduler.start();
        mScheduler.execCommand(mockListener, args);
        mScheduler.shutdownOnEmpty();
        mScheduler.join(2*1000);
        verifyMocks(mockListener);
    }

    /**
     * Sets the number of expected
     * {@link ITestInvocation#invoke(IInvocationContext, IConfiguration, IRescheduler,
     *      ITestInvocationListener[])} calls
     *
     * @param times
     */
    private void setExpectedInvokeCalls(int times) throws Throwable {
        mMockInvocation.invoke((IInvocationContext)EasyMock.anyObject(),
                (IConfiguration)EasyMock.anyObject(), (IRescheduler)EasyMock.anyObject(),
                (ITestInvocationListener)EasyMock.anyObject());
        EasyMock.expectLastCall().times(times);
    }

    /**
     * Sets up a object that will notify when the expected number of
     * {@link ITestInvocation#invoke(IInvocationContext, IConfiguration, IRescheduler,
     *      ITestInvocationListener[])} calls occurs
     *
     * @param times
     */
    private Object waitForExpectedInvokeCalls(final int times) throws Throwable {
        IAnswer<Object> blockResult = new IAnswer<Object>() {
            private int mCalls = 0;
            @Override
            public Object answer() throws Throwable {
                synchronized(this) {
                    mCalls++;
                    if (times == mCalls) {
                        notifyAll();
                    }
                }
                return null;
            }
        };
        mMockInvocation.invoke((IInvocationContext)EasyMock.anyObject(),
                (IConfiguration)EasyMock.anyObject(), (IRescheduler)EasyMock.anyObject(),
                (ITestInvocationListener)EasyMock.anyObject());
        EasyMock.expectLastCall().andAnswer(blockResult);
        EasyMock.expectLastCall().andAnswer(blockResult);
        return blockResult;
    }

    /** Test {@link CommandScheduler#run()} when one config has been added in a loop */
    @Test
    public void testRun_oneConfigLoop() throws Throwable {
        String[] args = new String[] {"test"};
        // track if exception occurs on scheduler thread
        UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        try {
            ExceptionTracker tracker = new ExceptionTracker();
            Thread.setDefaultUncaughtExceptionHandler(tracker);
            mMockManager.setNumDevices(1);
            // config should only be created three times
            setCreateConfigExpectations(args, 3);
            mCommandOptions.setLoopMode(true);
            mCommandOptions.setMinLoopTime(50);
            Object notifier = waitForExpectedInvokeCalls(2);
            mMockConfiguration.validateOptions();
            replayMocks();
            mScheduler.start();
            mScheduler.addCommand(args);
            synchronized (notifier) {
                notifier.wait(1 * 1000);
            }
            mScheduler.shutdown();
            mScheduler.join();
            // Wait a little for device to be released.
            RunUtil.getDefault().sleep(SHORT_WAIT_MS);
            verifyMocks();
            assertNull("exception occurred on background thread!", tracker.mThrowable);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(defaultHandler);
        }
    }

    class ExceptionTracker implements UncaughtExceptionHandler {

        private Throwable mThrowable = null;

        /**
         * {@inheritDoc}
         */
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            e.printStackTrace();
            mThrowable  = e;
        }
    }

    /**
     * Test{@link CommandScheduler#run()} when config is matched to a specific device serial number
     *
     * <p>Adds two configs to run, and verify they both run on one device
     */
    @Test
    public void testRun_configSerial() throws Throwable {
        String[] args = new String[] {"test"};
        mMockManager.setNumDevices(2);
        setCreateConfigExpectations(args, 2);
        // allocate and free a device to get its serial
        ITestDevice dev = mMockManager.allocateDevice();
        mDeviceOptions.addSerial(dev.getSerialNumber());
        setExpectedInvokeCalls(1);
        mMockConfiguration.validateOptions();
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        mScheduler.addCommand(args);
        mMockManager.freeDevice(dev, FreeDeviceState.AVAILABLE);

        mScheduler.shutdownOnEmpty();
        mScheduler.join();
        verifyMocks();
    }

    /**
     * Test{@link CommandScheduler#run()} when config is matched to a exclude specific device serial
     * number.
     *
     * <p>Adds two configs to run, and verify they both run on the other device
     */
    @Test
    public void testRun_configExcludeSerial() throws Throwable {
        String[] args = new String[] {"test"};
        mMockManager.setNumDevices(2);
        setCreateConfigExpectations(args, 2);
        // allocate and free a device to get its serial
        ITestDevice dev = mMockManager.allocateDevice();
        mDeviceOptions.addExcludeSerial(dev.getSerialNumber());
        ITestDevice expectedDevice = mMockManager.allocateDevice();
        setExpectedInvokeCalls(1);
        mMockConfiguration.validateOptions();
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        mScheduler.addCommand(args);
        mMockManager.freeDevice(dev, FreeDeviceState.AVAILABLE);
        mMockManager.freeDevice(expectedDevice, FreeDeviceState.AVAILABLE);
        mScheduler.shutdownOnEmpty();
        mScheduler.join();
        verifyMocks();
    }

    /** Test {@link CommandScheduler#run()} when one config has been rescheduled */
    @Test
    public void testRun_rescheduled() throws Throwable {
        String[] args = new String[] {"test"};
        mMockManager.setNumDevices(2);
        setCreateConfigExpectations(args, 1);
        mMockConfiguration.validateOptions();
        final IConfiguration rescheduledConfig = EasyMock.createMock(IConfiguration.class);
        EasyMock.expect(rescheduledConfig.getCommandOptions()).andStubReturn(mCommandOptions);
        EasyMock.expect(rescheduledConfig.getDeviceRequirements()).andStubReturn(
                mDeviceOptions);
        EasyMock.expect(rescheduledConfig.getDeviceConfig()).andStubReturn(mMockDeviceConfig);
        EasyMock.expect(rescheduledConfig.getCommandLine()).andStubReturn("");
        EasyMock.expect(rescheduledConfig.getConfigurationDescription())
            .andStubReturn(mMockConfigDescriptor);

        // an ITestInvocationn#invoke response for calling reschedule
        IAnswer<Object> rescheduleAndThrowAnswer = new IAnswer<Object>() {
            @Override
            public Object answer() throws Throwable {
                IRescheduler rescheduler =  (IRescheduler) EasyMock.getCurrentArguments()[2];
                rescheduler.scheduleConfig(rescheduledConfig);
                throw new DeviceNotAvailableException("not avail", "fakeserial");
            }
        };

        mMockInvocation.invoke(EasyMock.<IInvocationContext>anyObject(),
                EasyMock.<IConfiguration>anyObject(), EasyMock.<IRescheduler>anyObject(),
                EasyMock.<ITestInvocationListener>anyObject());
        EasyMock.expectLastCall().andAnswer(rescheduleAndThrowAnswer);

        // expect one more success call
        setExpectedInvokeCalls(1);

        replayMocks(rescheduledConfig);
        mScheduler.start();
        mScheduler.addCommand(args);
        mScheduler.shutdownOnEmpty();
        mScheduler.join();

        EasyMock.verify(mMockConfigFactory, mMockConfiguration, mMockInvocation);
    }

    /**
     * Simple success case test for {@link CommandScheduler#addCommandFile(String, java.util.List)}
     *
     * @throws ConfigurationException
     */
    @Test
    public void testAddCommandFile() throws ConfigurationException {
        // set number of devices to 0 so we can verify command presence
        mMockManager.setNumDevices(0);
        List<String> extraArgs = Arrays.asList("--bar");
        setCreateConfigExpectations(new String[] {"foo", "--bar"}, 1);
        mMockConfiguration.validateOptions();
        final List<CommandLine> cmdFileContent = Arrays.asList(new CommandLine(
                Arrays.asList("foo"), null, 0));
        mMockCmdFileParser = new CommandFileParser() {
            @Override
            public List<CommandLine> parseFile(File cmdFile) {
                return cmdFileContent;
            }
        };
        replayMocks();

        mScheduler.start();
        mScheduler.addCommandFile("mycmd.txt", extraArgs);
        List<CommandTracker> cmds = mScheduler.getCommandTrackers();
        assertEquals(1, cmds.size());
        assertEquals("foo", cmds.get(0).getArgs()[0]);
        assertEquals("--bar", cmds.get(0).getArgs()[1]);
    }

    /**
     * Simple success case test for auto reloading a command file
     *
     * @throws ConfigurationException
     */
    @Test
    public void testAddCommandFile_reload() throws ConfigurationException {
        // set number of devices to 0 so we can verify command presence
        mMockManager.setNumDevices(0);
        String[] addCommandArgs = new String[]{"fromcommand"};
        List<String> extraArgs = Arrays.asList("--bar");

        setCreateConfigExpectations(addCommandArgs, 1);
        String[] cmdFile1Args = new String[] {"fromFile1", "--bar"};
        setCreateConfigExpectations(cmdFile1Args, 1);
        String[] cmdFile2Args = new String[] {"fromFile2", "--bar"};
        setCreateConfigExpectations(cmdFile2Args, 1);

        mMockConfiguration.validateOptions();
        EasyMock.expectLastCall().times(3);

        final List<CommandLine> cmdFileContent1 = Arrays.asList(new CommandLine(
                Arrays.asList("fromFile1"), null, 0));
        final List<CommandLine> cmdFileContent2 = Arrays.asList(new CommandLine(
                Arrays.asList("fromFile2"), null, 0));
        mMockCmdFileParser = new CommandFileParser() {
            boolean firstCall = true;
            @Override
            public List<CommandLine> parseFile(File cmdFile) {
                if (firstCall) {
                    firstCall = false;
                    return cmdFileContent1;
                }
                return cmdFileContent2;
            }
        };
        replayMocks();
        mScheduler.start();
        mScheduler.setCommandFileReload(true);
        mScheduler.addCommand(addCommandArgs);
        mScheduler.addCommandFile("mycmd.txt", extraArgs);

        List<CommandTracker> cmds = mScheduler.getCommandTrackers();
        assertEquals(2, cmds.size());
        Collections.sort(cmds, new CommandTrackerIdComparator());
        Assert.assertArrayEquals(addCommandArgs, cmds.get(0).getArgs());
        Assert.assertArrayEquals(cmdFile1Args, cmds.get(1).getArgs());

        // now reload the command file
        mScheduler.notifyFileChanged(new File("mycmd.txt"), extraArgs);

        cmds = mScheduler.getCommandTrackers();
        assertEquals(2, cmds.size());
        Collections.sort(cmds, new CommandTrackerIdComparator());
        Assert.assertArrayEquals(addCommandArgs, cmds.get(0).getArgs());
        Assert.assertArrayEquals(cmdFile2Args, cmds.get(1).getArgs());
    }

    /** Verify attempts to add the same commmand file in reload mode are rejected */
    @Test
    public void testAddCommandFile_twice() throws ConfigurationException {
        // set number of devices to 0 so we can verify command presence
        mMockManager.setNumDevices(0);
        String[] cmdFile1Args = new String[] {"fromFile1"};
        setCreateConfigExpectations(cmdFile1Args, 1);
        setCreateConfigExpectations(cmdFile1Args, 1);
        mMockConfiguration.validateOptions();
        EasyMock.expectLastCall().times(2);

        final List<CommandLine> cmdFileContent1 = Arrays.asList(new CommandLine(
                Arrays.asList("fromFile1"), null, 0));
        mMockCmdFileParser = new CommandFileParser() {
            @Override
            public List<CommandLine> parseFile(File cmdFile) {
                return cmdFileContent1;
            }
        };
        replayMocks();
        mScheduler.start();
        mScheduler.setCommandFileReload(true);
        mScheduler.addCommandFile("mycmd.txt", Collections.<String>emptyList());

        List<CommandTracker> cmds = mScheduler.getCommandTrackers();
        assertEquals(1, cmds.size());
        Assert.assertArrayEquals(cmdFile1Args, cmds.get(0).getArgs());

        // now attempt to add the same command file
        mScheduler.addCommandFile("mycmd.txt", Collections.<String>emptyList());

        // expect reload
        // ensure same state as before
        cmds = mScheduler.getCommandTrackers();
        assertEquals(1, cmds.size());
        Assert.assertArrayEquals(cmdFile1Args, cmds.get(0).getArgs());
    }

    /** Test {@link CommandScheduler#shutdown()} when no devices are available. */
    @Test
    public void testShutdown() throws Exception {
        mMockManager.setNumDevices(0);
        mScheduler.start();
        while (!mScheduler.isAlive()) {
            Thread.sleep(10);
        }
        // hack - sleep a bit more to ensure allocateDevices is called
        Thread.sleep(50);
        mScheduler.shutdown();
        mScheduler.join();
        // test will hang if not successful
    }

    /**
     * Set EasyMock expectations for a create configuration call.
     */
    private void setCreateConfigExpectations(String[] args, int times)
            throws ConfigurationException {
        List<String> nullArg = null;
        EasyMock.expect(
                mMockConfigFactory.createConfigurationFromArgs(EasyMock.aryEq(args),
                EasyMock.eq(nullArg), (IKeyStoreClient)EasyMock.anyObject()))
                .andReturn(mMockConfiguration)
                .times(times);
        EasyMock.expect(mMockConfiguration.getCommandOptions()).andStubReturn(mCommandOptions);
        EasyMock.expect(mMockConfiguration.getDeviceRequirements()).andStubReturn(
                mDeviceOptions);
        EasyMock.expect(mMockConfiguration.getDeviceConfig()).andStubReturn(mMockDeviceConfig);
        EasyMock.expect(mMockConfiguration.getCommandLine()).andStubReturn("");
        EasyMock.expect(mMockConfiguration.getConfigurationDescription())
            .andStubReturn(mMockConfigDescriptor);

        // Assume all legacy test are single device
        if (mMockDeviceConfig.isEmpty()) {
            IDeviceConfiguration mockConfig = new DeviceConfigurationHolder("device");
            mockConfig.addSpecificConfig(mDeviceOptions);
            mMockDeviceConfig.add(mockConfig);
        }
    }

    /** Test that Available device at the end of a test are available to be reselected. */
    @Test
    public void testDeviceReleased() throws Throwable {
        String[] args = new String[] {"test"};
        mMockManager.setNumDevices(1);
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 1);
        setCreateConfigExpectations(args, 1);
        setExpectedInvokeCalls(1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        mScheduler.shutdownOnEmpty();
        mScheduler.join();
        verifyMocks();
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 1);
    }

    /**
     * Test that if device is released properly and marked as such, the next invocation can run
     * without issues.
     */
    @Test
    public void testDeviceReleasedEarly() throws Throwable {
        String[] args = new String[] {"test"};
        mMockManager.setNumDevices(1);
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 1);
        setCreateConfigExpectations(args, 2);

        mMockInvocation.invoke(
                (IInvocationContext) EasyMock.anyObject(),
                (IConfiguration) EasyMock.anyObject(),
                (IRescheduler) EasyMock.anyObject(),
                (ITestInvocationListener) EasyMock.anyObject());
        EasyMock.expectLastCall()
                .andAnswer(
                        new IAnswer<Object>() {
                            @Override
                            public Object answer() throws Throwable {
                                IInvocationContext context =
                                        (IInvocationContext) getCurrentArguments()[0];
                                IScheduledInvocationListener listener =
                                        (IScheduledInvocationListener) getCurrentArguments()[3];
                                Map<ITestDevice, FreeDeviceState> deviceStates = new HashMap<>();
                                for (ITestDevice device : context.getDevices()) {
                                    deviceStates.put(device, FreeDeviceState.AVAILABLE);
                                }
                                context.markReleasedEarly();
                                listener.releaseDevices(context, deviceStates);
                                RunUtil.getDefault().sleep(500);
                                return null;
                            }
                        });
        // Second invocation runs properly
        setExpectedInvokeCalls(1);

        mMockConfiguration.validateOptions();
        EasyMock.expectLastCall().times(2);
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        RunUtil.getDefault().sleep(100);
        mScheduler.addCommand(args);
        RunUtil.getDefault().sleep(200);
        mScheduler.shutdown();
        mScheduler.join();
        verifyMocks();
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 1);
        assertNull(mScheduler.getLastInvocationThrowable());
    }

    /**
     * If for any reasons the device is released early and it's unexpected, we still release it in
     * the next invocation properly.
     */
    @Test
    public void testDeviceReleasedEarly_conflict() throws Throwable {
        String[] args = new String[] {"test"};
        mMockManager.setNumDevices(1);
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 1);
        setCreateConfigExpectations(args, 2);

        mMockInvocation.invoke(
                (IInvocationContext) EasyMock.anyObject(),
                (IConfiguration) EasyMock.anyObject(),
                (IRescheduler) EasyMock.anyObject(),
                (ITestInvocationListener) EasyMock.anyObject());
        EasyMock.expectLastCall()
                .andAnswer(
                        new IAnswer<Object>() {
                            @Override
                            public Object answer() throws Throwable {
                                IInvocationContext context =
                                        (IInvocationContext) getCurrentArguments()[0];
                                IScheduledInvocationListener listener =
                                        (IScheduledInvocationListener) getCurrentArguments()[3];
                                Map<ITestDevice, FreeDeviceState> deviceStates = new HashMap<>();
                                for (ITestDevice device : context.getDevices()) {
                                    deviceStates.put(device, FreeDeviceState.AVAILABLE);
                                }
                                // Device is released early but this is not marked properly in
                                // context
                                listener.releaseDevices(context, deviceStates);
                                RunUtil.getDefault().sleep(500);
                                return null;
                            }
                        });
        mMockConfiguration.validateOptions();
        EasyMock.expectLastCall().times(2);
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        RunUtil.getDefault().sleep(100);
        mScheduler.addCommand(args);
        RunUtil.getDefault().sleep(200);
        mScheduler.shutdown();
        mScheduler.join();
        verifyMocks();
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 1);
        assertNotNull(mScheduler.getLastInvocationThrowable());
        assertEquals(
                "Attempting invocation on device serial0 when one is already running",
                mScheduler.getLastInvocationThrowable().getMessage());
    }

    /**
     * Test that NOT_AVAILABLE devices at the end of a test are not returned to the selectable
     * devices.
     */
    @Test
    public void testDeviceReleased_unavailable() throws Throwable {
        String[] args = new String[] {"test"};
        mMockManager.setNumDevicesCustom(1, TestDeviceState.NOT_AVAILABLE, IDevice.class);
        assertEquals(1, mMockManager.getQueueOfAvailableDeviceSize());
        setCreateConfigExpectations(args, 1);
        setExpectedInvokeCalls(1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        mScheduler.shutdownOnEmpty();
        mScheduler.join();
        EasyMock.verify(mMockConfigFactory, mMockConfiguration, mMockInvocation);
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 0);
    }

    /**
     * Test that only the device NOT_AVAILABLE, selected for invocation is not returned at the end.
     */
    @Test
    public void testDeviceReleased_unavailableMulti() throws Throwable {
        String[] args = new String[] {"test"};
        mMockManager.setNumDevicesCustom(2, TestDeviceState.NOT_AVAILABLE, IDevice.class);
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 2);
        setCreateConfigExpectations(args, 1);
        setExpectedInvokeCalls(1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        mScheduler.shutdownOnEmpty();
        mScheduler.join();
        EasyMock.verify(mMockConfigFactory, mMockConfiguration, mMockInvocation);
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 1);
    }

    /** Test that the TCP device NOT available are NOT released. */
    @Test
    public void testTcpDevice_NotReleased() throws Throwable {
        String[] args = new String[] {"test"};
        mMockManager.setNumDevicesStub(1, TestDeviceState.NOT_AVAILABLE, new TcpDevice("serial"));
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 1);
        setCreateConfigExpectations(args, 1);
        setExpectedInvokeCalls(1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        mScheduler.shutdownOnEmpty();
        mScheduler.join();
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 1);
        EasyMock.verify(mMockConfigFactory, mMockConfiguration, mMockInvocation);
    }

    /** Test that the TCP device NOT available selected for a run is NOT released. */
    @Test
    public void testTcpDevice_NotReleasedMulti() throws Throwable {
        String[] args = new String[] {"test"};
        mMockManager.setNumDevicesStub(2, TestDeviceState.NOT_AVAILABLE, new TcpDevice("serial"));
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 2);
        setCreateConfigExpectations(args, 1);
        setExpectedInvokeCalls(1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        mScheduler.shutdownOnEmpty();
        mScheduler.join();
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 2);
        EasyMock.verify(mMockConfigFactory, mMockConfiguration, mMockInvocation);
    }

    /** Test that the Stub device NOT available are NOT released. */
    @Test
    public void testStubDevice_NotReleased() throws Throwable {
        String[] args = new String[] {"test"};
        IDevice stub = new StubDevice("emulator-5554", true);
        mMockManager.setNumDevicesStub(1, TestDeviceState.NOT_AVAILABLE, stub);
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 1);
        setCreateConfigExpectations(args, 1);
        setExpectedInvokeCalls(1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        mScheduler.shutdownOnEmpty();
        mScheduler.join();
        assertTrue(mMockManager.getQueueOfAvailableDeviceSize() == 1);
        EasyMock.verify(mMockConfigFactory, mMockConfiguration, mMockInvocation);
    }

    /** Test that a device recovery state is reset when returned to the available queue. */
    @Test
    public void testDeviceRecoveryState() throws Throwable {
        String[] args = new String[] {"test"};
        mMockManager.setNumDevicesCustomRealNoRecovery(1, IDevice.class);
        assertEquals(1, mMockManager.getQueueOfAvailableDeviceSize());
        setCreateConfigExpectations(args, 1);
        setExpectedInvokeCalls(1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        mScheduler.shutdownOnEmpty();
        mScheduler.join();
        EasyMock.verify(mMockConfigFactory, mMockConfiguration, mMockInvocation);
        assertEquals(1, mMockManager.getQueueOfAvailableDeviceSize());
        ITestDevice t = mMockManager.allocateDevice();
        assertTrue(t.getRecoveryMode().equals(RecoveryMode.AVAILABLE));
    }

    /** Test that a device that is unresponsive at the end of an invocation is made unavailable. */
    @Test
    public void testDevice_unresponsive() throws Throwable {
        String[] args = new String[] {"test"};
        mMockManager.setNumDevicesUnresponsive(1);
        assertEquals(1, mMockManager.getQueueOfAvailableDeviceSize());
        setCreateConfigExpectations(args, 1);
        setExpectedInvokeCalls(1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        mScheduler.shutdownOnEmpty();
        mScheduler.join();
        EasyMock.verify(mMockConfigFactory, mMockConfiguration, mMockInvocation);
        // Device does not return to the list since it's unavailable.
        assertEquals(0, mMockManager.getQueueOfAvailableDeviceSize());
    }

    /**
     * Test that {@link CommandScheduler#displayCommandQueue(PrintWriter)} is properly printing the
     * state of a command.
     */
    @Test
    public void testDisplayCommandQueue() throws Throwable {
        String[] args = new String[] {"empty"};
        setCreateConfigExpectations(args, 1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        OutputStream res = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(res);
        mScheduler.displayCommandQueue(pw);
        verifyMocks();
        pw.flush();
        assertEquals("Id  Config  Created  Exec time  State            Sleep time  Rescheduled  "
                + "Loop   \n1   empty   0m:00    0m:00      Wait_for_device  N/A         false  "
                + "      false  \n", res.toString());
        mScheduler.shutdown();
    }

    /**
     * Test that {@link CommandScheduler#dumpCommandsXml(PrintWriter, String)} is properly printing
     * the xml of a command.
     */
    @Test
    public void testDumpCommandXml() throws Throwable {
        String[] args = new String[] {"empty"};
        OutputStream res = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(res);
        setCreateConfigExpectations(args, 1);
        mMockConfiguration.validateOptions();
        mMockConfiguration.dumpXml(EasyMock.anyObject());
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        mScheduler.dumpCommandsXml(pw, null);
        verifyMocks();
        pw.flush();
        String filename = res.toString().replace("Saved command dump to ", "").trim();
        File test = new File(filename);
        try {
            assertTrue(test.exists());
            mScheduler.shutdown();
        } finally {
            FileUtil.deleteFile(test);
        }
    }

    /**
     * Test that {@link CommandScheduler#displayCommandsInfo(PrintWriter, String)} is properly
     * printing the command.
     */
    @Test
    public void testDisplayCommandsInfo() throws Throwable {
        String[] args = new String[] {"empty"};
        setCreateConfigExpectations(args, 1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        OutputStream res = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(res);
        mScheduler.displayCommandsInfo(pw, null);
        verifyMocks();
        pw.flush();
        assertEquals("Command 1: [0m:00] empty\n", res.toString());
        mScheduler.shutdown();
    }

    /**
     * Test that {@link CommandScheduler#getInvocationInfo(int)} is properly returning null if no
     * invocation matching the id.
     */
    @Test
    public void testGetInvocationInfo_null() throws Throwable {
        String[] args = new String[] {"empty", "test"};
        setCreateConfigExpectations(args, 1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        mScheduler.addCommand(args);
        assertNull(mScheduler.getInvocationInfo(999));
        mScheduler.shutdown();
    }

    @Test
    public void testAllocateDevices() throws Exception {
        String[] args = new String[] {"foo", "test"};
        mMockManager.setNumDevices(1);
        setCreateConfigExpectations(args, 1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        Map<String, ITestDevice> devices = mScheduler.allocateDevices(
                mMockConfiguration, mMockManager);
        assertEquals(1, devices.size());
        mScheduler.shutdown();
    }

    @Test
    public void testAllocateDevices_replicated() throws Exception {
        String[] args = new String[] {"foo", "test"};
        mMockManager.setNumDevices(3);
        setCreateConfigExpectations(args, 1);
        OptionSetter setter = new OptionSetter(mCommandOptions);
        setter.setOptionValue("replicate-parent-setup", "true");
        mCommandOptions.setShardCount(3);
        mMockConfiguration.validateOptions();
        for (int i = 0; i < 2; i++) {
            IConfiguration configReplicat = new Configuration("test", "test");
            configReplicat.setDeviceConfig(new DeviceConfigurationHolder("serial"));
            EasyMock.expect(
                            mMockConfiguration.partialDeepClone(
                                    Arrays.asList(Configuration.DEVICE_NAME), mMockKeyStoreClient))
                    .andReturn(configReplicat);
        }
        mMockConfiguration.setDeviceConfigList(EasyMock.anyObject());
        replayMocks();
        mScheduler.start();
        Map<String, ITestDevice> devices =
                mScheduler.allocateDevices(mMockConfiguration, mMockManager);
        // With replicated setup, all devices get allocated.
        assertEquals(3, devices.size());
        mScheduler.shutdown();
    }

    private IDeviceConfiguration createDeviceConfig(String serial) throws Exception {
        IDeviceConfiguration mockConfig = new DeviceConfigurationHolder(serial);
        DeviceSelectionOptions options = new DeviceSelectionOptions();
        options.addSerial(serial);
        mockConfig.addSpecificConfig(options);
        return mockConfig;
    }

    @Test
    public void testAllocateDevices_multipleDevices() throws Exception {
        String[] args = new String[] {"foo", "test"};

        mMockManager.setNumDevices(2);
        mMockDeviceConfig.add(createDeviceConfig("serial0"));
        mMockDeviceConfig.add(createDeviceConfig("serial1"));

        setCreateConfigExpectations(args, 1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        Map<String, ITestDevice> devices = mScheduler.allocateDevices(
                mMockConfiguration, mMockManager);
        assertEquals(2, devices.size());
        assertEquals(0, mMockManager.getQueueOfAvailableDeviceSize());
        mScheduler.shutdown();
    }

    @Test
    public void testAllocateDevices_multipleDevices_failed() throws Exception {
        String[] args = new String[] {"foo", "test"};

        mMockManager.setNumDevices(2);
        mMockDeviceConfig.add(createDeviceConfig("serial0"));
        mMockDeviceConfig.add(createDeviceConfig("not_exist_serial"));

        setCreateConfigExpectations(args, 1);
        mMockConfiguration.validateOptions();
        replayMocks();
        mScheduler.start();
        Map<String, ITestDevice> devices = mScheduler.allocateDevices(
                mMockConfiguration, mMockManager);
        assertEquals(0, devices.size());
        assertEquals(2, mMockManager.getQueueOfAvailableDeviceSize());
        mScheduler.shutdown();
    }

    /**
     * Test case for execCommand with multiple devices. {@link
     * CommandScheduler#execCommand(IScheduledInvocationListener, String[])}
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testExecCommand_multipleDevices() throws Throwable {
        String[] args = new String[] {
            "foo"
        };
        mMockManager.setNumDevices(2);
        mMockDeviceConfig.add(createDeviceConfig("serial0"));
        mMockDeviceConfig.add(createDeviceConfig("serial1"));
        setCreateConfigExpectations(args, 1);
        mMockConfiguration.validateOptions();
        mMockInvocation.invoke((IInvocationContext)EasyMock.anyObject(),
                (IConfiguration)EasyMock.anyObject(), (IRescheduler)EasyMock.anyObject(),
                (ITestInvocationListener)EasyMock.anyObject(),
                // This is FreeDeviceHandler.
                (IScheduledInvocationListener)EasyMock.anyObject());
        IScheduledInvocationListener mockListener = EasyMock
                .createMock(IScheduledInvocationListener.class);
        mockListener.invocationInitiated((IInvocationContext) EasyMock.anyObject());
        mockListener.invocationComplete((IInvocationContext)EasyMock.anyObject(),
                (Map<ITestDevice, FreeDeviceState>)EasyMock.anyObject());
        replayMocks(mockListener);

        mScheduler.start();
        mScheduler.execCommand(mockListener, args);
        mScheduler.shutdownOnEmpty();
        mScheduler.join(2 * 1000);
        verifyMocks(mockListener);
    }

    /**
     * Test case for execCommand with multiple devices but fail to allocate some device. {@link
     * CommandScheduler#execCommand(IScheduledInvocationListener, String[])}
     */
    @Test
    public void testExecCommand_multipleDevices_noDevice() throws Throwable {
        String[] args = new String[] {
            "foo"
        };
        mMockManager.setNumDevices(2);
        mMockDeviceConfig.add(createDeviceConfig("serial0"));
        mMockDeviceConfig.add(createDeviceConfig("not_exist_serial"));
        setCreateConfigExpectations(args, 1);
        mMockConfiguration.validateOptions();
        IScheduledInvocationListener mockListener = EasyMock
                .createMock(IScheduledInvocationListener.class);
        replayMocks(mockListener);

        mScheduler.start();
        try {
            mScheduler.execCommand(mockListener, args);
            fail();
        } catch (NoDeviceException e) {
            // expect NoDeviceException
        }
        mScheduler.shutdownOnEmpty();
        mScheduler.join(2 * 1000);
        verifyMocks(mockListener);
    }

    /**
     * Test that when a command runs in the versioned subprocess with --invocation-data option we do
     * not add the attributes again
     */
    @Test
    public void testExecCommand_versioning() throws Throwable {
        String[] args =
                new String[] {
                    "foo", "--invocation-data", "test",
                };
        setCreateConfigExpectations(args, 1);
        OptionSetter setter = new OptionSetter(mCommandOptions);
        // If invocation-data are added and we are in a versioned invocation, the data should not
        // be added again.
        setter.setOptionValue("invocation-data", "key", "value");
        mMockConfigDescriptor.setSandboxed(true);

        mMockInvocation.invoke(
                (IInvocationContext) EasyMock.anyObject(),
                (IConfiguration) EasyMock.anyObject(),
                (IRescheduler) EasyMock.anyObject(),
                (ITestInvocationListener) EasyMock.anyObject(),
                EasyMock.anyObject());
        EasyMock.expectLastCall().times(1);

        mMockConfiguration.validateOptions();
        IDevice mockIDevice = EasyMock.createMock(IDevice.class);
        ITestDevice mockDevice = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(mockDevice.getSerialNumber()).andStubReturn("serial");
        EasyMock.expect(mockDevice.getDeviceState()).andStubReturn(TestDeviceState.ONLINE);
        mockDevice.setRecoveryMode(EasyMock.eq(RecoveryMode.AVAILABLE));
        EasyMock.expect(mockDevice.getIDevice()).andStubReturn(mockIDevice);
        IScheduledInvocationListener mockListener =
                EasyMock.createMock(IScheduledInvocationListener.class);
        mockListener.invocationInitiated((InvocationContext) EasyMock.anyObject());
        mockListener.invocationComplete(
                (IInvocationContext) EasyMock.anyObject(), EasyMock.anyObject());
        EasyMock.expect(mockDevice.waitForDeviceShell(EasyMock.anyLong())).andReturn(true);

        mScheduler =
                new TestableCommandScheduler() {
                    @Override
                    Map<String, ITestDevice> allocateDevices(
                            IConfiguration config, IDeviceManager manager) {
                        Map<String, ITestDevice> allocated = new HashMap<>();
                        ((MockDeviceManager) manager).addDevice(mockDevice);
                        allocated.put("device", ((MockDeviceManager) manager).allocateDevice());
                        return allocated;
                    }
                };

        replayMocks(mockDevice, mockListener);
        mScheduler.start();
        mScheduler.execCommand(mockListener, args);
        mScheduler.shutdownOnEmpty();
        mScheduler.join(2 * 1000);
        verifyMocks(mockListener);

        // only attribute is invocation ID
        assertEquals(1, mContext.getAttributes().size());
        assertNotNull(mContext.getInvocationId());
    }
}
