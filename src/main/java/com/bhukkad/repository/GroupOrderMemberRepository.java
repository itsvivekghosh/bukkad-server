package com.bhukkad.repository;

import com.bhukkad.entity.GroupOrderMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupOrderMemberRepository extends JpaRepository<GroupOrderMember, Long> {

    Optional<GroupOrderMember> findByGroupOrderIdAndUserId(Long groupOrderId, Long userId);

    List<GroupOrderMember> findByGroupOrderId(Long groupOrderId);

    long countByGroupOrderIdAndStatus(Long groupOrderId, GroupOrderMember.MemberStatus status);
}