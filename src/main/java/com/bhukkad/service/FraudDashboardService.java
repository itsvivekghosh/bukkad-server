package com.bhukkad.service;

import com.bhukkad.dto.response.FraudDashboardResponse;
import com.bhukkad.dto.response.FraudPatternResponse;
import com.bhukkad.entity.FraudEvent;
import com.bhukkad.entity.FraudReviewAction;
import com.bhukkad.repository.FraudEventRepository;
import com.bhukkad.repository.FraudReviewActionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudDashboardService {

    private final FraudEventRepository fraudEventRepository;
    private final FraudReviewActionRepository fraudReviewActionRepository;

    public FraudDashboardResponse getDashboard() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last24Hours = now.minusHours(24);
        LocalDateTime last7Days = now.minusDays(7);
        LocalDateTime last30Days = now.minusDays(30);

        List<FraudEvent> allEvents = fraudEventRepository.findTop100ByOrderByCreatedAtDesc();
        List<FraudEvent> recentEvents = allEvents.stream()
                .filter(e -> e.getCreatedAt().isAfter(last24Hours))
                .toList();

        Map<String, Long> eventsByType = allEvents.stream()
                .collect(Collectors.groupingBy(FraudEvent::getEventType, Collectors.counting()));

        Map<String, Long> eventsByIP = allEvents.stream()
                .filter(e -> e.getIpAddress() != null)
                .collect(Collectors.groupingBy(FraudEvent::getIpAddress, Collectors.counting()));

        Map<String, Long> eventsByDevice = allEvents.stream()
                .filter(e -> e.getDeviceFingerprint() != null)
                .collect(Collectors.groupingBy(FraudEvent::getDeviceFingerprint, Collectors.counting()));

        List<FraudPatternResponse> topIPs = eventsByIP.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(entry -> FraudPatternResponse.builder()
                        .identifier(entry.getKey())
                        .type("IP")
                        .count(entry.getValue())
                        .build())
                .toList();

        List<FraudPatternResponse> topDevices = eventsByDevice.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(entry -> FraudPatternResponse.builder()
                        .identifier(entry.getKey())
                        .type("DEVICE")
                        .count(entry.getValue())
                        .build())
                .toList();

        return FraudDashboardResponse.builder()
                .totalEvents(allEvents.size())
                .eventsLast24Hours(recentEvents.size())
                .eventsLast7Days(allEvents.stream().filter(e -> e.getCreatedAt().isAfter(last7Days)).count())
                .eventsLast30Days(allEvents.stream().filter(e -> e.getCreatedAt().isAfter(last30Days)).count())
                .pendingReviewCount(fraudReviewActionRepository.countByStatus(FraudReviewAction.FraudReviewStatus.PENDING))
                .eventsByType(eventsByType)
                .topIPs(topIPs)
                .topDevices(topDevices)
                .recentEvents(allEvents.stream().limit(20).map(this::toEventResponse).toList())
                .build();
    }

    public List<FraudEvent> getEventsForReview() {
        return fraudEventRepository.findTop100ByOrderByCreatedAtDesc();
    }

    private com.bhukkad.dto.response.FraudEventResponse toEventResponse(FraudEvent event) {
        return com.bhukkad.dto.response.FraudEventResponse.builder()
                .id(event.getId())
                .eventType(event.getEventType())
                .ipAddress(event.getIpAddress())
                .deviceFingerprint(event.getDeviceFingerprint())
                .details(event.getDetails())
                .createdAt(event.getCreatedAt().toString())
                .build();
    }
}