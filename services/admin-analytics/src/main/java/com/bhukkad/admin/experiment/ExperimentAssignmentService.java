package com.bhukkad.admin.experiment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic A/B experiment assignment with exposure logging (FEATURE #7).
 *
 * <p><strong>Consistent bucketing:</strong> {@code bucket = stableHash(experiment + ":" + userId)
 * mod 10000}. The same user always lands in the same variant for a given
 * experiment — no flapping between requests and no sticky-assignment store.
 * Variants consume buckets in declared order by cumulative weight; weights
 * below 100 leave the remainder unassigned (excluded from analysis).</p>
 *
 * <p><strong>Exposure logging:</strong> the first assignment is persisted to
 * {@code experiment_exposures} (unique per user/experiment), giving analytics an
 * exact cohort census. Logging is best-effort and never fails assignment.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExperimentAssignmentService {

    private final ExperimentProperties properties;
    private final ExperimentExposureRepository exposureRepository;

    /**
     * Assigns the user to a variant, logging the exposure on first touch.
     *
     * @return the variant name, or {@code null} when the experiment does not exist,
     *         is disabled, or the user falls into the unassigned remainder.
     */
    public String assign(String experimentKey, Long userId) {
        if (!properties.isEnabled() || userId == null) {
            return null;
        }
        ExperimentProperties.Experiment experiment = properties.getExperiment(experimentKey);
        if (experiment == null || !experiment.isEnabled() || experiment.getVariants().isEmpty()) {
            return null;
        }

        int bucket = bucketFor(experimentKey, userId);
        ExperimentProperties.Variant variant = resolveVariant(experiment, bucket);
        if (variant == null) {
            return null;
        }

        logExposure(experimentKey, userId, variant.getName(), bucket);
        return variant.getName();
    }

    /**
     * Cohort census for one experiment: variant → number of exposed users.
     * Feeds conversion/AOV/retention lift calculations downstream.
     */
    public Map<String, Long> exposureCounts(String experimentKey) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : exposureRepository.countByVariant(experimentKey)) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }

    private int bucketFor(String experimentKey, Long userId) {
        long h = 1125899906842597L; // same large-prime seed as FeatureFlagService
        String input = experimentKey + ":" + userId;
        for (char c : input.toCharArray()) {
            h = 31 * h + c;
        }
        // Modulo before abs: Math.abs(Long.MIN_VALUE) overflows back to negative.
        int bucket = (int) (h % 10000);
        return bucket < 0 ? bucket + 10000 : bucket;
    }

    private ExperimentProperties.Variant resolveVariant(ExperimentProperties.Experiment experiment, int bucket) {
        int cursor = 0;
        for (ExperimentProperties.Variant variant : experiment.getVariants()) {
            cursor += Math.max(0, variant.getWeight()) * 100; // percent → basis points
            if (bucket < cursor) {
                return variant;
            }
        }
        return null; // unassigned remainder
    }

    private void logExposure(String experimentKey, Long userId, String variantName, int bucket) {
        try {
            // First write wins thanks to the unique constraint; repeat calls short-circuit.
            if (exposureRepository.findByExperimentKeyAndUserId(experimentKey, userId).isPresent()) {
                return;
            }
            ExperimentExposure exposure = new ExperimentExposure();
            exposure.setExperimentKey(experimentKey);
            exposure.setUserId(userId);
            exposure.setVariant(variantName);
            exposure.setBucket(bucket);
            exposureRepository.save(exposure);
        } catch (DataIntegrityViolationException raced) {
            // Another request logged the exposure first — that's the desired outcome.
        } catch (Exception ex) {
            log.warn("EXPOSURE_LOG_FAILED | experiment={} | userId={} | error={}",
                    experimentKey, userId, ex.getMessage());
        }
    }
}
