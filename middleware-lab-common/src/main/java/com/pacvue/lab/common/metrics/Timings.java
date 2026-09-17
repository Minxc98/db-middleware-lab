package com.pacvue.lab.common.metrics;

import java.time.Duration;
import java.util.concurrent.Callable;

/** Wall-clock timing helpers, so every module reports latency the same way. */
public final class Timings {

    private Timings() {
    }

    /** Runs {@code task} and returns its value together with the elapsed time. */
    public static <T> Timed<T> measure(Callable<T> task) {
        long startNanos = System.nanoTime();
        try {
            T value = task.call();
            return new Timed<>(value, Duration.ofNanos(System.nanoTime() - startNanos));
        } catch (Exception e) {
            throw new TimedExecutionException(Duration.ofNanos(System.nanoTime() - startNanos), e);
        }
    }

    public record Timed<T>(T value, Duration cost) {
    }

    /** Carries the elapsed time of a failed call so probes can still report latency. */
    public static class TimedExecutionException extends RuntimeException {
        private final transient Duration cost;

        public TimedExecutionException(Duration cost, Throwable cause) {
            super(cause);
            this.cost = cost;
        }

        public Duration cost() {
            return cost;
        }
    }
}
