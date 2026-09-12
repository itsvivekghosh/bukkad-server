package com.bhukkad.order.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import com.bhukkad.order.domain.entity.CartItem;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    @Query("SELECT ci FROM CartItem ci WHERE ci.id = :id")
    Optional<CartItem> findByIdWithCart(@Param("id") Long id);

    @Query("SELECT ci FROM CartItem ci WHERE ci.cartId = :cartId")
    List<CartItem> findByCartId(@Param("cartId") Long cartId);

    /** Backs the UNIQUE (cart_id, menu_item_id) race-retry merge (V3 migration). */
    @Query("SELECT ci FROM CartItem ci WHERE ci.cartId = :cartId AND ci.menuItemId = :menuItemId")
    Optional<CartItem> findByCartIdAndMenuItemId(@Param("cartId") Long cartId,
                                                 @Param("menuItemId") Long menuItemId);

    @Modifying
    @Transactional
    @Query("DELETE FROM CartItem ci WHERE ci.cartId = :cartId")
    void deleteByCartId(@Param("cartId") Long cartId);

    /** PERF-3: bulk line removal for one sweep batch (statement, not per-cart deletes). */
    @Modifying
    @Transactional
    @Query("DELETE FROM CartItem ci WHERE ci.cartId IN :cartIds")
    int deleteByCartIdIn(@Param("cartIds") List<Long> cartIds);

    @Query("SELECT COUNT(ci) FROM CartItem ci WHERE ci.cartId = :cartId")
    long countByCartId(@Param("cartId") Long cartId);
}
