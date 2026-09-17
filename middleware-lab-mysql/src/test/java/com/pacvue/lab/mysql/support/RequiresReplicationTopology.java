package com.pacvue.lab.mysql.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Enables a test class only when the two-node topology from
 * {@code docker-compose.replication.yml} is up and replicating.
 *
 * <p>This exists because {@link EnabledIf} is not {@code @Inherited}: putting it on
 * {@link AbstractReplicationIT} looks like it works, and quietly does nothing for the
 * subclasses - they run, fail to connect, and report errors instead of skipping. Wrapping it
 * in an {@code @Inherited} annotation of our own is what makes a base class able to carry the
 * condition.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@EnabledIf("com.pacvue.lab.mysql.support.ReplicationCluster#isUp")
public @interface RequiresReplicationTopology {
}
