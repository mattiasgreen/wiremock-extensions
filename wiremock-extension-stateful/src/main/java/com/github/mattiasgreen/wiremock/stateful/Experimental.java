package com.github.mattiasgreen.wiremock.stateful;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that a class, method, or package in {@code wiremock-extension-stateful}
 * is an experimental alpha incubation prototype subject to redesign, breaking changes,
 * or deprecation.
 *
 * <p>Stateful in-memory AST simulation is evaluated against native WireMock Scenarios
 * and provider-supplied Level 4 Testcontainers. See module {@code README.md} for the
 * Crossing Line decision matrix.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PACKAGE})
public @interface Experimental {

    /**
     * Optional description of the incubation status or migration guidance.
     *
     * @return status description
     */
    String value() default "Alpha incubation prototype";
}
