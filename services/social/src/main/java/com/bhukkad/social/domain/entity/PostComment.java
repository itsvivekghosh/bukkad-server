package com.bhukkad.social.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Post comment entity.
 *
 * <p>Matches migration V3 which defines {@code id BIGSERIAL PRIMARY KEY}
 * and a separate {@code post_id} column.</p>
 */
@Entity
@Table(name = "post_comments", indexes = {
        @Index(name = "idx_post_comments_post_created", columnList = "post_id, created_at DESC")
})
@Getter
@Setter
public class PostComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(nullable = false)
    private Long userId;

    @Column
    private Long parentCommentId;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
