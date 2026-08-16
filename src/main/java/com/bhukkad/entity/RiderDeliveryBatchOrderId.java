package com.bhukkad.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiderDeliveryBatchOrderId implements Serializable {
    private Long batchId;
    private Long orderId;
}
