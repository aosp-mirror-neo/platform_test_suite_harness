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

package com.android.compatibility.common.util;

import static com.google.common.truth.Truth.assertThat;

import com.android.tradefed.device.DeviceRuntimeException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.util.CommandResult;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Utility class for collecting csr values. */
public class CsrUtil {
    private static final String PROP_AVF_REMOTE_ATTESTATION_ENABLED =
            "avf.remote_attestation.enabled";

    public static Map<String, byte[]> getInstanceNameToCsr(ITestDevice device) throws Exception {
        LinkedHashMap<String, byte[]> instanceNameToCsr = new LinkedHashMap<>();
        for (String name : getInstanceNames(device)) {
            // TODO(b/351113293): Remove this bypass once the AVF RKP HAL declaration is moved to
            // /vendor.
            if (name.equals("avf") && !supportAvfRemoteAttestation(device)) {
                CLog.d("Skip AVF instance as protected VM support required to run it is missing");
                continue;
            }
            byte[] csr = getCsr(device, name);
            instanceNameToCsr.putIfAbsent(name, csr);
        }
        return instanceNameToCsr;
    }

    private static String[] getInstanceNames(ITestDevice device) throws Exception {
        CommandResult result = device.executeShellV2Command("cmd remote_provisioning list");
        if (result.getExitCode() != 0) {
            throw new DeviceRuntimeException(
                    String.format("Failed to get instance names: %s", result.getStderr()),
                    DeviceErrorIdentifier.SHELL_COMMAND_ERROR);
        }
        return result.getStdout().lines().toArray(String[]::new);
    }

    private static byte[] getCsr(ITestDevice device, String name) throws Exception {
        byte[] challenge = new byte[16];
        SecureRandom.getInstanceStrong().nextBytes(challenge);
        String challengeBase64 = Base64.getEncoder().encodeToString(challenge);
        CommandResult result =
                device.executeShellV2Command(
                        "cmd remote_provisioning csr --challenge " + challengeBase64 + " " + name);
        if (result.getExitCode() != 0) {
            throw new DeviceRuntimeException(
                    String.format(
                            "Failed to get csr for instance name: %s due to exception : %s",
                            name, result.getStderr()),
                    DeviceErrorIdentifier.SHELL_COMMAND_ERROR);
        }
        // Confirm that the CSR contains the challenge from this request.
        String csrBase64 = result.getStdout().trim();
        byte[] csrBytes = Base64.getDecoder().decode(csrBase64);
        List<DataItem> csrItems = CborDecoder.decode(csrBytes);
        Array csr = (Array) csrItems.get(0);
        switch (csr.getDataItems().get(0).getMajorType()) {
            case ARRAY:
                checkChallengeV1(csr, challenge);
                break;
            case UNSIGNED_INTEGER:
                checkChallengeV2(csr, challenge);
                break;
            default:
                throw new AssertionError(
                        "unsupported CSR with first item major type: "
                                + csr.getDataItems().get(0).getMajorType());
        }
        return csrBytes;
    }

    private static void checkChallengeV1(Array csr, byte[] expectedChallenge) throws Exception {
        ByteString challenge = (ByteString) csr.getDataItems().get(1);
        assertThat(challenge.getBytes()).isEqualTo(expectedChallenge);
    }

    private static void checkChallengeV2(Array csr, byte[] expectedChallenge) throws Exception {
        // Check the challenge embedded in the AuthenticatedRequest.
        UnsignedInteger version = (UnsignedInteger) csr.getDataItems().get(0);
        assertThat(version.getValue().intValue()).isEqualTo(1);
        Array signedData = (Array) csr.getDataItems().get(3);
        ByteString payloadBytes = (ByteString) signedData.getDataItems().get(2);
        List<DataItem> payloadItems = CborDecoder.decode(payloadBytes.getBytes());
        Array payload = (Array) payloadItems.get(0);
        ByteString challenge = (ByteString) payload.getDataItems().get(0);
        assertThat(challenge.getBytes()).isEqualTo(expectedChallenge);
    }

    private static boolean supportAvfRemoteAttestation(ITestDevice device) throws Exception {
        TestDevice testDevice = (TestDevice) device;
        return testDevice.supportsMicrodroid(/* protectedVm= */ true)
                && testDevice.getBooleanProperty(PROP_AVF_REMOTE_ATTESTATION_ENABLED, true);
    }
}
