package com.bhukkad.repository;

import com.bhukkad.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByCartId(Long cartId);

    // Fetch cart items with menu item eagerly loaded (avoids N+1)
    @Query("SELECT ci FROM CartItem ci " +
            "JOIN FETCH ci.menuItem m " +
            "JOIN FETCH m.category c " +
            "JOIN FETCH c.restaurant r " +
            "WHERE ci.cart.id = :cartId")
    List<CartItem> findByCartIdWithMenuItem(@Param("cartId") Long cartId);

    @Modifying
    @Query("DELETE FROM CartItem ci WHERE ci.cart.id = :cartId")
    void deleteByCartId(@Param("cartId") Long cartId);

    @Query("SELECT COUNT(ci) FROM CartItem ci WHERE ci.cart.id = :cartId")
    long countByCartId(@Param("cartId") Long cartId);
}