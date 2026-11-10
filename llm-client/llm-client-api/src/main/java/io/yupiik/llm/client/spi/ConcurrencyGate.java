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
package io.yupiik.llm.client.spi;

import java.util.concurrent.CompletionStage;

/**
 * Admission control for the concurrent requests of one provider: the budget the
 * {@code maxConcurrent} knob configures.
 *
 * <p>The historical implementation was a {@link java.util.concurrent.Semaphore} private to one
 * {@code LlmClientResilienceWrapper}, hence a budget per object graph of a single JVM. That is
 * wrong for account-level limits - a {@code 429} {@code concurrency_limit_exceeded} advertising
 * {@code "limit":2} applies to the whole account, not to one process - so admission is abstracted
 * here and an implementation may share the budget with other processes (see the file-lease based
 * gate of the core module).
 *
 * <h2>Contract</h2>
 * <ul>
 * <li>{@link #acquire()} never blocks and never throws {@link InterruptedException}: this library
 *     does not assume virtual threads, so a caller which cannot proceed must be told through the
 *     returned stage instead of by parking its carrier thread.</li>
 * <li>the returned stage may complete on <em>any</em> thread - the caller's own thread on the
 *     uncontended path, but a peer release or a poller thread once queued - so a dependent stage
 *     must not assume it runs off the thread that called {@code acquire()}.</li>
 * <li>cancelling the returned stage is the supported way to leave the queue: the implementation
 *     must stop waiting and must never grant a slot to a cancelled waiter. A slot which is granted
 *     concurrently with the cancellation is handed back to the gate by the implementation, never
 *     leaked.</li>
 * <li>the granted {@link Runnable} is the release action and must be idempotent: running it twice,
 *     or from two threads, must release exactly one slot.</li>
 * </ul>
 */
public interface ConcurrencyGate {
    /**
     * Requests one slot.
     *
     * @return a stage completed with the slot's release action as soon as a slot is free, failed when
     *     the gate is closed, or cancelled when the caller gave up. Implementations complete it as
     *     soon as possible: the uncontended path completes it synchronously on the calling thread.
     */
    CompletionStage<Runnable> acquire();

    /**
     * Sets the usable capacity. Above the configured value this is the caller relaxing a temporary
     * adaptation, below it a provider announcing a smaller limit.
     *
     * <p>Grants already handed out are never revoked - a stream can run for minutes and must not be
     * preempted - so a shrink only applies to future admissions (the freed slots are absorbed until
     * the outstanding grants are back below the new capacity), while a growth re-attempts the queued
     * waiters immediately.
     *
     * @param capacity the new usable capacity, clamped by the implementation to {@code >= 1}.
     */
    void setCapacity(int capacity);
}
