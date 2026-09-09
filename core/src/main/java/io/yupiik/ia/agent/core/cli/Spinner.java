package io.yupiik.ia.agent.core.cli;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A lightweight terminal activity indicator for waiting periods (LLM first
 * token, long tool execution). Writes a single spinner line to stderr using
 * carriage returns and erases itself on {@link #stop()}. Inert when the
 * output is not an interactive terminal or no frames are configured, so it
 * never corrupts piped/scripted output.
 */
public final class Spinner {
    private final Symbols symbols;
    private final PrintStream out;
    private final boolean active;
    private final long startNanos = System.nanoTime();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread thread;
    private volatile String label;

    public Spinner(final Symbols symbols, final PrintStream out, final boolean tty) {
        this.symbols = symbols;
        this.out = out;
        this.active = tty && symbols.spinner().length > 0;
    }

    public void start(final String label) {
        if (!active) {
            return;
        }
        this.label = label;
        running.set(true);
        thread = Thread.ofVirtual().name("cli-spinner").start(() -> {
            final var frames = symbols.spinner();
            int i = 0;
            while (running.get()) {
                final var seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                out.print('\r' + String.valueOf(frames[i++ % frames.length])
                        + ' ' + label + (seconds >= 1 ? String.format(" %.1fs", seconds) : ""));
                out.flush();
                try {
                    Thread.sleep(80L);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    /**
     * Erases the spinner line (keeps the current status text as a static line
     * on stderr when {@code keepLabel} is true).
     */
    public void stop() {
        stop(false);
    }

    public void stop(final boolean keepLabel) {
        if (!active) {
            return;
        }
        running.set(false);
        if (thread != null) {
            try {
                thread.join(200L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        out.print('\r');
        if (keepLabel && label != null) {
            out.print(symbols.infoMarker() + label);
        }
        out.print("\r");
        out.print("\u001b[2K");
        out.print('\r');
        out.flush();
    }
}