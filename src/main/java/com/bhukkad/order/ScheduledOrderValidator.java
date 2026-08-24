package com.bhukkad.order;

import com.bhukkad.config.ScheduledOrderProperties;
import com.bhukkad.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledOrderValidator {

    private final ScheduledOrderProperties scheduledOrderProperties;

    public void validateScheduledAt(LocalDateTime scheduledAt) {
        if (scheduledAt == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime minTime = now.plusMinutes(scheduledOrderProperties.getMinimumLeadMinutes());
        LocalDateTime maxTime = now.plusDays(scheduledOrderProperties.getMaxDaysAhead());
        if (scheduledAt.isBefore(minTime)) {
            throw new BusinessException("Scheduled time must be at least "
                    + scheduledOrderProperties.getMinimumLeadMinutes() + " minutes from now");
        }
        if (scheduledAt.isAfter(maxTime)) {
            throw new BusinessException("Scheduled time cannot be more than "
                    + scheduledOrderProperties.getMaxDaysAhead() + " days ahead");
        }
    }

    public boolean isScheduledOrder(LocalDateTime scheduledAt) {
        return scheduledAt != null && scheduledAt.isAfter(LocalDateTime.now());
    }
}
