package com.bhukkad.social.api.dto.response;

/**
 * Feed response wrapper.
 */
public record FeedResponse(
        java.util.List<PostSummary> posts,
        String nextCursor,
        boolean hasMore
) {
    public static FeedResponse of(java.util.List<PostSummary> posts, String nextCursor, boolean hasMore) {
        return new FeedResponse(posts, nextCursor, hasMore);
    }

    public static FeedResponse empty() {
        return new FeedResponse(java.util.List.of(), null, false);
    }
}
