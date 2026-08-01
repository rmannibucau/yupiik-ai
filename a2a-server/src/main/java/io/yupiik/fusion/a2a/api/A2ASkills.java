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
package io.yupiik.fusion.a2a.api;

import io.yupiik.fusion.a2a.model.AgentSkill;

import java.util.List;

/**
 * Completes what the {@link A2ASkill} methods say about themselves.
 * <p>
 * A method signature carries the skill {@code id}, {@code name} and {@code description} but not its {@code tags} -
 * which the specification requires - nor its {@code examples} and media types, so any bean implementing this
 * interface is asked to enrich the derived descriptors and may declare skills of its own:
 * <pre>{@code
 * @ApplicationScoped
 * public class MyAgentSkills implements A2ASkills {
 *     @Override
 *     public AgentSkill describe(final AgentSkill derived) {
 *         if (!"report/build".equals(derived.id())) {
 *             return derived; // not for us
 *         }
 *         return derived.describedBy(
 *                 List.of("report", "city"),
 *                 List.of("Build a report about Paris."),
 *                 null, List.of("text/plain"));
 *     }
 * }
 * }</pre>
 * All the implementations are used in bean priority order ({@code @Order}): {@link #skills()} results are
 * concatenated and {@link #describe(AgentSkill)} is chained, each one seeing what the previous returned.
 * <p>
 * The card is built once at startup, so both methods must be cheap and stable.
 */
public interface A2ASkills {
    /**
     * @return skills to advertise which are not backed by a {@link A2ASkill} method - typically because an
     * {@link A2AExecutor} handles them.
     */
    default List<AgentSkill> skills() {
        return List.of();
    }

    /**
     * @param derived the descriptor built from a {@link A2ASkill} method signature, or the result of the previous
     *                implementation in the chain.
     * @return the descriptor to advertise, return {@code derived} as is when the skill is not yours.
     */
    default AgentSkill describe(final AgentSkill derived) {
        return derived;
    }
}
