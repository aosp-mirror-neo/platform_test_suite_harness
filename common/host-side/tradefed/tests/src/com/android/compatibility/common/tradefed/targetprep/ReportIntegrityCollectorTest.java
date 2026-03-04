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

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.TargetSetupError;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.HashMap;
import java.util.Map;

/** Unit tests for {@link ReportIntegrityCollector}. */
@RunWith(JUnit4.class)
public final class ReportIntegrityCollectorTest {
    private static final String BUILD_VB_META_DIGEST =
            "559c04afef75498eea9896bda43c2bf3f2f48dca4b77952a43be2a7026e2dbbf";

    @Mock private IBuildInfo mMockBuildInfo;
    @Mock private ITestDevice mMockTestDevice;
    private TestInformation mTestInfo;
    private Map<String, String> mProperties;

    @Before
    public void setUp() throws DeviceNotAvailableException {
        MockitoAnnotations.initMocks(this);

        Mockito.when(mMockTestDevice.getProperty(Mockito.anyString()))
                .then(
                        new Answer<String>() {
                            @Override
                            public String answer(InvocationOnMock invocation) {
                                String name = invocation.getArgument(0);
                                return mProperties.getOrDefault(name, null);
                            }
                        });

        mProperties = new HashMap<String, String>();
        IInvocationContext context = new InvocationContext();
        context.addDeviceBuildInfo("device", mMockBuildInfo);
        context.addAllocatedDevice("device", mMockTestDevice);
        mTestInfo = TestInformation.newBuilder().setInvocationContext(context).build();
    }

    @Test
    public void testVbMetaDigest()
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        mProperties.put("ro.boot.vbmeta.digest", BUILD_VB_META_DIGEST);
        new ReportIntegrityCollector().setUp(mTestInfo);

        Mockito.verify(mMockBuildInfo)
                .addBuildAttribute("cts:build_vb_meta_digest", BUILD_VB_META_DIGEST);
    }

    @Test
    public void testVbMetaDigest_empty()
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        new ReportIntegrityCollector().setUp(mTestInfo);

        Mockito.verify(mMockBuildInfo).addBuildAttribute("cts:build_vb_meta_digest", "");
    }
}
