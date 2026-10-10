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
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exposes the single application-wide {@link ExecutorService} (a virtual-thread-per-task executor) shared by all
 * consumers (agent turn loops, bridge-to-workspace, hook callbacks, memory compaction, ...). Consumers inject
 * {@link ExecutorService} and never shut it down — {@link Executors#newVirtualThreadPerTaskExecutor()} returns an
 * {@link AutoCloseable} executor, so the container releases the pool when the application stops.
 */
@DefaultScoped
public class ExecutorServiceProducer {

    @Bean
    @ApplicationScoped
    public ExecutorService executor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
