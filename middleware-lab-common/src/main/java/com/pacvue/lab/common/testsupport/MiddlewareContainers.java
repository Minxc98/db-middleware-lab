package com.pacvue.lab.common.testsupport;

/**
 * Knobs shared by every containerised integration test.
 *
 * <p>Containers are started once per JVM (the "singleton container" pattern) rather than per
 * test class, so a whole module's suite pays the startup cost only once. Set
 * {@code -Dlab.containers.reuse=true} plus {@code testcontainers.reuse.enable=true} in
 * {@code ~/.testcontainers.properties} to also keep them alive between builds.
 */
public final class MiddlewareContainers {

    /** System property that opts into Testcontainers reuse across JVM runs. */
    public static final String REUSE_PROPERTY = "lab.containers.reuse";

    private MiddlewareContainers() {
    }

    public static boolean reuseEnabled() {
        return Boolean.parseBoolean(System.getProperty(REUSE_PROPERTY, "false"));
    }
}
