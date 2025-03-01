/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Unit tests for {@link IncrementalDeqpPreparer}. */
@RunWith(JUnit4.class)
public class IncrementalDeqpPreparerTest {
    private IncrementalDeqpPreparer mPreparer;
    private ITestDevice mMockDevice;

    @Before
    public void setUp() throws Exception {
        mPreparer = new IncrementalDeqpPreparer();
        mMockDevice = mock(ITestDevice.class);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Test
    public void testRunIncrementalDeqp() throws Exception {
        File resultDir = FileUtil.createTempDir("result");
        InputStream deqpDependencyStream =
                getClass().getResourceAsStream("/testdata/deqp_dependency_file.so");
        File deqpDependencyFile = FileUtil.createTempFile("deqp_dependency_file", ".so");
        try {
            FileUtil.writeToFile(deqpDependencyStream, deqpDependencyFile);
            IBuildInfo mMockBuildInfo = new BuildInfo();
            IInvocationContext mMockContext = new InvocationContext();
            mMockContext.addDeviceBuildInfo("build", mMockBuildInfo);
            mMockContext.addAllocatedDevice("device", mMockDevice);
            File deviceInfoDir = new File(resultDir, "device-info-files");
            deviceInfoDir.mkdir();
            CompatibilityBuildHelper mMockBuildHelper =
                    new CompatibilityBuildHelper(mMockBuildInfo) {
                        @Override
                        public File getResultDir() {
                            return resultDir;
                        }
                    };
            InputStream perfDumpStream = getClass().getResourceAsStream("/testdata/perf-dump.txt");
            File dumpFile = FileUtil.createTempFile("parseDump", "perf-dump.txt");
            FileUtil.writeToFile(perfDumpStream, dumpFile);
            when(mMockDevice.pullFile(endsWith("-perf-dump.txt")))
                    .thenReturn(dumpFile, null, null, null);
            when(mMockDevice.pullFile(endsWith(".so"))).thenReturn(deqpDependencyFile);

            File incrementalDeqpReport =
                    new File(deviceInfoDir, IncrementalDeqpPreparer.INCREMENTAL_DEQP_REPORT_NAME);
            assertFalse(incrementalDeqpReport.exists());
            mPreparer.runIncrementalDeqp(
                    mMockContext,
                    mMockDevice,
                    mMockBuildHelper,
                    IncrementalDeqpPreparer.RunMode.LIGHTWEIGHT_RUN);
            assertTrue(
                    mMockBuildInfo
                            .getBuildAttributes()
                            .containsKey(IncrementalDeqpPreparer.INCREMENTAL_DEQP_ATTRIBUTE_NAME));
            assertTrue(incrementalDeqpReport.exists());
        } finally {
            FileUtil.recursiveDelete(resultDir);
            FileUtil.deleteFile(deqpDependencyFile);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Test
    public void testRunIncrementalDeqp_skipPreparerWhenReportExists() throws Exception {
        File resultDir = FileUtil.createTempDir("result");
        InputStream reportStream =
                getClass()
                        .getResourceAsStream("/testdata/IncrementalCtsDeviceInfo.deviceinfo.json");
        try {
            IBuildInfo mMockBuildInfo = new BuildInfo();
            IInvocationContext mMockContext = new InvocationContext();
            mMockContext.addDeviceBuildInfo("build", mMockBuildInfo);
            mMockContext.addAllocatedDevice("device", mMockDevice);
            File deviceInfoDir = new File(resultDir, "device-info-files");
            deviceInfoDir.mkdir();
            File report =
                    new File(deviceInfoDir, IncrementalDeqpPreparer.INCREMENTAL_DEQP_REPORT_NAME);
            report.createNewFile();
            FileUtil.writeToFile(reportStream, report);
            CompatibilityBuildHelper mMockBuildHelper =
                    new CompatibilityBuildHelper(mMockBuildInfo) {
                        @Override
                        public File getResultDir() {
                            return resultDir;
                        }
                    };

            mPreparer.runIncrementalDeqp(
                    mMockContext,
                    mMockDevice,
                    mMockBuildHelper,
                    IncrementalDeqpPreparer.RunMode.LIGHTWEIGHT_RUN);
            assertTrue(
                    mMockBuildInfo
                            .getBuildAttributes()
                            .containsKey(IncrementalDeqpPreparer.INCREMENTAL_DEQP_ATTRIBUTE_NAME));
        } finally {
            FileUtil.recursiveDelete(resultDir);
        }
    }

    @Test
    public void testParseDump() throws Exception {
        InputStream inputStream = getClass().getResourceAsStream("/testdata/perf-dump.txt");
        File dumpFile = FileUtil.createTempFile("parseDump", ".txt");
        try {
            FileUtil.writeToFile(inputStream, dumpFile);
            Set<String> dependency = mPreparer.parseDump(dumpFile);
            Set<String> expect = new HashSet<>();
            expect.add("/system/deqp_dependency_file_a.so");
            expect.add("/vendor/deqp_dependency_file_b.so");
            assertEquals(dependency, expect);
        } finally {
            FileUtil.deleteFile(dumpFile);
        }
    }

    @Test
    public void testGetFileHash()
            throws IOException, DeviceNotAvailableException, TargetSetupError {
        Set<String> fileSet =
                new HashSet<>(
                        Arrays.asList(
                                "/system/deqp_dependency_file_a.so",
                                "/vendor/deqp_dependency_file_b.so",
                                "/vendor/deqp_dependency_file_c.so"));
        InputStream deqpDependencyStream =
                getClass().getResourceAsStream("/testdata/deqp_dependency_file.so");
        File deqpDependencyFile = FileUtil.createTempFile("deqp_dependency_file", ".so");
        try {
            FileUtil.writeToFile(deqpDependencyStream, deqpDependencyFile);
            when(mMockDevice.pullFile(endsWith(".so"))).thenReturn(deqpDependencyFile);
            Map<String, String> fileHashMap = mPreparer.getFileHash(fileSet, mMockDevice);

            assertEquals(fileHashMap.size(), 3);
            String md5 =
                    StreamUtil.calculateMd5(
                            new ByteArrayInputStream(
                                    "placeholder\nplaceholder\n".getBytes(StandardCharsets.UTF_8)));
            assertEquals(fileHashMap.get("/system/deqp_dependency_file_a.so"), md5);
            assertEquals(fileHashMap.get("/vendor/deqp_dependency_file_b.so"), md5);
            assertEquals(fileHashMap.get("/vendor/deqp_dependency_file_c.so"), md5);
        } finally {
            FileUtil.deleteFile(deqpDependencyFile);
        }
    }
}
