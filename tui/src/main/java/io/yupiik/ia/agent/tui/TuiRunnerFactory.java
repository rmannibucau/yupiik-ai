package io.yupiik.ia.agent.tui;

import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.time.Duration;

/**
 * Creates the {@link TuiRunner} used by the TUI command. Isolated in a bean so
 * tests can substitute a headless runner backed by an in-memory terminal.
 */
@ApplicationScoped
public class TuiRunnerFactory {
    public TuiRunner create(final TuiCommand.TuiConf conf) throws Exception {
        return TuiRunner.create(TuiConfig.builder()
                .mouseCapture(true)
                .tickRate(Duration.ofMillis(100))
                .bindings(conf.bindings().toBindings())
                .build());
    }
}
