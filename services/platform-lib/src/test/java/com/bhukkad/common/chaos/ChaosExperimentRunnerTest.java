package com.bhukkad.common.chaos;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link ChaosExperimentRunner} enforces the two Section 3.5 safety
 * invariants:
 * <ul>
 *   <li>every experiment respects its {@code maxDuration} (duration must not
 *       exceed maxDuration), and</li>
 *   <li>fail-closed: experiments missing a {@code maxDuration} or a
 *       {@code bhukkad.blast-radius} label are blocked and never applied.</li>
 * </ul>
 */
public class ChaosExperimentRunnerTest {

    private final ChaosExperimentRunner runner = new ChaosExperimentRunner();

    private static ChaosExperimentRunner.Experiment experiment(String name,
                                                               String blastRadius,
                                                               Duration maxDuration,
                                                               Duration duration) {
        return new ChaosExperimentRunner.Experiment(
                name, "PodChaos", "bhukkad", blastRadius, maxDuration, duration);
    }

    @Test
    void withinMaxDuration_isValid() {
        ChaosExperimentRunner.Experiment exp = experiment(
                "pod-kill-gateway", "single-pod", Duration.ofMinutes(5), Duration.ofSeconds(30));

        ChaosValidationResult result = runner.validate(exp);

        assertThat(result.isValid()).isTrue();
        assertThat(result.failClosed()).isFalse();
        assertThat(result.reason()).contains("within maxDuration");
    }

    @Test
    void durationEqualsMaxDuration_isValid() {
        Duration d = Duration.ofMinutes(5);
        ChaosExperimentRunner.Experiment exp = experiment(
                "redis-latency-100ms", "single-cache-node", d, d);

        ChaosValidationResult result = runner.validate(exp);

        assertThat(result.isValid()).isTrue();
        assertThat(result.failClosed()).isFalse();
    }

    @Test
    void durationExceedsMaxDuration_isFailClosed() {
        ChaosExperimentRunner.Experiment exp = experiment(
                "db-connection-exhaustion", "single-db-primary",
                Duration.ofMinutes(5), Duration.ofMinutes(10));

        ChaosValidationResult result = runner.validate(exp);

        assertThat(result.isValid()).isFalse();
        assertThat(result.failClosed()).isTrue();
        assertThat(result.reason()).contains("exceeds maxDuration");
    }

    @Test
    void missingMaxDuration_isFailClosed() {
        ChaosExperimentRunner.Experiment exp = new ChaosExperimentRunner.Experiment(
                "no-max-duration", "PodChaos", "bhukkad", "single-pod", null, Duration.ofSeconds(30));

        ChaosValidationResult result = runner.validate(exp);

        assertThat(result.isValid()).isFalse();
        assertThat(result.failClosed()).isTrue();
        assertThat(result.reason()).contains("missing bhukkad.max-duration");
    }

    @Test
    void missingBlastRadius_isFailClosed() {
        ChaosExperimentRunner.Experiment exp = new ChaosExperimentRunner.Experiment(
                "no-blast-radius", "PodChaos", "bhukkad", null, Duration.ofMinutes(5), Duration.ofSeconds(30));

        ChaosValidationResult result = runner.validate(exp);

        assertThat(result.isValid()).isFalse();
        assertThat(result.failClosed()).isTrue();
        assertThat(result.reason()).contains("missing bhukkad.blast-radius");
    }

    @Test
    void assertMayRun_throwsForFailClosedExperiment() {
        ChaosExperimentRunner.Experiment exp = experiment(
                "blocked-exp", "single-pod", Duration.ofMinutes(1), Duration.ofMinutes(10));

        assertThatThrownBy(() -> runner.assertMayRun(exp))
                .isInstanceOf(IllegalStateException.class)
                .message().contains("blocked (fail-closed)");
    }

    @Test
    void assertMayRun_passesForValidExperiment() {
        ChaosExperimentRunner.Experiment exp = experiment(
                "ok-exp", "single-cache-node", Duration.ofMinutes(10), Duration.ofMinutes(2));

        // Should not throw
        runner.assertMayRun(exp);
    }

    @Test
    void allThreeSection35ExperimentsRespectMaxDuration() {
        List<ChaosExperimentRunner.Experiment> experiments = List.of(
                experiment("pod-kill-gateway", "single-pod", Duration.ofMinutes(5), Duration.ofSeconds(30)),
                experiment("redis-latency-100ms", "single-cache-node", Duration.ofMinutes(10), Duration.ofMinutes(2)),
                experiment("db-connection-exhaustion", "single-db-primary", Duration.ofMinutes(10), Duration.ofMinutes(3)));

        for (ChaosExperimentRunner.Experiment exp : experiments) {
            ChaosValidationResult result = runner.validate(exp);
            assertThat(result.isValid())
                    .as(exp.name() + " must respect maxDuration").isTrue();
            assertThat(result.failClosed())
                    .as(exp.name() + " must not be fail-closed").isFalse();
            assertThat(exp.blastRadius()).isNotBlank();
            assertThat(exp.maxDuration()).isNotNull();
        }
    }
}
