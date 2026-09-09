package com.bhukkad.restaurant.experiment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExperimentAssignmentService {

    private final ExperimentProperties properties;
    private final ExperimentExposureRepository exposureRepository;

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

    public Map<String, Long> exposureCounts(String experimentKey) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : exposureRepository.countByVariant(experimentKey)) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }

    private int bucketFor(String experimentKey, Long userId) {
        long h = 1125899906842597L;
        String input = experimentKey + ":" + userId;
        for (char c : input.toCharArray()) {
            h = 31 * h + c;
        }
        int bucket = (int) (h % 10000);
        return bucket < 0 ? bucket + 10000 : bucket;
    }

    private ExperimentProperties.Variant resolveVariant(ExperimentProperties.Experiment experiment, int bucket) {
        int cursor = 0;
        for (ExperimentProperties.Variant variant : experiment.getVariants()) {
            cursor += Math.max(0, variant.getWeight()) * 100;
            if (bucket < cursor) {
                return variant;
            }
        }
        return null;
    }

    private void logExposure(String experimentKey, Long userId, String variantName, int bucket) {
        try {
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
        } catch (Exception ex) {
            log.warn("EXPOSURE_LOG_FAILED | experiment={} | userId={} | error={}",
                    experimentKey, userId, ex.getMessage());
        }
    }
}
