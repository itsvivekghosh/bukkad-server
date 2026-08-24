package com.bhukkad.entity;

import com.bhukkad.repository.GroupOrderMemberRepository;
import com.bhukkad.repository.GroupOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupOrderTest {

    @Mock private GroupOrderRepository groupOrderRepository;
    @Mock private GroupOrderMemberRepository memberRepository;

    @Test
    void groupOrder_settersAndGetters() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 23, 12, 0);
        LocalDateTime placedAt = createdAt.plusHours(1);

        GroupOrder groupOrder = new GroupOrder();
        groupOrder.setId(1L);
        groupOrder.setHostUserId(10L);
        groupOrder.setTitle("Team lunch");
        groupOrder.setStatus(GroupOrder.GroupOrderStatus.OPEN);
        groupOrder.setCreatedAt(createdAt);
        groupOrder.setPlacedAt(placedAt);

        assertEquals(1L, groupOrder.getId());
        assertEquals(10L, groupOrder.getHostUserId());
        assertEquals("Team lunch", groupOrder.getTitle());
        assertEquals(GroupOrder.GroupOrderStatus.OPEN, groupOrder.getStatus());
        assertEquals(createdAt, groupOrder.getCreatedAt());
        assertEquals(placedAt, groupOrder.getPlacedAt());
    }

    @Test
    void groupOrder_defaultStatusIsOpen() {
        GroupOrder groupOrder = new GroupOrder();
        assertEquals(GroupOrder.GroupOrderStatus.OPEN, groupOrder.getStatus());
        assertNull(groupOrder.getPlacedAt());
    }

    @Test
    void groupOrderMember_settersAndGetters() {
        GroupOrder groupOrder = new GroupOrder();
        groupOrder.setId(1L);
        LocalDateTime joinedAt = LocalDateTime.now();

        GroupOrderMember member = new GroupOrderMember();
        member.setId(7L);
        member.setGroupOrder(groupOrder);
        member.setUserId(20L);
        member.setInvitePhone("+919876543210");
        member.setStatus(GroupOrderMember.MemberStatus.JOINED);
        member.setAmountContribution(250.5);
        member.setPaid(true);
        member.setJoinedAt(joinedAt);

        assertEquals(7L, member.getId());
        assertEquals(1L, member.getGroupOrder().getId());
        assertEquals(20L, member.getUserId());
        assertEquals("+919876543210", member.getInvitePhone());
        assertEquals(GroupOrderMember.MemberStatus.JOINED, member.getStatus());
        assertEquals(Double.valueOf(250.5), member.getAmountContribution());
        assertEquals(Boolean.TRUE, member.getPaid());
        assertEquals(joinedAt, member.getJoinedAt());
    }

    @Test
    void groupOrderMember_defaults() {
        GroupOrderMember member = new GroupOrderMember();
        assertEquals(GroupOrderMember.MemberStatus.INVITED, member.getStatus());
        assertEquals(Boolean.FALSE, member.getPaid());
        assertNull(member.getAmountContribution());
        assertNull(member.getJoinedAt());
    }

    @Test
    void repository_findsOpenGroupsByHost() {
        GroupOrder groupOrder = new GroupOrder();
        groupOrder.setHostUserId(7L);
        when(groupOrderRepository.findOpenByHostUserId(7L)).thenReturn(List.of(groupOrder));

        List<GroupOrder> result = groupOrderRepository.findOpenByHostUserId(7L);

        assertEquals(1, result.size());
        assertSame(groupOrder, result.get(0));
        verify(groupOrderRepository).findOpenByHostUserId(7L);
    }

    @Test
    void repository_findsByStatus() {
        GroupOrder groupOrder = new GroupOrder();
        when(groupOrderRepository.findByStatus(GroupOrder.GroupOrderStatus.OPEN))
                .thenReturn(List.of(groupOrder));

        List<GroupOrder> result = groupOrderRepository.findByStatus(GroupOrder.GroupOrderStatus.OPEN);

        assertEquals(1, result.size());
        assertSame(groupOrder, result.get(0));
    }

    @Test
    void repository_findsMemberByGroupAndUser() {
        GroupOrderMember member = new GroupOrderMember();
        member.setUserId(20L);
        when(memberRepository.findByGroupOrderIdAndUserId(5L, 20L)).thenReturn(Optional.of(member));

        Optional<GroupOrderMember> result = memberRepository.findByGroupOrderIdAndUserId(5L, 20L);

        assertTrue(result.isPresent());
        assertSame(member, result.get());
        verify(memberRepository).findByGroupOrderIdAndUserId(5L, 20L);
    }

    @Test
    void repository_returnsEmptyWhenUserNotInGroup() {
        when(memberRepository.findByGroupOrderIdAndUserId(5L, 99L)).thenReturn(Optional.empty());

        Optional<GroupOrderMember> result = memberRepository.findByGroupOrderIdAndUserId(5L, 99L);

        assertTrue(result.isEmpty());
    }

    @Test
    void repository_countsMembersByGroupAndStatus() {
        when(memberRepository.countByGroupOrderIdAndStatus(5L, GroupOrderMember.MemberStatus.JOINED))
                .thenReturn(2L);

        long count = memberRepository.countByGroupOrderIdAndStatus(5L, GroupOrderMember.MemberStatus.JOINED);

        assertEquals(2L, count);
        verify(memberRepository).countByGroupOrderIdAndStatus(5L, GroupOrderMember.MemberStatus.JOINED);
    }
}