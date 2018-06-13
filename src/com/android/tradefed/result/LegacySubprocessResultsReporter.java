/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.result;

import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.SubprocessEventHelper.BaseTestEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.FailedTestEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.InvocationFailedEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.InvocationStartedEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.TestEndedEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.TestRunEndedEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.TestRunFailedEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.TestRunStartedEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.TestStartedEventInfo;
import com.android.tradefed.util.SubprocessTestResultsParser;

import java.util.Map;

/**
 * A class for freezed subprocess results reporter which is compatible with M & N version of CTS.
 * Methods in this class were part of ITestInvocationListener in pre-O version of TF/CTS. Changes
 * are not supposed to be made on this class.
 */
public final class LegacySubprocessResultsReporter extends SubprocessResultsReporter {

    /* Legacy method based on M cts api, pls do not change*/
    public void testAssumptionFailure(TestIdentifier testId, String trace) {
        FailedTestEventInfo info =
                new FailedTestEventInfo(testId.getClassName(), testId.getTestName(), trace);
        printEvent(SubprocessTestResultsParser.StatusKeys.TEST_ASSUMPTION_FAILURE, info);
    }

    /* Legacy method based on M cts api, pls do not change*/
    public void testEnded(TestIdentifier testId, Map<String, String> metrics) {
        testEnded(testId, System.currentTimeMillis(), metrics);
    }

    /* Legacy method based on M cts api, pls do not change*/
    public void testEnded(TestIdentifier testId, long endTime, Map<String, String> metrics) {
        TestEndedEventInfo info =
                new TestEndedEventInfo(
                        testId.getClassName(), testId.getTestName(), endTime, metrics);
        printEvent(SubprocessTestResultsParser.StatusKeys.TEST_ENDED, info);
    }

    /* Legacy method based on M cts api, pls do not change*/
    public void testFailed(TestIdentifier testId, String reason) {
        FailedTestEventInfo info =
                new FailedTestEventInfo(testId.getClassName(), testId.getTestName(), reason);
        printEvent(SubprocessTestResultsParser.StatusKeys.TEST_FAILED, info);
    }

    /* Legacy method based on M cts api, pls do not change*/
    public void testIgnored(TestIdentifier testId) {
        BaseTestEventInfo info = new BaseTestEventInfo(testId.getClassName(), testId.getTestName());
        printEvent(SubprocessTestResultsParser.StatusKeys.TEST_IGNORED, info);
    }

    /* Legacy method based on M cts api, pls do not change*/
    public void testStarted(TestIdentifier testId) {
        testStarted(testId, System.currentTimeMillis());
    }

    /* Legacy method based on M cts api, pls do not change*/
    public void testStarted(TestIdentifier testId, long startTime) {
        TestStartedEventInfo info =
                new TestStartedEventInfo(testId.getClassName(), testId.getTestName(), startTime);
        printEvent(SubprocessTestResultsParser.StatusKeys.TEST_STARTED, info);
    }

    /* Legacy method based on M cts api, pls do not change*/
    public void invocationStarted(IBuildInfo buildInfo) {
        InvocationStartedEventInfo info =
                new InvocationStartedEventInfo(buildInfo.getTestTag(), System.currentTimeMillis());
        printEvent(SubprocessTestResultsParser.StatusKeys.INVOCATION_STARTED, info);
    }

    /* Legacy method based on M cts api, pls do not change*/
    @Override
    public void invocationFailed(Throwable cause) {
        InvocationFailedEventInfo info = new InvocationFailedEventInfo(cause);
        printEvent(SubprocessTestResultsParser.StatusKeys.INVOCATION_FAILED, info);
    }

    /* Legacy method based on M cts api, pls do not change*/
    @Override
    public void invocationEnded(long elapsedTime) {
        // ignore
    }

    /* Legacy method based on M cts api, pls do not change*/
    @Override
    public void testRunFailed(String reason) {
        TestRunFailedEventInfo info = new TestRunFailedEventInfo(reason);
        printEvent(SubprocessTestResultsParser.StatusKeys.TEST_RUN_FAILED, info);
    }

    /* Legacy method based on M cts api, pls do not change*/
    @Override
    public void testRunStarted(String runName, int testCount) {
        TestRunStartedEventInfo info = new TestRunStartedEventInfo(runName, testCount);
        printEvent(SubprocessTestResultsParser.StatusKeys.TEST_RUN_STARTED, info);
    }

    /* Legacy method based on M cts api, pls do not change*/
    @Override
    public void testRunEnded(long time, Map<String, String> runMetrics) {
        TestRunEndedEventInfo info = new TestRunEndedEventInfo(time, runMetrics);
        printEvent(SubprocessTestResultsParser.StatusKeys.TEST_RUN_ENDED, info);
    }

    /** A intentionally inop function to handle incompatibility problem in CTS 8.1 */
    @Override
    public void testModuleStarted(IInvocationContext moduleContext) {
        CLog.d("testModuleStarted is called but ignored intentionally");
    }

    /** A intentionally inop function to handle incompatibility problem in CTS 8.1 */
    @Override
    public void testModuleEnded() {
        CLog.d("testModuleEnded is called but ignored intentionally");
    }

}
