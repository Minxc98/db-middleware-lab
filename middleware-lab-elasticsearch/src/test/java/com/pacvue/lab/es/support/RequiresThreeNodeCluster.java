package com.pacvue.lab.es.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Enables a test class only when the 3-node cluster from {@code docker-compose.cluster.yml}
 * is up.
 *
 * <p>This exists because {@link EnabledIf} is not {@code @Inherited}: putting it on
 * {@link AbstractClusterIT} looks like it works, and quietly does nothing for the subclasses -
 * with the cluster down they run anyway, fail to connect, and report errors instead of
 * skipping. Wrapping it in an {@code @Inherited} annotation of our own is what makes a base
 * class able to carry the condition.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@EnabledIf("com.pacvue.lab.es.support.ClusterAvailability#isThreeNodeClusterUp")
public @interface RequiresThreeNodeCluster {
}
