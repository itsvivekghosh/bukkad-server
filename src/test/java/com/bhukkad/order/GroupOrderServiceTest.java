package com.bhukkad.order;

import com.bhukkad.dto.response.CartResponse;
import com.bhukkad.dto.response.GroupOrderResponse;
import com.bhukkad.entity.GroupOrder;
import com.bhukkad.entity.GroupOrderMember;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.GroupOrderMemberRepository;
import com.bhukkad.repository.GroupOrderRepository;
import com.bhukkad.service.CartService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupOrderServiceTest {

    @Mock private GroupOrderRepository groupOrderRepository;
    @Mock private GroupOrderMemberRepository memberRepository;
    @Mock private CartService cartService;
    @Mock private EntityManager entityManager;
    @Mock private TypedQuery<User> phoneQuery;

    private GroupOrderService service;

    /** In-memory stand-in for {@code group_order_members} so responses are observable. */
    private final List<GroupOrderMember> members = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(groupOrderRepository.save(any(GroupOrder.class))).thenAnswer(inv -> {
            GroupOrder group = inv.getArgument(0);
            if (group.getId() == null) {
                group.setId(1L);
            }
            return group;
        });
        lenient().when(memberRepository.save(any(GroupOrderMember.class))).thenAnswer(inv -> {
            GroupOrderMember member = inv.getArgument(0);
            members.removeIf(existing -> sameMember(existing, member));
            members.add(member);
            return member;
        });
        lenient().when(memberRepository.findByGroupOrderId(anyLong())).thenAnswer(inv -> {
            Long groupId = inv.getArgument(0);
            return members.stream()
                    .filter(m -> Objects.equals(groupIdOf(m), groupId))
                    .collect(Collectors.toList());
        });
        lenient().when(memberRepository.findByGroupOrderIdAndUserId(anyLong(), anyLong())).thenAnswer(inv -> {
            Long groupId = inv.getArgument(0);
            Long userId = inv.getArgument(1);
            return members.stream()
                    .filter(m -> Objects.equals(groupIdOf(m), groupId))
                    .filter(m -> Objects.equals(m.getUserId(), userId))
                    .findFirst();
        });
        service = new GroupOrderService(groupOrderRepository, memberRepository, cartService);
        injectEntityManager();
    }

    private void injectEntityManager() {
        try {
            Field emField = GroupOrderService.class.getDeclaredField("entityManager");
            emField.setAccessible(true);
            emField.set(service, entityManager);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new IllegalStateException("Cannot inject EntityManager mock", ex);
        }
    }

    @Test
    void createGroupOrder_createsOpenGroupWithHostAsJoinedMember() {
        GroupOrderResponse response = service.createGroupOrder(10L, "Friday lunch");

        assertEquals("OPEN", response.getStatus());
        assertEquals(10L, response.getHostUserId());
        assertEquals("Friday lunch", response.getTitle());
        assertEquals(1, response.getMembers().size());
        assertEquals(10L, response.getMembers().get(0).getUserId());
        assertEquals("JOINED", response.getMembers().get(0).getStatus());
        assertNotNull(response.getMembers().get(0).getJoinedAt());
        verify(groupOrderRepository).save(any(GroupOrder.class));
        verify(memberRepository).save(any(GroupOrderMember.class));
    }

    @Test
    void inviteMember_nonHost_throws() {
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(openGroup(5L, 10L)));

        assertThrows(UnauthorizedException.class,
                () -> service.inviteMember(5L, 99L, "+919876543210"));
    }

    @Test
    void inviteMember_groupNotOpen_throws() {
        GroupOrder group = openGroup(5L, 10L);
        group.setStatus(GroupOrder.GroupOrderStatus.PLACED);
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(group));

        assertThrows(BusinessException.class,
                () -> service.inviteMember(5L, 10L, "+919876543210"));
    }

    @Test
    void inviteMember_unregisteredPhone_throws() {
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(openGroup(5L, 10L)));
        when(entityManager.createQuery(anyString(), eq(User.class))).thenReturn(phoneQuery);
        when(phoneQuery.setParameter(eq("phone"), anyString())).thenReturn(phoneQuery);
        when(phoneQuery.getResultList()).thenReturn(List.of());

        assertThrows(BusinessException.class,
                () -> service.inviteMember(5L, 10L, "+919876543210"));
    }

    @Test
    void inviteMember_success_createsInvitedMember() {
        User invited = new User();
        invited.setId(20L);
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(openGroup(5L, 10L)));
        when(entityManager.createQuery(anyString(), eq(User.class))).thenReturn(phoneQuery);
        when(phoneQuery.setParameter(eq("phone"), anyString())).thenReturn(phoneQuery);
        when(phoneQuery.getResultList()).thenReturn(List.of(invited));

        GroupOrderResponse response = service.inviteMember(5L, 10L, "+919876543210");

        assertEquals(1, response.getMembers().size());
        assertEquals(20L, response.getMembers().get(0).getUserId());
        assertEquals("INVITED", response.getMembers().get(0).getStatus());
        assertEquals("+919876543210", response.getMembers().get(0).getInvitePhone());
    }

    @Test
    void inviteMember_hostPhone_throws() {
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(openGroup(5L, 10L)));
        when(entityManager.createQuery(anyString(), eq(User.class))).thenReturn(phoneQuery);
        when(phoneQuery.setParameter(eq("phone"), anyString())).thenReturn(phoneQuery);
        when(phoneQuery.getResultList()).thenReturn(List.of(selfUser(10L)));

        assertThrows(BusinessException.class,
                () -> service.inviteMember(5L, 10L, "+919876543210"));
    }

    @Test
    void joinGroup_success_marksJoined() {
        GroupOrder group = openGroup(5L, 10L);
        members.add(invitedMember(group, 20L));
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(group));

        GroupOrderResponse response = service.joinGroup(5L, 20L);

        assertEquals("JOINED", response.getMembers().get(0).getStatus());
        assertNotNull(response.getMembers().get(0).getJoinedAt());
    }

    @Test
    void joinGroup_notInvited_throws() {
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(openGroup(5L, 10L)));

        assertThrows(BusinessException.class, () -> service.joinGroup(5L, 99L));
    }

    @Test
    void getGroup_memberCanView() {
        GroupOrder group = openGroup(5L, 10L);
        members.add(invitedMember(group, 20L));
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(group));

        GroupOrderResponse response = service.getGroup(5L, 20L);

        assertNotNull(response);
        assertEquals(5L, response.getId());
    }

    @Test
    void getGroup_nonMember_throws() {
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(openGroup(5L, 10L)));

        assertThrows(UnauthorizedException.class, () -> service.getGroup(5L, 99L));
    }

    @Test
    void splitPayment_recordsContributionsForMembers() {
        GroupOrder group = openGroup(5L, 10L);
        members.add(joinedMember(group, 10L));
        members.add(joinedMember(group, 20L));
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(group));
        CartResponse cart = new CartResponse();
        cart.setSubtotal(300.0);
        when(cartService.getCart()).thenReturn(cart);

        GroupOrderResponse response = service.splitPayment(5L, 10L, Map.of(10L, 150.0, 20L, 150.0));

        assertEquals(2, response.getMembers().size());
        assertTrue(response.getMembers().stream().allMatch(m -> Double.valueOf(150.0).equals(m.getAmountContribution())));
        assertTrue(response.getMembers().stream().allMatch(m -> Boolean.TRUE.equals(m.getPaid())));
    }

    @Test
    void splitPayment_skipsNonMembers_andRecordsRemaining() {
        GroupOrder group = openGroup(5L, 10L);
        members.add(joinedMember(group, 10L));
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(group));
        CartResponse cart = new CartResponse();
        cart.setSubtotal(100.0);
        when(cartService.getCart()).thenReturn(cart);

        GroupOrderResponse response = service.splitPayment(5L, 10L, Map.of(10L, 100.0, 99L, 50.0));

        assertEquals(1, response.getMembers().size());
        assertEquals(Double.valueOf(100.0), response.getMembers().get(0).getAmountContribution());
    }

    @Test
    void splitPayment_emptyShares_throws() {
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(openGroup(5L, 10L)));

        assertThrows(BusinessException.class, () -> service.splitPayment(5L, 10L, Map.of()));
    }

    @Test
    void splitPayment_nonHost_throws() {
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(openGroup(5L, 10L)));

        assertThrows(UnauthorizedException.class,
                () -> service.splitPayment(5L, 99L, Map.of(10L, 100.0)));
    }

    @Test
    void placeGroupOrder_marksGroupPlaced() {
        GroupOrder group = openGroup(5L, 10L);
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(group));

        GroupOrderResponse response = service.placeGroupOrder(5L, 10L);

        assertEquals("PLACED", response.getStatus());
        assertNotNull(response.getPlacedAt());
        verify(groupOrderRepository).save(group);
    }

    @Test
    void placeGroupOrder_nonHost_throws() {
        when(groupOrderRepository.findById(5L)).thenReturn(Optional.of(openGroup(5L, 10L)));

        assertThrows(UnauthorizedException.class, () -> service.placeGroupOrder(5L, 99L));
    }

    private GroupOrder openGroup(Long id, Long hostUserId) {
        GroupOrder group = new GroupOrder();
        group.setId(id);
        group.setHostUserId(hostUserId);
        group.setTitle("Friday lunch");
        group.setStatus(GroupOrder.GroupOrderStatus.OPEN);
        return group;
    }

    private GroupOrderMember invitedMember(GroupOrder group, Long userId) {
        GroupOrderMember member = new GroupOrderMember();
        member.setGroupOrder(group);
        member.setUserId(userId);
        member.setInvitePhone("+919876543210");
        member.setStatus(GroupOrderMember.MemberStatus.INVITED);
        return member;
    }

    private GroupOrderMember joinedMember(GroupOrder group, Long userId) {
        GroupOrderMember member = invitedMember(group, userId);
        member.setStatus(GroupOrderMember.MemberStatus.JOINED);
        return member;
    }

    private User selfUser(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private Long groupIdOf(GroupOrderMember member) {
        return member.getGroupOrder() != null ? member.getGroupOrder().getId() : null;
    }

    private boolean sameMember(GroupOrderMember a, GroupOrderMember b) {
        return Objects.equals(groupIdOf(a), groupIdOf(b)) && Objects.equals(a.getUserId(), b.getUserId());
    }
}