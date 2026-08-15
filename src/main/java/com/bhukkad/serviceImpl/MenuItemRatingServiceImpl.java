package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.MenuItemRatingRequest;
import com.bhukkad.dto.response.MenuItemRatingResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.MenuItemRating;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderItem;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.MenuItemRatingRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.MenuItemRatingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuItemRatingServiceImpl implements MenuItemRatingService {

    private final MenuItemRatingRepository menuItemRatingRepository;
    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final MenuItemRepository menuItemRepository;
    private final SecurityUtils securityUtils;

    @Override
    @Transactional
    public MenuItemRatingResponse rateMenuItem(MenuItemRatingRequest request) {
        Long customerId = securityUtils.getCurrentUserId();
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        Order order = orderRepository.findByIdWithDetails(request.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getCustomer().getId().equals(customerId)) {
            throw new BusinessException("You can only rate items from your own orders");
        }
        if (order.getStatus() != Order.OrderStatus.DELIVERED) {
            throw new BusinessException("You can only rate items from delivered orders");
        }

        boolean orderContainsItem = order.getOrderItems().stream()
                .map(OrderItem::getMenuItem)
                .map(MenuItem::getId)
                .anyMatch(id -> id.equals(request.getMenuItemId()));
        if (!orderContainsItem) {
            throw new BusinessException("Menu item was not part of this order");
        }

        if (menuItemRatingRepository.findByOrderIdAndMenuItemId(request.getOrderId(), request.getMenuItemId())
                .isPresent()) {
            throw new BusinessException("You have already rated this item for this order");
        }

        MenuItem menuItem = menuItemRepository.findById(request.getMenuItemId())
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

        MenuItemRating rating = new MenuItemRating();
        rating.setCustomer(customer);
        rating.setMenuItem(menuItem);
        rating.setOrder(order);
        rating.setRating(request.getRating());
        rating.setComment(request.getComment());
        rating = menuItemRatingRepository.save(rating);

        updateMenuItemAverageRating(menuItem.getId());

        return toResponse(rating);
    }

    @Override
    public List<MenuItemRatingResponse> getMenuItemRatings(Long menuItemId) {
        if (!menuItemRepository.existsById(menuItemId)) {
            throw new ResourceNotFoundException("Menu item not found");
        }
        return menuItemRatingRepository.findByMenuItemIdOrderByCreatedAtDesc(menuItemId).stream()
                .map(this::toResponse)
                .toList();
    }

    private void updateMenuItemAverageRating(Long menuItemId) {
        Double average = menuItemRatingRepository.getAverageRatingByMenuItem(menuItemId);
        long count = menuItemRatingRepository.countByMenuItem(menuItemId);
        menuItemRepository.findById(menuItemId).ifPresent(item -> {
            item.setAverageRating(average != null ? average : 0.0);
            item.setTotalRatings((int) count);
            menuItemRepository.save(item);
        });
    }

    private MenuItemRatingResponse toResponse(MenuItemRating rating) {
        return MenuItemRatingResponse.builder()
                .id(rating.getId())
                .menuItemId(rating.getMenuItem().getId())
                .orderId(rating.getOrder().getId())
                .rating(rating.getRating())
                .comment(rating.getComment())
                .createdAt(rating.getCreatedAt() != null ? rating.getCreatedAt().toString() : null)
                .build();
    }
}
