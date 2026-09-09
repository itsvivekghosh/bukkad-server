package com.bhukkad.restaurant.experiment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        private String name;
        private int weight;

        public Variant() {
        }

        public Variant(String name, int weight) {
            this.name = name;
            this.weight = weight;
        }
    }
}
