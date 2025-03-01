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

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.compatibility.common.util.DeviceInfo;
import com.android.compatibility.common.util.HostInfoStore;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.TestErrorIdentifier;
import com.android.tradefed.targetprep.BaseTargetPreparer;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Collects the dEQP dependencies and compares the builds. */
@OptionClass(alias = "incremental-deqp-preparer")
public class IncrementalDeqpPreparer extends BaseTargetPreparer {
    @Option(name = "run-mode", description = "The run mode for incremental dEQP.")
    private RunMode mRunMode = RunMode.LIGHTWEIGHT_RUN;

    @Option(
            name = "fallback-strategy",
            description =
                    "The fallback strategy to apply if the incremental dEQP qualification testing "
                            + "for the builds fails.")
    private FallbackStrategy mFallbackStrategy = FallbackStrategy.ABORT_IF_ANY_EXCEPTION;

    public enum RunMode {
        // Collects the dependencies information for the build via the full dEQP tests.
        FULL_RUN,
        // Collects the dependencies information for the build via the representative dEQP tests.
        LIGHTWEIGHT_RUN
    }

    private enum FallbackStrategy {
        // Continues to run full dEQP tests no matter an exception is thrown or not.
        RUN_FULL_DEQP,
        // Aborts if an exception is thrown in the preparer. Otherwise, runs full dEQP tests due to
        // dependency modifications.
        ABORT_IF_ANY_EXCEPTION
    }

    private static final String MODULE_NAME = "CtsDeqpTestCases";
    private static final String DEVICE_DEQP_DIR = "/data/local/tmp";
    private static final List<String> BASELINE_DEQP_TEST_LIST =
            Arrays.asList(
                    "gles2-incremental-deqp-baseline",
                    "gles3-incremental-deqp-baseline",
                    "gles31-incremental-deqp-baseline",
                    "vk-incremental-deqp-baseline");
    private static final List<String> REPRESENTATIVE_DEQP_TEST_LIST =
            Arrays.asList("vk-incremental-deqp", "gles3-incremental-deqp");
    private static final List<String> DEQP_BINARY_LIST =
            Arrays.asList("deqp-binary32", "deqp-binary64");
    private static final String DEQP_CASE_LIST_FILE_EXTENSION = ".txt";
    private static final String PERF_FILE_EXTENSION = ".data";
    private static final String LOG_FILE_EXTENSION = ".qpa";
    private static final String RUN_MODE_ATTRIBUTE = "run_mode";
    private static final String MODULE_ATTRIBUTE = "module";
    private static final String MODULE_NAME_ATTRIBUTE = "module_name";
    private static final String DEPENDENCY_DETAILS_ATTRIBUTE = "deps_details";
    private static final String DEPENDENCY_NAME_ATTRIBUTE = "dep_name";
    private static final String DEPENDENCY_FILE_HASH_ATTRIBUTE = "file_hash";

