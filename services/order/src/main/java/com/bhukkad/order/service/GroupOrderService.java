package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.AccountLookupService;
import com.bhukkad.order.api.GroupOrderResponse;
import com.bhukkad.order.domain.GroupOrder;
import com.bhukkad.order.domain.GroupOrderMember;
import com.bhukkad.order.domain.GroupOrderMemberRepository;
import com.bhukkad.order.domain.GroupOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Group ordering and bill splitting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupOrderService {

    private static final double SPLIT_TOLERANCE = 0.01;

    private final GroupOrderRepository groupOrderRepository;
    private final GroupOrderMemberRepository memberRepository;
    private final CartService cartService;
    private final AccountLookupService accountLookupService;

    @Transactional
    public GroupOrderResponse createGroupOrder(Long hostUserId, String title) {
        GroupOrder group = new GroupOrder();
        group.setHostUserId(hostUserId);
        group.setTitle(title);
        group.setStatus(GroupOrder.STATUS_OPEN);
        group = groupOrderRepository.save(group);

        GroupOrderMember hostMember = new GroupOrderMember();
        hostMember.setGroupOrderId(group.getId());
        hostMember.setCustomerId(hostUserId);
        hostMember.setStatus(GroupOrderMember.STATUS_JOINED);
        hostMember.setPaid(false);
        hostMember.setJoinedAt(LocalDateTime.now());
        memberRepository.save(hostMember);

        log.info("Group order created | groupOrderId={} | hostUserId={}", group.getId(), hostUserId);
        return toResponse(group);
    }

    @Transactional
    public GroupOrderResponse inviteMember(Long groupOrderId, Long hostUserId, String phone) {
        if (phone == null || phone.isBlank()) {
            throw new BusinessException("Phone is required");
        }
        GroupOrder group = getOpenGroup(groupOrderId);
        assertHost(group, hostUserId);

        AccountLookupService.UserSummary invitedUser = findUserByPhone(phone)
                .orElseThrow(() -> new BusinessException("No registered user with phone: " + phone));
        if (invitedUser.id().equals(hostUserId)) {
            throw new BusinessException("Host is already a member of the group");
        }
        if (memberRepository.findByGroupOrderIdAndCustomerId(groupOrderId, invitedUser.id()).isPresent()) {
            throw new BusinessException("User is already invited to this group");
        }

        GroupOrderMember member = new GroupOrderMember();
        member.setGroupOrderId(groupOrderId);
        member.setCustomerId(invitedUser.id());
        member.setInvitePhone(phone);
        member.setStatus(GroupOrderMember.STATUS_INVITED);
        memberRepository.save(member);

        log.info("Member invited | groupOrderId={} | userId={} | phoneMasked={}",
                groupOrderId, invitedUser.id(), maskPhone(phone));
        return toResponse(group);
    }

    @Transactional
    public GroupOrderResponse joinGroup(Long groupOrderId, Long userId) {
        GroupOrder group = getOpenGroup(groupOrderId);
        GroupOrderMember member = memberRepository.findByGroupOrderIdAndCustomerId(groupOrderId, userId)
                .orElseThrow(() -> new BusinessException("You are not invited to this group"));
        if (GroupOrderMember.STATUS_DECLINED.equals(member.getStatus())) {
            throw new BusinessException("Invitation to this group was declined");
        }
        if (!GroupOrderMember.STATUS_JOINED.equals(member.getStatus())) {
            member.setStatus(GroupOrderMember.STATUS_JOINED);
            member.setJoinedAt(LocalDateTime.now());
            memberRepository.save(member);
        }
        log.info("Member joined | groupOrderId={} | userId={}", groupOrderId, userId);
        return toResponse(group);
    }

    public GroupOrderResponse getGroup(Long groupOrderId, Long userId) {
        GroupOrder group = groupOrderRepository.findById(groupOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Group order not found"));
        boolean host = group.getHostUserId().equals(userId);
        boolean member = memberRepository.findByGroupOrderIdAndCustomerId(groupOrderId, userId).isPresent();
        if (!host && !member) {
            throw new UnauthorizedException("You are not a member of this group");
        }
        return toResponse(group);
    }

    @Transactional
    public GroupOrderResponse splitPayment(Long groupOrderId, Long hostUserId, Map<Long, Double> amountsByUserId) {
        GroupOrder group = getOpenGroup(groupOrderId);
        assertHost(group, hostUserId);
        if (amountsByUserId == null || amountsByUserId.isEmpty()) {
            throw new BusinessException("Split amounts are required");
        }

        double expectedTotal = resolveExpectedTotal(hostUserId);
        double recordedSum = 0.0;
        int recorded = 0;
        for (Map.Entry<Long, Double> entry : amountsByUserId.entrySet()) {
            Long userId = entry.getKey();
            Double amount = entry.getValue();
            if (userId == null || amount == null || amount < 0) {
                log.warn("Split entry skipped | groupOrderId={} | userId={} | amount={}",
                        groupOrderId, userId, amount);
                continue;
            }
            GroupOrderMember member = memberRepository
                    .findByGroupOrderIdAndCustomerId(groupOrderId, userId)
                    .orElse(null);
            if (member == null) {
                log.warn("Split entry skipped for non-member | groupOrderId={} | userId={}",
                        groupOrderId, userId);
                continue;
            }
            double rounded = Math.round(amount * 100.0) / 100.0;
            member.setAmountContribution(rounded);
            member.setPaid(true);
            memberRepository.save(member);
            recordedSum += rounded;
            recorded++;
        }
        if (Math.abs(recordedSum - expectedTotal) > SPLIT_TOLERANCE) {
            log.warn("Group split mismatch | groupOrderId={} | recorded={} | expected={} | diff={}",
                    groupOrderId, recordedSum, expectedTotal, recordedSum - expectedTotal);
        }
        if (recorded == 0) {
            throw new BusinessException("No valid member splits were recorded");
        }
        return toResponse(group);
    }

    @Transactional
    public GroupOrderResponse placeGroupOrder(Long groupOrderId, Long hostUserId) {
        GroupOrder group = getOpenGroup(groupOrderId);
        assertHost(group, hostUserId);
        group.setStatus("PLACED");
        group.setPlacedAt(LocalDateTime.now());
        groupOrderRepository.save(group);
        log.info("Group order placed | groupOrderId={} | hostUserId={}", groupOrderId, hostUserId);
        return toResponse(group);
    }

    // ==================== HELPERS ====================

    private GroupOrder getOpenGroup(Long groupOrderId) {
        GroupOrder group = groupOrderRepository.findById(groupOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Group order not found"));
        if (!GroupOrder.STATUS_OPEN.equals(group.getStatus())) {
            throw new BusinessException("Group order is not open");
        }
        return group;
    }

    private void assertHost(GroupOrder group, Long hostUserId) {
        if (!group.getHostUserId().equals(hostUserId)) {
            throw new UnauthorizedException("Only the group host can perform this action");
        }
    }

    private Optional<AccountLookupService.UserSummary> findUserByPhone(String phone) {
        try {
            return accountLookupService.byPhoneNumber(phone);
        } catch (Exception ex) {
            log.warn("Phone lookup failed | error={}", ex.getMessage());
            return Optional.empty();
        }
    }

    private double resolveExpectedTotal(Long customerId) {
        try {
            java.math.BigDecimal subtotal = cartService.subtotal(customerId);
            if (subtotal != null) {
                return subtotal.doubleValue();
            }
        } catch (Exception ex) {
            log.warn("Could not resolve group expected total | error={}", ex.getMessage());
        }
        return 0.0;
    }

    private GroupOrderResponse toResponse(GroupOrder group) {
        List<GroupOrderMember> members = memberRepository.findByGroupOrderId(group.getId());
        List<GroupOrderResponse.MemberSplit> splits = new ArrayList<>();
        for (GroupOrderMember member : members) {
            splits.add(GroupOrderResponse.MemberSplit.builder()
                    .id(member.getId())
                    .userId(member.getCustomerId())
                    .invitePhone(member.getInvitePhone())
                    .status(member.getStatus())
                    .amountContribution(member.getAmountContribution())
                    .paid(member.getPaid())
                    .joinedAt(member.getJoinedAt())
                    .build());
        }
        return GroupOrderResponse.builder()
                .id(group.getId())
                .hostUserId(group.getHostUserId())
                .title(group.getTitle())
                .status(group.getStatus())
                .createdAt(group.getCreatedAt())
                .placedAt(group.getPlacedAt())
                .members(splits)
                .build();
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4) {
            return "****";
        }
        return phone.substring(0, Math.min(2, phone.length() - 4)) + "****"
                + phone.substring(phone.length() - 4);
    }
}
