package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

@Entity
@Table(name = "group_order_members", indexes = {
        @Index(name = "idx_group_member_user", columnList = "user_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_group_member", columnNames = {"group_order_id", "user_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"groupOrder"})
@EqualsAndHashCode(exclude = {"groupOrder"})
public class GroupOrderMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_order_id", nullable = false)
    private GroupOrder groupOrder;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "invite_phone", length = 15)
    private String invitePhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status = MemberStatus.INVITED;

    @Column(name = "amount_contribution")
    private Double amountContribution;

    @Column(nullable = false)
    private Boolean paid = false;

    private LocalDateTime joinedAt;

    public enum MemberStatus {
        INVITED, JOINED, DECLINED
    }
}