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
package io.yupiik.ia.agent.core.session;

import io.yupiik.ia.agent.core.llm.Message;
import java.nio.file.Path;
import java.util.List;

/**
 * Stores conversation sessions. Implementations can be file-backed (persistent)
 * or in-memory (ephemeral, for subagents).
 */
public interface SessionStore {

    /**
     * Creates a new session and returns its ID.
     *
     * @return the new session ID
     */
    default String create() {
        return null;
    }

    /**
     * Appends an entry to the session.
     *
     * @param id the session ID
     * @param entry the entry to append
     */
    default void append(String id, SessionEntry entry) {}

    /**
     * Loads all messages from a session.
     *
     * @param id the session ID
     * @return the list of messages, empty if session doesn't exist
     */
    default List<Message> load(String id) {
        return List.of();
    }

    /**
     * Replaces the entire session content.
     *
     * @param id the session ID
     * @param messages the new messages
     */
    default void replace(String id, List<Message> messages) {}

    /**
     * Checks if a session is empty (no messages or only initial user prompt).
     *
     * @param id the session ID
     * @return true if empty
     */
    default boolean isEmpty(String id) {
        return false;
    }

    /**
     * Deletes a session.
     *
     * @param id the session ID
     */
    default void delete(String id) {}

    /**
     * Returns a one-line summary of the session (first user prompt).
     *
     * @param id the session ID
     * @return the summary, or empty string
     */
    default String summary(String id) {
        return "";
    }

    /**
     * Returns the file path for a session ID (file-backed implementations).
     *
     * @param id the session ID
     * @return the session file path
     */
    default Path path(String id) {
        throw new UnsupportedOperationException("path() not supported by this SessionStore implementation");
    }

    /**
     * The session storage directory (file-backed implementations).
     *
     * @return the session directory path
     */
    default Path dir() {
        throw new UnsupportedOperationException("dir() not supported by this SessionStore implementation");
    }

    /** Current time in millis (used to timestamp session entries). */
    default long now() {
        return System.currentTimeMillis();
    }

    /** Housekeeping convenience: cleans without deleting empty sessions. */
    default void cleanup() {
        housekeeping(false);
    }

    /**
     * Cleans the session directory: deletes sessions older than the retention
     * window, compresses sessions older than the compress window.
     *
     * @param deleteEmpty whether to delete empty sessions
     * @return report of what was done
     */
    default HousekeepingReport housekeeping(boolean deleteEmpty) {
        return new HousekeepingReport(0, 0, 0);
    }

    /**
     * Lists all stored sessions.
     *
     * @return list of session info
     */
    default List<SessionInfo> list() {
        return List.of();
    }

    /**
     * Returns the last session ID, if any.
     *
     * @return the last session ID or null
     */
    default String lastSessionId() {
        return null;
    }

    record SessionInfo(String id, long modifiedAt, long sizeBytes) {}

    record HousekeepingReport(int compressed, int deleted, int kept) {}
}
