package com.pacvue.lab.common.probe;

import java.time.Duration;

/**
 * Outcome of a {@link MiddlewareProbe#ping()} call.
 *
 * @param middleware id of the probed middleware
 * @param healthy    whether the round trip succeeded
 * @param detail     version string when healthy, failure message otherwise
 * @param cost       wall-clock time of the round trip
 */
public record ProbeResult(String middleware, boolean healthy, String detail, Duration cost) {

    public static ProbeResult up(String middleware, String detail, Duration cost) {
        return new ProbeResult(middleware, true, detail, cost);
    }

    public static ProbeResult down(String middleware, Throwable cause, Duration cost) {
        return new ProbeResult(middleware, false, cause.getClass().getSimpleName() + ": " + cause.getMessage(), cost);
    }
}
