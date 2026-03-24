/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.compatibility.common.tradefed.targetprep;

import com.android.compatibility.common.util.CsrUtil;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;

import java.util.Base64;
import java.util.Map;

/** Collects data from devices to validate report integrity. */
public class ReportIntegrityCollector implements ITargetPreparer {
    private static final String VB_META_DIGEST = "ro.boot.vbmeta.digest";

    @Option(name = "disable", description = "disables the collector")
    private boolean mDisable = false;

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        IBuildInfo buildInfo = testInfo.getBuildInfo();
        // Ensure device is available before collecting.
        device.waitForDeviceAvailable();

        // Collect VBMeta data.
        buildInfo.addBuildAttribute(
                "cts:build_vb_meta_digest", nullToEmpty(device.getProperty(VB_META_DIGEST)));

        // Collect csr values.
        try {
            Map<String, byte[]> instanceNameToCsrMap = CsrUtil.getInstanceNameToCsr(device);
            for (Map.Entry<String, byte[]> instanceNameToCsr : instanceNameToCsrMap.entrySet()) {
                String instanceName = nullToEmpty(instanceNameToCsr.getKey());
                String csr = Base64.getEncoder().encodeToString(instanceNameToCsr.getValue());
                buildInfo.addBuildAttribute("cts:csr_" + instanceName, csr);
            }
        } catch (Exception e) {
            CLog.e("Fail to collect csr values from %s, error: %s", device.getSerialNumber(), e);
        }
    }

    @Override
    public final boolean isDisabled() {
        return mDisable;
    }

    @Override
    public final void setDisable(boolean isDisabled) {
        mDisable = isDisabled;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