    private static final Pattern EXCLUDE_DEQP_PATTERN =
            Pattern.compile("(^/data/|^/apex/|^\\[vdso" + "\\]|^/dmabuf|^/kgsl-3d0|^/mali csf)");
    public static final String INCREMENTAL_DEQP_ATTRIBUTE_NAME = "incremental-deqp";
    public static final String INCREMENTAL_DEQP_REPORT_NAME =
            "IncrementalCtsDeviceInfo.deviceinfo.json";

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        try {
            ITestDevice device = testInfo.getDevice();
            CompatibilityBuildHelper buildHelper =
                    new CompatibilityBuildHelper(testInfo.getBuildInfo());
            IInvocationContext context = testInfo.getContext();
            runIncrementalDeqp(context, device, buildHelper, mRunMode);
        } catch (Exception e) {
            if (mFallbackStrategy == FallbackStrategy.ABORT_IF_ANY_EXCEPTION) {
                // Rethrows the exception to abort the task.
                throw e;
            }
            // Ignores the exception and continues to run full dEQP tests.
        }
    }

    /**
     * Collects dEQP dependencies and generate an incremental cts report with more details.
     *
     * <p>Synchronize this method so that multiple shards won't run it multiple times.
     */
    protected void runIncrementalDeqp(
            IInvocationContext context,
            ITestDevice device,
            CompatibilityBuildHelper buildHelper,
            RunMode runMode)
            throws TargetSetupError, DeviceNotAvailableException {
        // Make sure synchronization is on the class not the object.
        synchronized (IncrementalDeqpPreparer.class) {
            File jsonFile;
            try {
                File deviceInfoDir =
                        new File(buildHelper.getResultDir(), DeviceInfo.RESULT_DIR_NAME);
                jsonFile = new File(deviceInfoDir, INCREMENTAL_DEQP_REPORT_NAME);
                if (jsonFile.exists()) {
                    CLog.i("Another shard has already checked dEQP dependencies.");
                    // Add an attribute to the shard's build info.
                    addBuildAttribute(context, INCREMENTAL_DEQP_ATTRIBUTE_NAME);
                    return;
                }
            } catch (FileNotFoundException e) {
                throw new TargetSetupError(
                        "Fail to read invocation result directory.",
                        device.getDeviceDescriptor(),
                        TestErrorIdentifier.TEST_ABORTED);
            }

            List<String> deqpTestList =
                    RunMode.FULL_RUN.equals(mRunMode)
                            ? BASELINE_DEQP_TEST_LIST
                            : REPRESENTATIVE_DEQP_TEST_LIST;
            Set<String> dependencies = getDeqpDependencies(device, deqpTestList);

            // Identify and write dependencies to device info report.
            try (HostInfoStore store = new HostInfoStore(jsonFile)) {
                store.open();
                store.addResult(RUN_MODE_ATTRIBUTE, runMode.name());
                store.startArray(MODULE_ATTRIBUTE);
                store.startGroup(); // Module
                store.addResult(MODULE_NAME_ATTRIBUTE, MODULE_NAME);
                store.startArray(DEPENDENCY_DETAILS_ATTRIBUTE);
                Map<String, String> currentBuildHashMap = getFileHash(dependencies, device);
                for (String dependency : dependencies) {
                    store.startGroup();
                    store.addResult(DEPENDENCY_NAME_ATTRIBUTE, dependency);
                    store.addResult(
                            DEPENDENCY_FILE_HASH_ATTRIBUTE, currentBuildHashMap.get(dependency));
                    store.endGroup();
                }
                store.endArray(); // dEQP details
                store.endGroup(); // Module
                store.endArray();
                addBuildAttribute(context, INCREMENTAL_DEQP_ATTRIBUTE_NAME);
            } catch (IOException e) {
                throw new TargetSetupError(
                        "Failed to collect dependencies",
                        e,
                        device.getDeviceDescriptor(),
                        TestErrorIdentifier.TEST_ABORTED);
            } catch (Exception e) {
                throw new TargetSetupError(
                        "Failed to write incremental dEQP report",
                        e,
                        device.getDeviceDescriptor(),
                        TestErrorIdentifier.TEST_ABORTED);
            } finally {
                if (jsonFile.exists() && jsonFile.length() == 0) {
                    FileUtil.deleteFile(jsonFile);
                }
            }
        }
    }

    /** Gets the filename of dEQP dependencies in build. */
    private Set<String> getDeqpDependencies(ITestDevice device, List<String> testList)
            throws TargetSetupError, DeviceNotAvailableException {
        Set<String> result = new HashSet<>();

        for (String test : testList) {
            for (String binaryName : DEQP_BINARY_LIST) {
                String fileNamePrefix = test + "-" + binaryName;
                String perfFile = DEVICE_DEQP_DIR + "/" + fileNamePrefix + PERF_FILE_EXTENSION;
                String binaryFile = DEVICE_DEQP_DIR + "/" + binaryName;
                String testFile = DEVICE_DEQP_DIR + "/" + test + DEQP_CASE_LIST_FILE_EXTENSION;
                String logFile = DEVICE_DEQP_DIR + "/" + fileNamePrefix + LOG_FILE_EXTENSION;

                String command =
                        String.format(
                                "cd %s && simpleperf record -o %s %s --deqp-caselist-file=%s"
                                    + " --deqp-log-images=disable --deqp-log-shader-sources=disable"
                                    + " --deqp-log-filename=%s --deqp-surface-type=fbo"
                                    + " --deqp-surface-width=2048 --deqp-surface-height=2048",
                                DEVICE_DEQP_DIR, perfFile, binaryFile, testFile, logFile);
                device.executeShellCommand(command);

                String dumpFile = DEVICE_DEQP_DIR + "/" + fileNamePrefix + "-perf-dump.txt";
                String dumpCommand = String.format("simpleperf dump %s > %s", perfFile, dumpFile);
                device.executeShellCommand(dumpCommand);

                File localDumpFile = device.pullFile(dumpFile);
                try {
                    result.addAll(parseDump(localDumpFile));
                } finally {
                    if (localDumpFile != null) {
                        localDumpFile.delete();
                    }
                }
            }
        }

        return result;
    }

    /** Gets the hash value of the specified file's content from the device. */
    protected Map<String, String> getFileHash(Set<String> fileNames, ITestDevice device)
            throws DeviceNotAvailableException, TargetSetupError {
        Map<String, String> fileHashes = new HashMap<>();
        for (String file : fileNames) {
            File localFile = device.pullFile(file);
            if (localFile == null) {
                throw new TargetSetupError(
                        String.format("Fail to load file: %s from the device.", file),
                        TestErrorIdentifier.TEST_ABORTED);
            }
            String md5 = FileUtil.calculateMd5(localFile);
            fileHashes.put(file, md5);
        }
        return fileHashes;
    }

    /** Parses the dump file and gets list of dependencies. */
    protected Set<String> parseDump(File localDumpFile) throws TargetSetupError {
        boolean binaryExecuted = false;
        boolean correctMmap = false;
        Set<String> result = new HashSet<>();
        if (localDumpFile == null) {
            return result;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(localDumpFile));
            String line;
            while ((line = br.readLine()) != null) {
                if (!binaryExecuted) {
                    // dEQP binary has first been executed.
                    Pattern pattern = Pattern.compile(" comm .*deqp-binary");
                    if (pattern.matcher(line).find()) {
                        binaryExecuted = true;
                    }
                } else {
                    // New perf event
                    if (!line.startsWith(" ")) {
                        // Ignore mmap with misc 1, they are not related to deqp binary
                        correctMmap = line.startsWith("record mmap") && !line.contains("misc 1");
                    }

                    // We have reached the filename for a valid perf event, add to the dependency
                    // map if it isn't in the exclusion pattern
                    if (line.contains("filename") && correctMmap) {
                        String dependency = line.substring(line.indexOf("filename") + 9).trim();
                        if (!EXCLUDE_DEQP_PATTERN.matcher(dependency).find()) {
                            result.add(dependency);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new TargetSetupError(
                    String.format("Could not parse file: %s", localDumpFile.getAbsoluteFile()),
                    e,
                    TestErrorIdentifier.TEST_ABORTED);
        } finally {
            StreamUtil.close(br);
        }
        return result;
    }

    /** Adds a build attribute to all the {@link IBuildInfo} tracked for the invocation. */
    private static void addBuildAttribute(IInvocationContext context, String buildAttributeName) {
        for (IBuildInfo bi : context.getBuildInfos()) {
            bi.addBuildAttribute(buildAttributeName, "");
        }
    }
}
