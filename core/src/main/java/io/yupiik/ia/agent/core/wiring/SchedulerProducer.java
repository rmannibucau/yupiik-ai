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
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exposes the single application-wide {@link ScheduledExecutorService} shared by all consumers (code graph refresher
 * + persist, TUI git branch poller, ...). Consumers must never call {@code shutdown()/shutdownNow()} on it - they only
 * cancel the tasks they scheduled.
 */
@DefaultScoped
public class SchedulerProducer {
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Bean
    @ApplicationScoped
    public ScheduledExecutorService ses(final CodingAgentConfiguration configuration) {
        final var executor = new ScheduledThreadPoolExecutor(Math.max(1, configuration.schedulerThreads()), r -> {
            final var thread = new Thread(r, "yca-scheduler-" + COUNTER.incrementAndGet());
            thread.setDaemon(true);
            thread.setContextClassLoader(SchedulerProducer.class.getClassLoader());
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
