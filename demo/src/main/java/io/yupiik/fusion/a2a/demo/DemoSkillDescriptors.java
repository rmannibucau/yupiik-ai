/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.demo;

import io.yupiik.fusion.a2a.api.A2ASkills;
import io.yupiik.fusion.a2a.model.AgentSkill;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.util.List;

/**
 * Completes the skill descriptors: a method signature gives the identifier, the name and the description, this adds
 * the tags the specification requires plus the examples and the media types.
 * <p>
 * It also declares {@code demo/countdown}, which is not backed by a method but by {@link DemoCountdownExecutor}.
 */
@ApplicationScoped
public class DemoSkillDescriptors implements A2ASkills {
    @Override
    public List<AgentSkill> skills() {
        return List.of(new AgentSkill(
                DemoCountdownExecutor.SKILL_ID,
                "Countdown",
                "Counts down from a number, reporting the progress as it goes.",
                List.of("demo", "streaming", "progress"),
                List.of("Count down from 3."),
                null,
                List.of("text/plain"),
                null));
    }

    @Override
    public AgentSkill describe(final AgentSkill derived) {
        return switch (derived.id()) {
            case "demo/greet" -> derived.describedBy(
                    List.of("demo", "greeting"),
                    List.of("Greet Fusion."),
                    null,
                    List.of("text/plain"));
            case "demo/report" -> derived.describedBy(
                    List.of("demo", "weather", "report"),
                    List.of("What is the weather in Paris?"),
                    null,
                    List.of("application/json"));
            case "demo/chat" -> derived.describedBy(
                    List.of("demo", "chat"),
                    List.of("Hello there!"),
                    null,
                    List.of("text/plain"));
            default -> derived; // not ours, let the next implementation - or the default - handle it
        };
    }
}
