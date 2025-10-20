/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.compatibility.common.util;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.INativeDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.SearchArtifactUtil;

import perfetto.protos.File.TraceSummary;
import perfetto.protos.V2Metric.TraceMetricV2Bundle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility class for tests which need to run perfetto and analyze the results to determine
 * pass/failure.
 *
 * <p>This utility requires the following:
 *
 * <ul>
 *   <li>A textpb file with a perfetto config. Depending on usage, this config may need to have a
 *       timeout ("duration_ms") set in it.
 *   <li>A v2 metrics spec textpb file that works with trace_processor_shell.
 * </ul>
 *
 * <p>Given those (and some timeouts and the device), this utility will run perfetto and parse the
 * trace file, returning the trace summary per the given spec.
 *
 * <p>This class also provides a utility method for parsing name/value pairs out of the trace
 * summary for basic v2 metric specs.
 *
 * <p>This class supports both blocking and asynchronous usage.
 *
 * <p>In the blocking case, traceAndAnalyze() is used, which blocks until perfetto ends. This
 * requires the perfetto config to have a timeout set in it.
 *
 * <p>In the asynchronous case, traceAndAnalyzeAsync() is used. That call returns a Future, which
 * can be blocked on as appropriate. It is assumed that the perfetto config will have a timeout set
 * in it, or that the caller will call stopPerfetto() from their code.
 *
 * <p>With either usage, if the given traceDurationMs (plus an internal buffer time) is exceeded
 * from the call to traceAndAnalyze()/traceAndAnalyzeAsync(), the perfetto trace will be killed.
 */
public class TraceAndAnalyze {
    private File mPerfettoConfig;
    private File mTraceProcessorSpec;
    private long mPerfettoTimeoutMs;
    private long mTraceProcessorTimeoutMs;
    private INativeDevice mDevice;
    private int mPerfettoPid = -1;

    // We support multiple instances of this class existing at once.  We use
    // this number to make sure our filenames on the device are unique.
    private String mInstanceNumber;
    private static AtomicInteger instanceCounter = new AtomicInteger(0);

    // Note that since Android 10, section 2.2.6 of the CDD requires devices to
    // have perfetto in this location.
    private static final String PERFETTO_CMD = "/system/bin/perfetto";

    // This binary must be included as an artifact in the build.
    private static final String TRACE_PROCESSOR_FILENAME = "trace_processor_shell";

    // The user tells us how long their perfetto trace takes.  But the entire
    // perfetto command needs time beyond that to save out the trace and
    // such.  We provide this additional buffer beyond the trace duration for
    // our adb command.
    private static final long PERFETTO_TIMEOUT_BUFFER_MS = TimeUnit.SECONDS.toMillis(30);

