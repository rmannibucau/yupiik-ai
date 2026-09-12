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
package io.yupiik.maven.knowledge.llm;

import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.config.TokenBudget;
import java.util.List;
import java.util.Map;

/**
 * Pluggable gateway used by the knowledge pipeline to delegate the intelligence to an LLM. Implementations
 * are supplied by the embedding runtime (yca provides an adapter over its own {@code ChatClient}; the MCP
 * server provides one over whatever model backend it is wired to).
 *
 * <p>The knowledge module stays framework-free: each method here is a bounded, bulk, JSON-Schema-constrained
 * LLM call so that many GAVs are processed in a single round trip (minimising the number of calls and the cost).</p>
 */
public interface KnowledgeLlm {

    /**
     * Ask the LLM to identify, for each given artifact, its core entry points (entry classes,
     * {@code main} classes, functional interfaces, config/init points) from the provided content.
     * Multiple GAVs MUST be answered in a single call.
     *
     * @param contentByGav content (source, javadoc or bytecode-derived) per GAV to analyse.
     * @return per-GAV ordered list of FQN entry points; absent GAVs are skipped by the caller.
     */
    Map<Gav, List<String>> entryPoints(Map<Gav, String> contentByGav);

    /**
     * Ask the LLM to pick, from the current session/project, the main entry-point artifacts (GAVs) whose
     * generated knowledge the session should load. Used at session start to dereference the per-artifact
     * {@code -skills.md} manifests.
     *
     * @param candidates not-yet-indexed artifact GAVs to choose from.
     * @return the subset of {@code candidates} the model considers relevant to the session.
     */
    List<Gav> suggestEntryGavs(List<Gav> candidates);

    /**
     * Synthesize a single artifact knowledge file (SKILL.md in the gold format) from acquired content and its
     * already-known entry points.
     *
     * @param gav        the artifact.
     * @param name       suggested short name (usually the artifactId).
     * @param content    acquired content (source/javadoc/documentation).
     * @param entryPoints entry points identified in the same batch.
     * @return the full SKILL.md text, starting with the YAML frontmatter.
     */
    String synthesizeSkill(Gav gav, String name, String content, List<String> entryPoints);

    /**
     * Whether the runtime can actually reach an LLM. When false the pipeline degrades gracefully (skips LLM
     * stages) rather than failing.
     */
    default boolean available() {
        return true;
    }

    /**
     * Token limits of the underlying model, used to size entry-points batches greedily when
     * {@code maxBatchChars == -1}. Defaults to unknown, which makes the pipeline fall back to GAV-count
     * batching; backends that can report their model budget override this.
     */
    default TokenBudget tokenBudget() {
        return new TokenBudget(0, 0);
    }
}
