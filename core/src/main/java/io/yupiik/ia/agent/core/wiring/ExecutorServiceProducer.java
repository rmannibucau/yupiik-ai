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
package io.yupiik.ia.agent.core.wiring;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exposes the single application-wide {@link ExecutorService} (a virtual-thread-per-task executor) shared by all
 * consumers (agent turn loops, bridge-to-workspace, hook callbacks, memory compaction, ...). Consumers inject
 * {@link ExecutorService} and never shut it down — {@link Executors#newVirtualThreadPerTaskExecutor()} returns an
 * {@link AutoCloseable} executor, so the container releases the pool when the application stops.
 *
 * <p>The bean is destroyed <em>last</em> ({@code @Destroy(order = Integer.MAX_VALUE)}): the raw
 * {@link Executors#newVirtualThreadPerTaskExecutor()} executor's {@code close()} is a graceful, unbounded wait for
 * every in-flight virtual thread, so it must run only once all other beans have already released the resources those
 * tasks block on — {@code ProcessService} (kills tracked subprocesses, unblocking pipe drains), {@code McpManager}
 * (closes MCP clients, unblocking {@code readLine()}), {@code ChatClient} (closes the HTTP client) and the session
 * teardown (turns, /btw, sub-agents). With every order-0 bean closed first, the executor close is a fast no-op instead
 * of a deadlock-able stall at shutdown.</p>
 */
@DefaultScoped
public class ExecutorServiceProducer {

    @Bean
    @ApplicationScoped
    @Destroy(order = Integer.MAX_VALUE)
    public ExecutorService executor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
