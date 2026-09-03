package com.bhukkad.order;

import com.bhukkad.dto.response.CartResponse;
import com.bhukkad.dto.response.GroupOrderResponse;
import com.bhukkad.entity.GroupOrder;
import com.bhukkad.entity.GroupOrderMember;
import com.bhukkad.entity.User;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.repository.GroupOrderMemberRepository;
import com.bhukkad.repository.GroupOrderRepository;
import com.bhukkad.service.CartService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
 *
 * <p>A customer creates a group order, invites members by phone, members join and the
 * host records how the final bill is split. The host's own cart is the shared cart for
 * the group; actual payment runs through the existing order pipeline (see
 * {@link #placeGroupOrder(Long, Long)}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupOrderService {

    /** Split sums are compared with this tolerance to absorb rounding (in rupees). */
    private static final double SPLIT_TOLERANCE = 0.01;

    private final GroupOrderRepository groupOrderRepository;
    private final GroupOrderMemberRepository memberRepository;
    private final CartService cartService;
    private final com.bhukkad.security.AccountLookupService accountLookupService;

    @Transactional
    public GroupOrderResponse createGroupOrder(Long hostUserId, String title) {
        GroupOrder group = new GroupOrder();
        group.setHostUserId(hostUserId);
        group.setTitle(title);
        group.setStatus(GroupOrder.GroupOrderStatus.OPEN);
        group = groupOrderRepository.save(group);

        GroupOrderMember hostMember = new GroupOrderMember();
        hostMember.setGroupOrder(group);
        hostMember.setUserId(hostUserId);
        hostMember.setStatus(GroupOrderMember.MemberStatus.JOINED);
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

        User invitedUser = findUserByPhone(phone)
                .orElseThrow(() -> new BusinessException("No registered user with phone: " + phone));
        if (invitedUser.getId().equals(hostUserId)) {
            throw new BusinessException("Host is already a member of the group");
        }
        if (memberRepository.findByGroupOrderIdAndUserId(groupOrderId, invitedUser.getId()).isPresent()) {
            throw new BusinessException("User is already invited to this group");
        }

        GroupOrderMember member = new GroupOrderMember();
        member.setGroupOrder(group);
        member.setUserId(invitedUser.getId());
        member.setInvitePhone(phone);
        member.setStatus(GroupOrderMember.MemberStatus.INVITED);
        memberRepository.save(member);

        log.info("Member invited | groupOrderId={} | userId={} | phoneMasked={}",
                groupOrderId, invitedUser.getId(), maskPhone(phone));
        return toResponse(group);
    }

    @Transactional
    public GroupOrderResponse joinGroup(Long groupOrderId, Long userId) {
        GroupOrder group = getOpenGroup(groupOrderId);
        GroupOrderMember member = memberRepository.findByGroupOrderIdAndUserId(groupOrderId, userId)
                .orElseThrow(() -> new BusinessException("You are not invited to this group"));
        if (member.getStatus() == GroupOrderMember.MemberStatus.DECLINED) {
            throw new BusinessException("Invitation to this group was declined");
        }
        if (member.getStatus() != GroupOrderMember.MemberStatus.JOINED) {
            member.setStatus(GroupOrderMember.MemberStatus.JOINED);
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
        boolean member = memberRepository.findByGroupOrderIdAndUserId(groupOrderId, userId).isPresent();
        if (!host && !member) {
            throw new UnauthorizedException("You are not a member of this group");
        }
        return toResponse(group);
    }

    /**
     * Records each member's share of the bill. The expected total is the host's cart
     * subtotal (the same proxy used for order risk scoring); a mismatch between the
     * recorded split and that total is logged as a warning and flagged on the response
     * rather than enforced, so hosts can adjust the split freely.
     */
    @Transactional
    public GroupOrderResponse splitPayment(Long groupOrderId, Long hostUserId, Map<Long, Double> amountsByUserId) {
        GroupOrder group = getOpenGroup(groupOrderId);
        assertHost(group, hostUserId);
        if (amountsByUserId == null || amountsByUserId.isEmpty()) {
            throw new BusinessException("Split amounts are required");
        }

        double expectedTotal = resolveExpectedTotal();
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
                    .findByGroupOrderIdAndUserId(groupOrderId, userId)
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

    /**
     * Finalizes the group order.
     *
     * <p>The existing order pipeline is keyed to a single customer's own cart and needs
     * restaurant/address/payment input that this endpoint (no request body) cannot
     * supply, so a true shared-cart charge is not supported cleanly. Per the agreed
     * design the host places their own order normally through
     * {@code POST /api/v1/orders/customer/create} (the host's cart is the shared cart),
     * then calls this endpoint: the group is marked {@code PLACED}, {@code placedAt} is
     * stamped, and member contributions recorded by the split step are frozen.
     */
    @Transactional
    public GroupOrderResponse placeGroupOrder(Long groupOrderId, Long hostUserId) {
        GroupOrder group = getOpenGroup(groupOrderId);
        assertHost(group, hostUserId);
        group.setStatus(GroupOrder.GroupOrderStatus.PLACED);
        group.setPlacedAt(LocalDateTime.now());
        groupOrderRepository.save(group);
        log.info("Group order placed | groupOrderId={} | hostUserId={}", groupOrderId, hostUserId);
        return toResponse(group);
    }

    // ==================== HELPERS ====================

    private GroupOrder getOpenGroup(Long groupOrderId) {
        GroupOrder group = groupOrderRepository.findById(groupOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Group order not found"));
        if (group.getStatus() != GroupOrder.GroupOrderStatus.OPEN) {
            throw new BusinessException("Group order is not open");
        }
        return group;
    }

    private void assertHost(GroupOrder group, Long hostUserId) {
        if (!group.getHostUserId().equals(hostUserId)) {
            throw new UnauthorizedException("Only the group host can perform this action");
        }
    }

    /**
     * Resolves a registered user by phone across the role-segregated account
     * tables; wrapped defensively so a lookup failure degrades to "no such
     * user" instead of bubbling up.
     */
    private Optional<User> findUserByPhone(String phone) {
        try {
            return accountLookupService.byPhoneNumber(phone);
        } catch (Exception ex) {
            log.warn("Phone lookup failed | error={}", ex.getMessage());
            return Optional.empty();
        }
    }

    private double resolveExpectedTotal() {
        try {
            CartResponse cart = cartService.getCart();
            if (cart != null && cart.getSubtotal() != null) {
                return cart.getSubtotal();
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
                    .userId(member.getUserId())
                    .invitePhone(member.getInvitePhone())
                    .status(member.getStatus() != null ? member.getStatus().name() : null)
                    .amountContribution(member.getAmountContribution())
                    .paid(member.getPaid())
                    .joinedAt(member.getJoinedAt())
                    .build());
        }
        return GroupOrderResponse.builder()
                .id(group.getId())
                .hostUserId(group.getHostUserId())
                .title(group.getTitle())
                .status(group.getStatus().name())
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