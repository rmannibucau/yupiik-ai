package io.yupiik.ia.agent.core.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpinnerTest {
    @Test
    void inertWithoutTty() {
        final var out = new ByteArrayOutputStream();
        final var spinner = new Spinner(new Symbols(false), new PrintStream(out, true), false);
        spinner.start("thinking");
        spinner.stop();
        assertEquals(0, out.size());
    }

    @Test
    void doubleStartDoesNotOrphanTheFirstAnimationThread() throws InterruptedException {
        final var out = new ByteArrayOutputStream();
        final var spinner = new Spinner(new Symbols(true), new PrintStream(out), true);
        spinner.start("thinking");
        spinner.start("thinking");
        Thread.sleep(250);
        spinner.stop();
        final var sizeAfterStop = out.size();
        assertTrue(sizeAfterStop > 0);
        Thread.sleep(250);
        assertEquals(sizeAfterStop, out.size(), "a leaked animation thread kept writing after stop()");
    }

    @Test
    void restartAfterStopAnimatesAgain() throws InterruptedException {
        final var out = new ByteArrayOutputStream();
        final var spinner = new Spinner(new Symbols(true), new PrintStream(out), true);
        spinner.start("thinking");
        Thread.sleep(250);
        spinner.stop();
        final var sizeAfterFirstStop = out.size();
        spinner.start("thinking");
        Thread.sleep(250);
        spinner.stop();
        assertTrue(out.size() > sizeAfterFirstStop, "no frames after restart");
    }
}
