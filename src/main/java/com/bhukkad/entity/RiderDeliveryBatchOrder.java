package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Links an order to a rider delivery batch with route sequence. */
@Entity
@Table(name = "rider_delivery_batch_orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@IdClass(RiderDeliveryBatchOrderId.class)
public class RiderDeliveryBatchOrder {

    @Id
    @Column(name = "batch_id")
    private Long batchId;

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", insertable = false, updatable = false)
    private RiderDeliveryBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", insertable = false, updatable = false)
    private Order order;

    @Column(nullable = false)
    private Integer sequenceNumber;
}
