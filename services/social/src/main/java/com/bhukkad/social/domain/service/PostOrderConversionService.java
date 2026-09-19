package com.bhukkad.social.domain.service;

import com.bhukkad.social.api.dto.request.OrderFromPostRequest;
import com.bhukkad.social.domain.entity.PostOrderConversion;
import com.bhukkad.social.domain.entity.SocialPost;
import com.bhukkad.social.domain.repository.PostOrderConversionRepository;
import com.bhukkad.social.domain.repository.SocialPostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Records and queries post-to-order conversions for analytics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PostOrderConversionService {

    private final PostOrderConversionRepository conversionRepository;
    private final SocialPostRepository socialPostRepository;

    /**
     * Record a conversion from a social post to an order.
     */
    @Transactional
    public PostOrderConversion recordConversion(Long postId, Long orderId, Long userId,
                                                Long restaurantId, BigDecimal totalAmount,
                                                List<OrderFromPostRequest.OrderItemRequest> items) {
        SocialPost post = socialPostRepository.findById(postId).orElse(null);

        PostOrderConversion conversion = new PostOrderConversion();
        conversion.setPostId(postId);
        conversion.setOrderId(orderId);
        conversion.setUserId(userId);
        conversion.setRestaurantId(restaurantId);
        conversion.setItemsCount(items != null ? items.size() : 0);
        conversion.setTotalAmount(totalAmount != null ? totalAmount : BigDecimal.ZERO);

        if (post != null) {
            conversion.setPostAuthorId(post.getAuthorId());
            conversion.setPostContent(post.getContent());
            conversion.setPostMediaUrls(post.getMediaUrls());
            conversion.setPostPostType(post.getPostType());
        }

        PostOrderConversion saved = conversionRepository.save(conversion);
        log.debug("POST_ORDER_CONVERSION_RECORDED postId={} orderId={} restaurantId={}",
                postId, orderId, restaurantId);
        return saved;
    }
}
