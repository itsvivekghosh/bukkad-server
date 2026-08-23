package com.bhukkad.experiment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Experiment definitions for the A/B testing framework (FEATURE #7).
 *
 * <pre>{@code
 * app:
 *   experiments:
 *     checkout-cta-copy:
 *       enabled: true
 *       description: "New CTA wording on checkout"
 *       variants:
 *         - name: control
 *           weight: 50
 *         - name: treatment
 *           weight: 50
 * }</pre>
 *
 * <p>Weights are percentages and may sum to less than 100 — the remainder is
 * unassigned (excluded from the experiment).</p>
 */
@Data
@ConfigurationProperties(prefix = "app.experiments")
public class ExperimentProperties {

    private boolean enabled = true;
    private Map<String, Experiment> experiments = new LinkedHashMap<>();

    public Experiment getExperiment(String key) {
        return experiments.get(key);
    }

    @Data
    public static class Experiment {
        private boolean enabled = true;
        private String description;
        private List<Variant> variants = List.of(new Variant("control", 100));
    }

    @Data
    public static class Variant {
        /** Variant arm name, e.g. {@code control} or {@code treatment}. */
        private String name;
        /** Percentage of users bucketed into this variant (0–100). */
        private int weight;

        public Variant() {
        }

        public Variant(String name, int weight) {
            this.name = name;
            this.weight = weight;
        }
    }
}
