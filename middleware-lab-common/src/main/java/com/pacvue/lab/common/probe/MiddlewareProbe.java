package com.pacvue.lab.common.probe;

/**
 * One implementation per middleware under test (Elasticsearch, ShardingSphere, Redis, ...).
 *
 * <p>Keeps the "is it alive / what version is it" question uniform across modules so the
 * lab can report on several middlewares side by side.
 */
public interface MiddlewareProbe {

    /** Stable id of the middleware, e.g. {@code "elasticsearch"}. */
    String middleware();

    /** Cheap round trip against the middleware. Must never throw: failures belong in the result. */
    ProbeResult ping();
}
