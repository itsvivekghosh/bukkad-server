package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "group_orders", indexes = {
        @Index(name = "idx_group_host", columnList = "host_user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"members"})
@EqualsAndHashCode(exclude = {"members"})
@EntityListeners(AuditingEntityListener.class)
public class GroupOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "host_user_id", nullable = false)
    private Long hostUserId;

    @Column(length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupOrderStatus status = GroupOrderStatus.OPEN;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime placedAt;

    @OneToMany(mappedBy = "groupOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GroupOrderMember> members = new ArrayList<>();

    public enum GroupOrderStatus {
        OPEN, PLACED, CANCELLED
    }
}