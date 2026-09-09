package io.yupiik.ia.agent.core.wiring;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.scanning.Bean;

import java.time.Clock;

@DefaultScoped
public class ClockProducer {
    @Bean
    @ApplicationScoped
    public Clock clock() {
        return Clock.systemUTC();
    }
}