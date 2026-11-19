/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AcpCommandPolicyTest {
    @Test
    void fusionDefaultsContainOnlyAcpAdditionsToTheCommonSecretRules() {
        final var conf = configuration(Map.of());
        assertEquals(
                List.of(
                        "passphrase",
                        "recoverycode",
                        "backupcode",
                        "payment",
                        "creditcard",
                        "debitcard",
                        "cardnumber",
                        "cardverification",
                        "securitycode",
                        "onetimecode"),
                conf.elicitationSensitiveMarkers());
        assertEquals(List.of("pin", "cvv", "cvc", "otp"), conf.elicitationSensitiveWords());
    }

    @Test
    void fusionParsesLiteralMarkerAndWholeWordOverrides() {
        final var conf = configuration(Map.of(
                "elicitationSensitiveMarkers", "Team / Entry,company badge",
                "elicitationSensitiveWords", "stepup,o.tp"));
        assertEquals(List.of("Team / Entry", "company badge"), conf.elicitationSensitiveMarkers());
        assertEquals(List.of("stepup", "o.tp"), conf.elicitationSensitiveWords());
    }

    @Test
    void explicitEmptyListsDisableOnlyTheAcpAdditionsInsteadOfRestoringDefaults() {
        final var conf = configuration(Map.of(
                "elicitationSensitiveMarkers", "",
                "elicitationSensitiveWords", ""));
        assertEquals(List.of(), conf.elicitationSensitiveMarkers());
        assertEquals(List.of(), conf.elicitationSensitiveWords());
    }

    private AcpCommand.Conf configuration(final Map<String, String> properties) {
        return new AcpCommand$Conf$FusionConfigurationFactory(key -> Optional.ofNullable(properties.get(key))).get();
    }
}
