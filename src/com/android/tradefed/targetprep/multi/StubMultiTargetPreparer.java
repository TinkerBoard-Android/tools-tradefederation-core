/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tradefed.targetprep.multi;

import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.TargetSetupError;

/** Placeholder empty implementation of a {@link IMultiTargetPreparer}. */
public class StubMultiTargetPreparer extends BaseMultiTargetPreparer {

    /** {@inheritDoc} */
    @Override
    public void setUp(TestInformation testInfo) throws TargetSetupError {
        CLog.d("skipping target prepare step");
    }
}
