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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
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
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Unit tests for {@link IncrementalDeqpPreparer}. */
@RunWith(JUnit4.class)
public class IncrementalDeqpPreparerTest {
    private IncrementalDeqpPreparer mPreparer;
    private ITestDevice mMockDevice;
    private IRunUtil mMockRuntil;

    @Before
    public void setUp() throws Exception {
        mMockDevice = mock(ITestDevice.class);
        mMockRuntil = mock(IRunUtil.class);
        mPreparer =
                new IncrementalDeqpPreparer() {
                    @Override
                    IRunUtil createRunUtil() {
                        return mMockRuntil;
                    }
                };
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Test
    public void testRunIncrementalDeqp() throws Exception {
        File testsDir = FileUtil.createTempDir("tests");
        File llvmStrip = new File(testsDir, "llvm-strip");
        FileUtil.writeToFile(
                new ByteArrayInputStream("stub llvm strip".getBytes(StandardCharsets.UTF_8)),
                llvmStrip);

        File resultDir = FileUtil.createTempDir("result");
        InputStream deqpDependencyStream =
                getClass().getResourceAsStream("/testdata/deqp_dependency_file.so");
        File deqpDependencyFile = FileUtil.createTempFile("deqp_dependency_file", ".so");
        File strippedFile =
                new File(
                        deqpDependencyFile.getParent(), "stripped_" + deqpDependencyFile.getName());
        FileUtil.writeToFile(deqpDependencyStream, strippedFile);

        try {
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

                        @Override
                        public File getTestsDir() {
                            return testsDir;
                        }
                    };
            InputStream perfDumpStream = getClass().getResourceAsStream("/testdata/perf-dump.txt");
            File dumpFile = FileUtil.createTempFile("parseDump", "perf-dump.txt");
            FileUtil.writeToFile(perfDumpStream, dumpFile);
            when(mMockDevice.pullFile(endsWith("-perf-dump.txt")))
                    .thenReturn(dumpFile, null, null, null);
            when(mMockDevice.pullFile(endsWith(".so"))).thenReturn(deqpDependencyFile);
            when(mMockRuntil.runTimedCmd(anyLong(), any(String.class)))
                    .thenReturn(new CommandResult(CommandStatus.SUCCESS));

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
            FileUtil.recursiveDelete(testsDir);
            FileUtil.deleteFile(deqpDependencyFile);
            FileUtil.deleteFile(strippedFile);
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
    public void testRunIncrementalDeqp_emptyDependencies() throws Exception {
        File resultDir = FileUtil.createTempDir("result");
        try {
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
            File dumpFile = FileUtil.createTempFile("parseDump", "perf-dump.txt");
            when(mMockDevice.pullFile(endsWith("-perf-dump.txt")))
                    .thenReturn(dumpFile, null, null, null);

            assertThrows(
                    TargetSetupError.class,
                    () ->
                            mPreparer.runIncrementalDeqp(
                                    mMockContext,
                                    mMockDevice,
                                    mMockBuildHelper,
                                    IncrementalDeqpPreparer.RunMode.LIGHTWEIGHT_RUN));
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
            assertEquals(dependency, expect);
        } finally {
            FileUtil.deleteFile(dumpFile);
        }
    }

    @Test
    public void testGetFileHash()
            throws IOException, DeviceNotAvailableException, TargetSetupError {
        File testsDir = FileUtil.createTempDir("tests");
        File llvmStrip = new File(testsDir, "llvm-strip");
        FileUtil.writeToFile(
                new ByteArrayInputStream("stub llvm strip".getBytes(StandardCharsets.UTF_8)),
                llvmStrip);

        Set<String> deviceFileSet =
                new HashSet<>(
                        Arrays.asList(
                                "/system/deqp_dependency_file_a.so",
                                "/vendor/deqp_dependency_file_b.so",
                                "/vendor/deqp_dependency_file_c.so"));

        List<File> tempFiles = new ArrayList<File>();
        for (String deviceFile : deviceFileSet) {
            File pulledFile = FileUtil.createTempFile("deqp_dependency_file", ".so");
            tempFiles.add(pulledFile);
            File strippedFile =
                    new File(pulledFile.getParent(), "stripped_" + pulledFile.getName());
            tempFiles.add(strippedFile);
            InputStream deqpDependencyStream =
                    getClass().getResourceAsStream("/testdata/deqp_dependency_file.so");
            FileUtil.writeToFile(deqpDependencyStream, strippedFile);
            when(mMockDevice.pullFile(deviceFile)).thenReturn(pulledFile);
        }

        try {
            CompatibilityBuildHelper mMockBuildHelper =
                    new CompatibilityBuildHelper(new BuildInfo()) {
                        @Override
                        public File getTestsDir() {
                            return testsDir;
                        }
                    };
            when(mMockRuntil.runTimedCmd(anyLong(), any(String.class)))
                    .thenReturn(new CommandResult(CommandStatus.SUCCESS));
            Map<String, String> fileHashMap =
                    mPreparer.getFileHash(deviceFileSet, mMockDevice, mMockBuildHelper);

            assertEquals(fileHashMap.size(), 3);
            String md5 =
                    StreamUtil.calculateMd5(
                            new ByteArrayInputStream(
                                    "placeholder\nplaceholder\n".getBytes(StandardCharsets.UTF_8)));
            assertEquals(fileHashMap.get("/system/deqp_dependency_file_a.so"), md5);
            assertEquals(fileHashMap.get("/vendor/deqp_dependency_file_b.so"), md5);
            assertEquals(fileHashMap.get("/vendor/deqp_dependency_file_c.so"), md5);
        } finally {
            FileUtil.recursiveDelete(testsDir);
            for (File tempFile : tempFiles) {
                FileUtil.deleteFile(tempFile);
            }
        }
    }

    /**
     * Tests that if stripping the build id from a file fails, the hash of the original file is
     * used.
     */
    @Test
    public void testGetFileHash_stripFails_usesOriginalFileHash()
            throws IOException, DeviceNotAvailableException, TargetSetupError {
        File testsDir = FileUtil.createTempDir("tests");
        File llvmStrip = new File(testsDir, "llvm-strip");
        llvmStrip.createNewFile(); // The file just needs to exist for the path.

        List<File> pulledFiles = new ArrayList<>();
        try {
            // Setup: Create a dummy file to be "pulled" from the device.
            String fileContent = "This is the original file content that cannot be stripped.";
            String devicePath1 = "/system/lib/unstrippable1.so";
            String devicePath2 = "/vendor/lib/unstrippable2.so";
            String devicePath3 = "/other/lib/unstrippable3.so";
            Set<String> deviceFileSet =
                    new HashSet<>(Arrays.asList(devicePath1, devicePath2, devicePath3));
            Map<String, String> expectedMd5Map = new HashMap<>();

            for (String devicePath : deviceFileSet) {
                File pulledFile = FileUtil.createTempFile("unstrippable_dependency", ".so");
                FileUtil.writeToFile(fileContent, pulledFile);
                pulledFiles.add(pulledFile);
                expectedMd5Map.put(devicePath, FileUtil.calculateMd5(pulledFile));
                // Mock the device to return our temp file.
                when(mMockDevice.pullFile(devicePath)).thenReturn(pulledFile);
            }

            // Mock the build helper.
            CompatibilityBuildHelper buildHelper =
                    new CompatibilityBuildHelper(new BuildInfo()) {
                        @Override
                        public File getTestsDir() {
                            return testsDir;
                        }
                    };

            // Mock the runUtil to return a FAILED status, simulating llvm-strip failure.
            CommandResult failedResult = new CommandResult(CommandStatus.FAILED);
            failedResult.setStderr("llvm-strip failed for some reason");
            when(mMockRuntil.runTimedCmd(anyLong(), any())).thenReturn(failedResult);

            // Execute the method under test.
            Map<String, String> fileHashMap =
                    mPreparer.getFileHash(deviceFileSet, mMockDevice, buildHelper);

            // Assert: The hash of the original, unstripped file should be used.
            assertEquals(3, fileHashMap.size());
            for (String devicePath : deviceFileSet) {
                assertTrue(fileHashMap.containsKey(devicePath));
                assertEquals(expectedMd5Map.get(devicePath), fileHashMap.get(devicePath));
            }
            // The pulled files should be cleaned up by the preparer.
            for (File pulledFile : pulledFiles) {
                assertFalse(pulledFile.exists());
            }
        } finally {
            FileUtil.recursiveDelete(testsDir);
            pulledFiles.forEach(FileUtil::deleteFile); // Cleanup in case of test failure.
        }
    }
}
