package com.bhukkad.admin.experiment.service;
import com.bhukkad.admin.config.ExperimentProperties;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the experiment configuration defaults ported from the
 * monolith ({@code com.bhukkad.experiment.ExperimentProperties}, prefix
 * {@code app.experiments}): framework on by default, experiments opt-in via
 * config, and an experiment without variants falls back to a full-weight
 * {@code control} arm.
 */
class ExperimentPropertiesTest {

    @Test
    void defaults_frameworkEnabledWithNoExperiments() {
        ExperimentProperties properties = new ExperimentProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getExperiments()).isEmpty();
        assertThat(properties.getExperiment("anything")).isNull();
    }

    @Test
    void defaults_experimentEnabledWithSingleFullWeightControlVariant() {
        ExperimentProperties.Experiment experiment = new ExperimentProperties.Experiment();

        assertThat(experiment.isEnabled()).isTrue();
        assertThat(experiment.getVariants())
                .isEqualTo(List.of(new ExperimentProperties.Variant("control", 100)));
    }

    @Test
    void defaults_variantNameNullAndWeightZero() {
        ExperimentProperties.Variant variant = new ExperimentProperties.Variant();

        assertThat(variant.getName()).isNull();
        assertThat(variant.getWeight()).isZero();
    }

    @Test
    void lookup_returnsConfiguredExperiment() {
        ExperimentProperties properties = new ExperimentProperties();
        ExperimentProperties.Experiment experiment = new ExperimentProperties.Experiment();
        experiment.setDescription("checkout copy test");
        properties.getExperiments().put("checkout-cta-copy", experiment);

        assertThat(properties.getExperiment("checkout-cta-copy")).isSameAs(experiment);
        assertThat(properties.getExperiment("missing")).isNull();
    }

    @Test
    void overrides_disableFrameworkAndIndividualExperiments() {
        ExperimentProperties properties = new ExperimentProperties();
        properties.setEnabled(false);
        ExperimentProperties.Experiment experiment = new ExperimentProperties.Experiment();
        experiment.setEnabled(false);
        properties.getExperiments().put("checkout-cta-copy", experiment);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getExperiment("checkout-cta-copy").isEnabled()).isFalse();
    }
}