    // The default amount of time we allow for processing the trace file.  This
    // can be increased with setTraceProcessorTimeout(), but that shouldn't
    // generally be needed.
    private static final long DEFAULT_TRACE_PROCESSOR_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);

    /**
     * There are some cases in which this utility's internal logic runs into a failure which
     * prevents us from generating results.
     *
     * <p>This should generally be an assertion failure for the test using this logic, but we leave
     * it to tests to determine how to handle this.
     *
     * <p>Note that we don't throw this exception when DeviceNotAvailableException is appropriate.
     */
    public class InternalException extends Exception {
        private int mFailureType;

        /**
         * A possible failure type.
         *
         * <p>We were unable to extract the contents of a build-time artifact from our executable at
         * runtime. This most likely points to an issue in the build setup, where host_common_data
         * wasn't properly included. Since the standard java_defaults for this includes that, it
         * suggests this specific test didn't use the standard setup for including this logic.
         */
        public static final int BUILD_ISSUE = 1;

        /**
         * A possible failure type.
         *
         * <p>We tried to execute a command on the device ("perfetto"), and while we were able to
         * access the device to issue the command, the command itself failed.
         */
        public static final int DEVICE_COMMAND_FAILURE = 2;

        /**
         * A possible failure type.
         *
         * <p>We tried to execute a command on the host ("trace_processor_shell") which failed or
         * gave unexpected output.
         */
        public static final int HOST_COMMAND_FAILURE = 3;

        InternalException(String message, int failureType) {
            super(message);
            mFailureType = failureType;
        }

        /**
         * This should be one of the constants defined above. See their documentation for more
         * details.
         */
        public int getFailureType() {
            return mFailureType;
        }

        public String getFailureTypeAsString() {
            switch (mFailureType) {
                case BUILD_ISSUE:
                    return "BUILD_ISSUE";
                case DEVICE_COMMAND_FAILURE:
                    return "DEVICE_COMMAND_FAILURE";
                case HOST_COMMAND_FAILURE:
                    return "HOST_COMMAND_FAILURE";
            }
            return "UNKNOWN FAILURE TYPE (" + mFailureType + ")";
        }

        @Override
        public String getLocalizedMessage() {
            return getFailureTypeAsString() + ": " + super.getLocalizedMessage();
        }
    }

    /**
     * Create a new instance.
     *
     * <p>Note this has the side effect of loading the perfetto config onto the device. We do this
     * during construction time to reduce (by a very small amount) lead time for starting the trace.
     *
     * @param perfettoConfig A textpb file containing a valid perfetto config.
     * @param traceProcessorSpec A textpb file containing a v2 metric spec.
     * @param traceDurationMs The maximum number of milliseconds we expect our trace to run, either
     *     due to "duration_ms" in the perfettoConfig or due to other logic that will call
     *     stopPerfetto().
     * @param device A handle to the device the test is running on.
     * @throws DeviceNotAvailableException if we're not able to access the given device.
     */
    public TraceAndAnalyze(
            File perfettoConfig,
            File traceProcessorSpec,
            long traceDurationMs,
            INativeDevice device)
            throws DeviceNotAvailableException {
        checkFileArgument(perfettoConfig, "perfettoConfig");
        checkFileArgument(traceProcessorSpec, "traceProcessorSpec");
        if (traceDurationMs <= 0) {
            throw new IllegalArgumentException("traceDurationMs argument must be positive");
        }
        if (device == null) {
            throw new IllegalArgumentException("device argument cannot be null");
        }

        mPerfettoConfig = perfettoConfig;
        mTraceProcessorSpec = traceProcessorSpec;
        mPerfettoTimeoutMs = traceDurationMs + PERFETTO_TIMEOUT_BUFFER_MS;
        mTraceProcessorTimeoutMs = DEFAULT_TRACE_PROCESSOR_TIMEOUT_MS;
        mDevice = device;

        mInstanceNumber = String.valueOf(instanceCounter.incrementAndGet());

        // We set this up now, so the traceAndAnalyze methods will start tracing
        // (slightly) more quickly.
        setupFiles();
    }

    /**
     * By default, we allow a substantial timeout (see `DEFAULT_TRACE_PROCESSOR_TIMEOUT_MS`) for the
     * trace processing command. This command is run host-side, so it doesn't vary for testing low
     * end devices.
     *
     * <p>But if the trace is particularly large and/or requires extensive processing, this method
     * can be used (before calling a `traceAndAnalyze` method) to give the command more time to run.
     *
     * @param timeoutMs A different timeout to use, in milliseconds.
     */
    public void setTraceProcessorTimeout(long timeoutMs) {
        mTraceProcessorTimeoutMs = timeoutMs;
    }

    /**
     * Perform a trace, parse the results, and return a TraceSummary corresponding to what the spec
     * asked for.
     *
     * <p>This method blocks for the duration of the trace (and analysis).
     *
     * <p>Note the returned TraceSummary can be passed to a function like parseSimpleIdValuePairs()
     * to get more easily processed data.
     *
     * @return The TraceSummary of the analysis
     * @throws DeviceNotAvailableException if we cannot access the given device.
     * @throws InternalException if an unrecoverable issue occurred within this logic.
     */
    public TraceSummary traceAndAnalyze() throws DeviceNotAvailableException, InternalException {
        try {
            launchPerfetto();
            waitForPerfetto();
            return analyzeTrace();
        } finally {
            cleanupFiles();
        }
    }

    /**
     * Start the trace and analysis process, returning a Future,
     *
     * <p>The Future will do one of the following:
     *
     * <ul>
     *   <li>Return a TraceSummary corresponding to what the spec asked for.
     *   <li>Throw a DeviceNotAvailableException
     *   <li>Throw an InternalException
     * </ul>
     *
     * <p>Note the returned TraceSummary can be passed to a function like parseSimpleIdValuePairs()
     * to get more easily processed data.
     *
     * @param executor An ExecutorService which the caller has already setup. This method only uses
     *     one thread.
     * @return A Future as described in this functions documentation.
     */
    public Future<TraceSummary> traceAndAnalyzeAsync(ExecutorService executor) {
        class MyCallable implements Callable<TraceSummary> {
            public final TraceAndAnalyze ta;

            MyCallable(TraceAndAnalyze ta) {
                this.ta = ta;
            }

            @Override
            public TraceSummary call() throws Exception {
                return ta.traceAndAnalyze();
            }
        }

        MyCallable callable = new MyCallable(this);
        return executor.submit(callable);
    }

    /**
     * Stop the perfetto trace.
     *
     * <p>This can be useful when using traceAndAnalyzeAsync() if, instead of relying on
     * "duration_ms" in the perfetto config, you want to use other logic within the test to stop
     * perfetto at the appropriate time. For example, if you're tracing and analyzing app start up
     * time, after your test logic detects the app has started, you would call this.
     *
     * @throws DeviceNotAvailableException if we cannot access the device.
     */
    public void stopPerfetto() throws DeviceNotAvailableException {
        // We don't check the result here.  Due to race conditions, perfetto
        // could have already stopped.  So we don't worry if the mPerfettoPid
        // is still running or not.
        mDevice.executeShellV2Command("kill -TERM " + mPerfettoPid);
    }

    private File getTraceProcessorHostFile() throws InternalException {
        File file = SearchArtifactUtil.searchFile(TRACE_PROCESSOR_FILENAME, false);
        if (file == null || !file.exists()) {
            final String findError =
                    "Failed to find " + TRACE_PROCESSOR_FILENAME + " in test artifacts";
            throw new InternalException(findError, InternalException.BUILD_ISSUE);
        }

        return file;
    }

    private void launchPerfetto() throws DeviceNotAvailableException, InternalException {
        // To keep the logic easier to maintain, we launch perfetto in the
        // background whether we're using the blocking or asynchronous
        // invocation.
        String cmd =
                PERFETTO_CMD
                        + " --background "
                        + " --config "
                        + getPerfettoConfigDevicePath()
                        + " --txt"
                        + " --out "
                        + getPerfettoTraceDevicePath();

        CLog.d("Starting perfetto trace");
        CommandResult result = mDevice.executeShellV2Command(cmd);
        if (result.getStatus() != CommandStatus.SUCCESS) {
            String errMsg = "Failed to launch perfetto\n" + result.toString();
            throw new InternalException(errMsg, InternalException.DEVICE_COMMAND_FAILURE);
        }

        // By using the --background option, this command returns immediately,
        // giving the process ID (pid) of perfetto running on the device.
        // Note the stdout includes a newline character, so we need to trim().
        String pidStr = result.getStdout().trim();
        try {
            mPerfettoPid = Integer.valueOf(pidStr);
        } catch (NumberFormatException e) {
            String errMsg = "Could not parse perfetto output as a process id: '" + pidStr + "'";
            throw new InternalException(errMsg, InternalException.DEVICE_COMMAND_FAILURE);
        }
    }

    private void waitForPerfetto() throws DeviceNotAvailableException {
        // This loops until the perfetto process is gone, sleeping for 1 second
        // between each time we check.
        String checkAlive = "ps -p " + mPerfettoPid + " ";
        String discardAllOutput = "2> /dev/null > /dev/null";
        String loopCondition = checkAlive + discardAllOutput;
        String innerLoop = "sleep 1;";

        String cmd = "while " + loopCondition + "; do " + innerLoop + "done";
        CommandResult result =
                mDevice.executeShellV2Command(cmd, mPerfettoTimeoutMs, TimeUnit.MILLISECONDS);
        if (result.getStatus() == CommandStatus.TIMED_OUT) {
            CLog.w("Perfetto ran longer than " + mPerfettoTimeoutMs + "ms; killing it.");
            stopPerfetto();
        }
    }

    private String[] getTraceProcessorArgs(
            String hostTraceFilePath, String traceProcessorBinaryPath)
            throws DeviceNotAvailableException {
        List<String> argsList = new ArrayList<String>();

        // Trace Processing binary command itself
        argsList.add(traceProcessorBinaryPath);

        // We need this to use any other "summary" options.
        argsList.add("--summary");

        // We'll grab all of the metrics we've defined in our spec file
        argsList.add("--summary-metrics-v2");
        argsList.add("all");

        // And here we specify that spec file.
        argsList.add("--summary-spec");
        argsList.add(mTraceProcessorSpec.getAbsolutePath());

        // We want our output in binary so we can parse it.
        argsList.add("--summary-format");
        argsList.add("binary");

        // And finally our trace file itself.
        argsList.add(hostTraceFilePath);

        return argsList.toArray(new String[argsList.size()]);
    }

    /**
     * Parse the given TraceSummary into id (String) / value (Double) pairs.
     *
     * <p>A TraceSummary can be quite complex. See the TraceMetricV2Bundle proto definition to see
     * the wide variety of dimensions and types and organizations of this data and metadata that is
     * supported.
     *
     * <p>However, some tests use a relatively simple setup, and just have values (as Doubles)
     * associated with a String id. This method is designed to handle this common case.
     *
     * <p>This method assumes that each bundle has a single row of values. It assumes there are the
     * same number of specs as there are values in the row, and they correspond to each other.
     *
     * <p>Note this method does not enforce that the TraceSummary is in this format. It will logcat
     * some instances where this assumption is broken, but otherwise will try to parse as much info
     * as it can.
     *
     * @param summary A TraceSummary to parse
     * @return A Map pairing together ids (String) with their value (Double).
     */
    public static Map<String, Double> parseSimpleIdValuePairs(TraceSummary traceSummary) {
        Map<String, Double> idToValue = new HashMap<>();
        if (traceSummary == null) {
            return idToValue;
        }
        for (TraceMetricV2Bundle bundle : traceSummary.getMetricBundlesList()) {
            int specCount = bundle.getSpecsCount();
            int rowCount = bundle.getRowCount();

            if ((specCount <= 0) || (rowCount <= 0)) {
                CLog.w(
                        "Skipping bundle with specCount("
                                + specCount
                                + ") and rowCount("
                                + rowCount
                                + ")");
                continue;
            }

            if (rowCount > 1) {
                CLog.w("Only parsing first row in bundle with rowCount " + rowCount);
            }

            List<TraceMetricV2Bundle.Row.Value> values = bundle.getRow(0).getValuesList();
            int valueCount = values.size();

            int pairsCount = Math.min(specCount, valueCount);

            if (specCount != valueCount) {
                CLog.w(
                        "Bundle with mismatched specCount("
                                + specCount
                                + ") and valueCount("
                                + valueCount
                                + "); only parsing first "
                                + pairsCount);
            }

            for (int i = 0; i < pairsCount; i++) {
                String id = bundle.getSpecs(i).getId();
                if (id == null) {
                    CLog.w("Got null id in spec at index " + i + "; skipping");
                    continue;
                }

                double value = values.get(i).getDoubleValue();
                idToValue.put(id, value);
            }
        }
        return idToValue;
    }

    private TraceSummary analyzeTrace() throws DeviceNotAvailableException, InternalException {
        // Pull the trace file from the device to a host file for analysis.
        String deviceTracePath = getPerfettoTraceDevicePath();
        File hostTraceFile = mDevice.pullFile(deviceTracePath);
        if (hostTraceFile == null) {
            String errMsg = "Unable to pull trace file " + deviceTracePath + " from device";
            // We'll blame perfetto for not creating the trace file.
            throw new InternalException(errMsg, InternalException.DEVICE_COMMAND_FAILURE);
        }
        hostTraceFile.deleteOnExit(); // We just need this temporarily.

        File traceProcessorBinary = getTraceProcessorHostFile();
        FileUtil.chmodGroupRWX(traceProcessorBinary);

        String[] argsArray =
                getTraceProcessorArgs(
                        hostTraceFile.getAbsolutePath(), traceProcessorBinary.getAbsolutePath());

        IRunUtil runner = RunUtil.getDefault();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        CommandResult result =
                runner.runTimedCmdWithOutputMonitor(
                        mTraceProcessorTimeoutMs,
                        mTraceProcessorTimeoutMs,
                        stdout,
                        null,
                        argsArray);
        if (result.getStatus() != CommandStatus.SUCCESS) {
            String errMsg = "Failed to process raw trace file: " + result.getStderr();
            throw new InternalException(errMsg, InternalException.HOST_COMMAND_FAILURE);
        }

        ByteArrayInputStream processorOutput = new ByteArrayInputStream(stdout.toByteArray());
        TraceSummary traceSummary;
        try {
            traceSummary = TraceSummary.parseFrom(processorOutput);
        } catch (Exception e) {
            String errMsg =
                    "Failed to parse the output from "
                            + TRACE_PROCESSOR_FILENAME
                            + "\n"
                            + e.getMessage();
            throw new InternalException(errMsg, InternalException.HOST_COMMAND_FAILURE);
        }
        return traceSummary;
    }

    private void cleanupFiles() throws DeviceNotAvailableException {
        // These operations won't throw exceptions if the files don't exist.
        // So this is fine to run after a failed attempt, or before a new
        // run to make sure there's no cruft around.
        mDevice.deleteFile(getPerfettoConfigDevicePath());
        mDevice.deleteFile(getPerfettoTraceDevicePath());
    }

    private void setupFiles() throws DeviceNotAvailableException {
        cleanupFiles();
        mDevice.pushFile(mPerfettoConfig, getPerfettoConfigDevicePath());
    }

    private String getPerfettoConfigDevicePath() {
        // Standard directory for configs; we include our library name to avoid
        // general conflicts, and then add our instance number to avoid
        // conflicting with other instances.
        final String perfettoConfigDevicePathBase =
                "/data/misc/perfetto-configs/compatibility-host-util.config.";

        return perfettoConfigDevicePathBase + mInstanceNumber + ".pbtx";
    }

    private String getPerfettoTraceDevicePath() {
        // Standard directory for traces; we include our library name to avoid
        // general conflicts, and then add our instance number to avoid
        // conflicting with other instances.
        final String perfettoTraceDevicePathBase =
                "/data/misc/perfetto-traces/compatibility-host-util.";

        return perfettoTraceDevicePathBase + mInstanceNumber + ".trace";
    }

    private void checkFileArgument(File file, String argumentName) {
        if (file == null) {
            throw new IllegalArgumentException(argumentName + " cannot be null");
        }
        if (!file.exists()) {
            throw new IllegalArgumentException("Given " + argumentName + " does not exist");
        }
    }
}
