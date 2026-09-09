package io.yupiik.ia.agent.core.runtime;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class VirtualThreadExecutor {
    private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();

    public void execute(final Runnable task) {
        delegate.execute(task);
    }

    @Destroy
    public void shutdown() {
        delegate.shutdown();
    }
}
