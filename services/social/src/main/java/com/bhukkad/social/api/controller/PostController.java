package com.bhukkad.social.api.controller;

import com.bhukkad.common.ratelimit.RateLimited;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.social.api.dto.request.CommentRequest;
import com.bhukkad.social.api.dto.request.CreatePostRequest;
import com.bhukkad.social.api.dto.response.CommentResponse;
import com.bhukkad.social.api.dto.response.FeedResponse;
import com.bhukkad.social.api.dto.response.PostSummary;
import com.bhukkad.social.domain.service.SocialFeedService;
import com.bhukkad.social.domain.service.SocialLikeService;
import com.bhukkad.social.domain.service.SocialPostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class PostController {

    private final SocialPostService postService;
    private final SocialLikeService likeService;
    private final SocialFeedService feedService;

    @PostMapping("/api/v1/social/posts")
    @RateLimited(bucket = "social-create-post", limit = 30, windowSeconds = 60)
    public ResponseEntity<PostSummary> createPost(
            @Valid @RequestBody CreatePostRequest request,
            @AuthenticationPrincipal TokenPrincipal principal) {
        Long userId = principal == null ? null : principal.userId();
        PostSummary post = postService.createPost(userId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(post.id())
                .toUri();
        return ResponseEntity.created(location).body(post);
    }

    @GetMapping("/api/v1/social/posts/{id}")
    public ResponseEntity<PostSummary> getPost(@PathVariable Long id) {
        return ResponseEntity.ok(postService.getPost(id));
    }

    @DeleteMapping("/api/v1/social/posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePost(@PathVariable Long id, @AuthenticationPrincipal TokenPrincipal principal) {
        Long userId = principal == null ? null : principal.userId();
        postService.deletePost(id, userId);
    }

    @GetMapping("/api/v1/social/posts/restaurant/{restaurantId}")
    public ResponseEntity<List<PostSummary>> getRestaurantPosts(
            @PathVariable Long restaurantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(postService.getRestaurantPosts(restaurantId, page, size));
    }

    @GetMapping("/api/v1/social/posts/user/{userId}")
    public ResponseEntity<List<PostSummary>> getUserPosts(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(postService.getUserPosts(userId, page, size));
    }

    // --- Likes ---

    @PostMapping("/api/v1/social/posts/{postId}/like")
    @RateLimited(bucket = "social-like", limit = 300, windowSeconds = 60)
    public ResponseEntity<PostSummary> likePost(
            @PathVariable Long postId,
            @AuthenticationPrincipal TokenPrincipal principal) {
        Long userId = principal == null ? null : principal.userId();
        return ResponseEntity.ok(likeService.toggleLike(postId, userId));
    }

    @DeleteMapping("/api/v1/social/posts/{postId}/like")
    @RateLimited(bucket = "social-unlike", limit = 300, windowSeconds = 60)
    public ResponseEntity<PostSummary> unlikePost(
            @PathVariable Long postId,
            @AuthenticationPrincipal TokenPrincipal principal) {
        Long userId = principal == null ? null : principal.userId();
        return ResponseEntity.ok(likeService.toggleLike(postId, userId));
    }

    // --- Comments ---

    @PostMapping("/api/v1/social/posts/{postId}/comments")
    @RateLimited(bucket = "social-comment", limit = 60, windowSeconds = 60)
    public ResponseEntity<PostSummary> commentOnPost(
            @PathVariable Long postId,
            @Valid @RequestBody CommentRequest request,
            @AuthenticationPrincipal TokenPrincipal principal) {
        Long userId = principal == null ? null : principal.userId();
        return ResponseEntity.ok(postService.addComment(postId, userId, request));
    }

    @GetMapping("/api/v1/social/posts/{postId}/comments")
    public ResponseEntity<List<CommentResponse>> getComments(@PathVariable Long postId) {
        return ResponseEntity.ok(postService.getComments(postId));
    }

    // --- Feed ---

    @GetMapping("/api/v1/social/feed/nearby")
    @RateLimited(bucket = "social-feed", limit = 200, windowSeconds = 60)
    public ResponseEntity<FeedResponse> nearbyFeed(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "5") double radiusKm,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(feedService.getNearbyFeed(lat, lng, radiusKm, cursor, size));
    }
}
