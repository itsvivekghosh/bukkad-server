package com.bhukkad.dto.request;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Gift order placement request. Extends the standard order request so the
 * shared single-order creation pipeline is reused: the sender pays now and the
 * order is delivered to the recipient with an optional gift message.
 *
 * <p>When {@link #recipientAddressId} is set it is used as the delivery address;
 * it must still belong to the authenticated sender (the sender saves the
 * recipient's address in their own address book). Falls back to
 * {@link #getDeliveryAddressId()} when not provided.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class GiftOrderRequest extends OrderRequest {

    private String recipientName;

    private String recipientPhone;

    private Long recipientAddressId;

    private String giftMessage;
}
