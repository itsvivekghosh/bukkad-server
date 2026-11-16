package com.bhukkad.common.chaos;

import java.time.Duration;

/**
 * Result of validating a chaos experiment before it is allowed to run.
 *
 * <p>Fail-closed semantics: an experiment may only run when it declares a
 * {@code maxDuration} and its declared {@code duration} does not exceed it. Any
 * experiment missing a {@code maxDuration}, or whose {@code duration} exceeds
 * {@code maxDuration}, is considered unsafe and must not run
 * ({@link #failClosed()} returns {@code true}).
 */
final class ChaosValidationResult {

    private final boolean valid;
    private final boolean failClosed;
    private final String reason;

    private ChaosValidationResult(boolean valid, boolean failClosed, String reason) {
        this.valid = valid;
        this.failClosed = failClosed;
        this.reason = reason;
    }

    public boolean isValid() {
        return valid;
    }

    /**
     * {@code true} when the experiment must not run because it is missing a
     * {@code maxDuration} or its {@code duration} exceeds {@code maxDuration}.
     */
    public boolean failClosed() {
        return failClosed;
    }

    public String reason() {
        return reason;
    }

    public static ChaosValidationResult ok(String reason) {
        return new ChaosValidationResult(true, false, reason);
    }

    public static ChaosValidationResult blocked(String reason) {
        return new ChaosValidationResult(false, true, reason);
    }
}

/**
 * Orchestrates chaos experiments against a cluster.
 *
 * <p>The runner enforces the two safety invariants required by
 * docs/architecture-audit.md Section 3.5:</p>
 * <ul>
 *   <li><b>Blast-radius limit:</b> every experiment must carry a
 *       {@code bhukkad.blast-radius} label (single-pod / single-cache-node /
 *       single-db-primary), bounding the affected scope.</li>
 *   <li><b>Automatic rollback / maxDuration:</b> every experiment must declare a
 *       {@code bhukkad.max-duration}, and its declared {@code duration} must not
 *       exceed it. Experiments that violate this are fail-closed (blocked) and
 *       never applied.</li>
 * </ul>
 *
 * <p>This class is deliberately free of Kubernetes/Chaos Mesh client code so it
 * can be unit-tested without a cluster. The actual apply/delete is performed by
 * {@code .github/workflows/chaos-validation.yml} using `kubectl`; this runner is
 * the unit-tested policy layer that decides whether an experiment may run.</p>
 */
public final class ChaosExperimentRunner {

    /**
     * A chaos experiment as declared in {@code k8s/chaos/*.yaml}.
     */
    public record Experiment(
            String name,
            String kind,
            String namespace,
            String blastRadius,
            Duration maxDuration,
            Duration duration
    ) {
        public Experiment {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("experiment name is required");
            }
            if (kind == null || kind.isBlank()) {
                throw new IllegalArgumentException("experiment kind is required");
            }
            if (namespace == null || namespace.isBlank()) {
                throw new IllegalArgumentException("experiment namespace is required");
            }
            if (duration == null) {
                throw new IllegalArgumentException("experiment duration is required");
            }
        }
    }

    /**
     * Validates an experiment before it may be applied.
     *
     * @return a {@link ChaosValidationResult}; {@link ChaosValidationResult#failClosed()}
     *         is {@code true} when the experiment must not run (missing maxDuration
     *         or duration exceeds maxDuration).
     */
    public ChaosValidationResult validate(Experiment experiment) {
        if (experiment.blastRadius() == null || experiment.blastRadius().isBlank()) {
            return ChaosValidationResult.blocked(
                    experiment.name() + ": missing bhukkad.blast-radius label (experiment may not run)");
        }
        if (experiment.maxDuration() == null) {
            return ChaosValidationResult.blocked(
                    experiment.name() + ": missing bhukkad.max-duration (fail-closed: may not run without a bounded rollback)");
        }
        if (experiment.duration().compareTo(experiment.maxDuration()) > 0) {
            return ChaosValidationResult.blocked(
                    experiment.name() + ": duration " + experiment.duration()
                            + " exceeds maxDuration " + experiment.maxDuration()
                            + " (fail-closed: may not run)");
        }
        return ChaosValidationResult.ok(
                experiment.name() + ": within maxDuration " + experiment.maxDuration()
                        + ", blast radius " + experiment.blastRadius());
    }

    /**
     * Decides whether the experiment may run. Fail-closed: throws when the
     * experiment is not valid so callers cannot accidentally apply an unsafe
     * experiment.
     *
     * @throws IllegalStateException when the experiment is fail-closed.
     */
    public void assertMayRun(Experiment experiment) {
        ChaosValidationResult result = validate(experiment);
        if (result.failClosed()) {
            throw new IllegalStateException("Chaos experiment blocked (fail-closed): " + result.reason());
        }
    }
}
