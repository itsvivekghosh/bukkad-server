package com.bhukkad.social.domain.repository;

import com.bhukkad.social.domain.entity.PostComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

    List<PostComment> findByPostIdAndParentCommentIdIsNullOrderByCreatedAtDesc(Long postId);

    List<PostComment> findByPostIdOrderByCreatedAtDesc(Long postId);

    long countByPostIdAndParentCommentIdIsNull(Long postId);

    @Modifying
    @Query("DELETE FROM PostComment c WHERE c.postId = :postId")
    void deleteByPostId(@Param("postId") Long postId);
}
