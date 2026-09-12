package io.yupiik.ia.agent.core.context;

import io.yupiik.ia.agent.core.llm.Message;

import java.util.List;

/**
 * The outcome of a prune or compact operation, including which strategy ran so
 * frontends can report the truth instead of implying an LLM summarization
 * happened when it fell back to a local truncation.
 *
 * @param messages the resulting message list (never the caller's original list
 *                 unless {@code mode} is {@link Mode#NONE})
 * @param mode how the context was reduced
 * @param error a human-readable note when a fallback or failure occurred
 */
public record CompactionResult(List<Message> messages, Mode mode, String error) {

    public enum Mode {
        /** Nothing needed to be (or could be) done. */
        NONE,
        /** The LLM summarizer produced the reduced context. */
        SUMMARY,
        /** The summarizer failed and a deterministic local truncation ran. */
        TRUNCATE
    }

    public boolean changed() {
        return mode != Mode.NONE;
    }
}