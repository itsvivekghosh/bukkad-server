package com.bhukkad.repository;

import com.bhukkad.entity.GroupOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupOrderRepository extends JpaRepository<GroupOrder, Long> {

    @Query("SELECT g FROM GroupOrder g WHERE g.hostUserId = :hostUserId AND g.status = 'OPEN' ORDER BY g.createdAt DESC")
    List<GroupOrder> findOpenByHostUserId(@Param("hostUserId") Long hostUserId);

    List<GroupOrder> findByStatus(GroupOrder.GroupOrderStatus status);
}