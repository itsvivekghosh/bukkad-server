package com.bhukkad.repository;

import com.bhukkad.entity.GiftOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GiftOrderRepository extends JpaRepository<GiftOrder, Long> {

    List<GiftOrder> findBySenderUserId(Long senderUserId);
}
