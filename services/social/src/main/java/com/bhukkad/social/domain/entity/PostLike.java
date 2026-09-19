package com.bhukkad.social.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Post like entity aligned with migration V16:
 * {@code id BIGSERIAL PRIMARY KEY} plus a unique constraint on
 * {@code (post_id, user_id)}.
 */
@Entity
@Table(name = "post_likes", uniqueConstraints = {
        @UniqueConstraint(name = "uq_post_likes_post_user", columnNames = {"post_id", "user_id"})
}, indexes = {
        @Index(name = "idx_post_likes_post_created", columnList = "post_id, created_at DESC")
})
@Getter
@Setter
public class PostLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long postId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
