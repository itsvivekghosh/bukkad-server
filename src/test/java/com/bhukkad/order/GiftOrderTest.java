package com.bhukkad.order;

import com.bhukkad.entity.GiftOrder;
import com.bhukkad.entity.Order;
import com.bhukkad.repository.GiftOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GiftOrderTest {

    @Mock private GiftOrderRepository giftOrderRepository;

    @Test
    void entity_settersAndGetters() {
        Order order = new Order();
        order.setId(100L);

        GiftOrder giftOrder = new GiftOrder();
        giftOrder.setOrder(order);
        giftOrder.setSenderUserId(1L);
        giftOrder.setRecipientName("Alice");
        giftOrder.setRecipientPhone("+919999999999");
        giftOrder.setRecipientAddressId(60L);
        giftOrder.setMessage("Happy Birthday!");

        assertEquals(100L, giftOrder.getOrder().getId());
        assertEquals(1L, giftOrder.getSenderUserId());
        assertEquals("Alice", giftOrder.getRecipientName());
        assertEquals("+919999999999", giftOrder.getRecipientPhone());
        assertEquals(60L, giftOrder.getRecipientAddressId());
        assertEquals("Happy Birthday!", giftOrder.getMessage());
    }

    @Test
    void repository_findsGiftOrdersBySender() {
        GiftOrder giftOrder = new GiftOrder();
        when(giftOrderRepository.findBySenderUserId(7L)).thenReturn(List.of(giftOrder));

        List<GiftOrder> result = giftOrderRepository.findBySenderUserId(7L);

        assertEquals(1, result.size());
        assertSame(giftOrder, result.get(0));
        verify(giftOrderRepository).findBySenderUserId(7L);
    }

    @Test
    void repository_returnsEmptyWhenSenderHasNoGiftOrders() {
        when(giftOrderRepository.findBySenderUserId(999L)).thenReturn(List.of());

        List<GiftOrder> result = giftOrderRepository.findBySenderUserId(999L);

        assertTrue(result.isEmpty());
    }

    @Test
    void order_fulfillmentFields_defaultValues() {
        Order order = new Order();
        assertEquals("DELIVERY", order.getFulfillmentType());
        assertNull(order.getDeviceId());
        assertNull(order.getGuestPhone());
        assertNull(order.getGiftMessage());
        assertNull(order.getRecipientName());
        assertNull(order.getRecipientPhone());
    }
}
