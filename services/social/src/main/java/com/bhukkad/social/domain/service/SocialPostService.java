package com.bhukkad.social.domain.service;

import com.bhukkad.common.datasource.UseReadReplica;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.social.api.dto.request.CommentRequest;
import com.bhukkad.social.api.dto.request.CreatePostRequest;
import com.bhukkad.social.api.dto.response.CommentResponse;
import com.bhukkad.social.api.dto.response.PostSummary;
import com.bhukkad.social.domain.entity.PostComment;
import com.bhukkad.social.domain.entity.SocialPost;
import com.bhukkad.social.domain.repository.PostCommentRepository;
import com.bhukkad.social.domain.repository.PostLikeRepository;
import com.bhukkad.social.domain.repository.SocialPostRepository;
import com.bhukkad.social.event.PostCreatedEvent;
import com.bhukkad.social.event.PostDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Social post service optimized for geospatial operations with PostGIS.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SocialPostService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SocialPostRepository postRepository;
    private final PostLikeRepository likeRepository;
    private final PostCommentRepository commentRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public PostSummary createPost(Long userId, CreatePostRequest request) {
        SocialPost post = new SocialPost();
        post.setRestaurantId(request.restaurantId());
        post.setAuthorId(userId);
        post.setContent(request.content());
        post.setMediaUrls(request.mediaUrls() != null ? request.mediaUrls() : new String[]{});
        post.setPostType(request.postType() != null ? request.postType() : "update");
        post.setStatus(SocialPost.STATUS_ACTIVE);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setLatitude(request.latitude());
        post.setLongitude(request.longitude());

        SocialPost saved = postRepository.save(post);

        // Publish event for cache invalidation
        eventPublisher.publishEvent(new PostCreatedEvent(
                saved.getId(),
                saved.getLatitude() != null ? saved.getLatitude() : 0.0,
                saved.getLongitude() != null ? saved.getLongitude() : 0.0,
                saved.getRestaurantId()
        ));

        return toSummary(saved);
    }

    @UseReadReplica
    @Transactional(readOnly = true)
    public PostSummary getPost(Long postId) {
        SocialPost post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found: " + postId));
        if (!SocialPost.STATUS_ACTIVE.equals(post.getStatus()) || post.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Post not found: " + postId);
        }
        return toSummary(post);
    }

    @Transactional
    public void deletePost(Long postId, Long userId) {
        SocialPost post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found: " + postId));
        if (!post.getAuthorId().equals(userId)) {
            throw new BusinessException("Not authorized to delete this post");
        }
        post.setStatus(SocialPost.STATUS_DELETED);
        post.setDeletedAt(LocalDateTime.now());
        postRepository.save(post);

        // Publish event for cache invalidation
        eventPublisher.publishEvent(new PostDeletedEvent(
                postId,
                post.getLatitude() != null ? post.getLatitude() : 0.0,
                post.getLongitude() != null ? post.getLongitude() : 0.0,
                post.getRestaurantId()
        ));
    }

    @UseReadReplica
    @Transactional(readOnly = true)
    public List<PostSummary> getRestaurantPosts(Long restaurantId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(safePage, safeSize);
        List<SocialPost> posts = postRepository.findByRestaurantIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                restaurantId, SocialPost.STATUS_ACTIVE, pageable);
        return posts.stream()
                .map(this::toSummary)
                .toList();
    }

    @UseReadReplica
    @Transactional(readOnly = true)
    public List<PostSummary> getUserPosts(Long userId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(safePage, safeSize);
        List<SocialPost> posts = postRepository.findByAuthorIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                userId, SocialPost.STATUS_ACTIVE, pageable);
        return posts.stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public PostSummary addComment(Long postId, Long userId, CommentRequest request) {
        SocialPost post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found: " + postId));

        PostComment comment = new PostComment();
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setParentCommentId(request.parentCommentId());
        comment.setContent(request.content());

        commentRepository.save(comment);

        post.setCommentCount(post.getCommentCount() + 1);
        postRepository.save(post);

        return toSummary(post);
    }

    @UseReadReplica
    @Transactional(readOnly = true)
    public List<CommentResponse> getComments(Long postId) {
        return commentRepository.findByPostIdAndParentCommentIdIsNullOrderByCreatedAtDesc(postId)
                .stream()
                .map(c -> new CommentResponse(
                        c.getId(), c.getPostId(), c.getUserId(),
                        c.getParentCommentId(), c.getContent(), c.getCreatedAt()))
                .toList();
    }

    private PostSummary toSummary(SocialPost post) {
        return new PostSummary(
                post.getId(),
                post.getRestaurantId(),
                post.getRestaurantName(),
                post.getAuthorId(),
                post.getAuthorName(),
                post.getContent(),
                post.getMediaUrls(),
                post.getPostType(),
                post.getLikeCount(),
                post.getCommentCount(),
                post.getStatus(),
                post.getCreatedAt()
        );
    }
}