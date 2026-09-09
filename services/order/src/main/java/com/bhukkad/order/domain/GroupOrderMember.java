package com.bhukkad.order.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "group_order_members", indexes = {
        @Index(name = "uk_group_member", columnList = "groupOrderId, customerId", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class GroupOrderMember {

    public static final String STATUS_INVITED = "INVITED";
    public static final String STATUS_JOINED = "JOINED";
    public static final String STATUS_DECLINED = "DECLINED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long groupOrderId;
    @Column(nullable = false)
    private Long customerId;
    @Column(length = 15)
    private String invitePhone;
    @Column(nullable = false, length = 20)
    private String status;
    @Column
    private Double amountContribution;
    @Column(nullable = false)
    private Boolean paid = false;
    @Column
    private LocalDateTime joinedAt;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
