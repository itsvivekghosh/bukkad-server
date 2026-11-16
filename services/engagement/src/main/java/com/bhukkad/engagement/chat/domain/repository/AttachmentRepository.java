package com.bhukkad.engagement.chat.domain.repository;

import com.bhukkad.engagement.chat.domain.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Attachment entities.
 */
public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    Optional<Attachment> findByAttachmentId(UUID attachmentId);

    List<Attachment> findByMessageIdOrderByCreatedAtAsc(UUID messageId);

    @Query("SELECT a FROM Attachment a WHERE a.imageHash = :hash AND a.analysisStatus = 'ANALYZED'")
    List<Attachment> findByImageHashAnalyzed(@Param("hash") String imageHash);

    @Query("SELECT a FROM Attachment a WHERE a.imageHash IN :hashes AND a.analysisStatus = 'ANALYZED'")
    List<Attachment> findByImageHashInAndAnalyzedTrue(@Param("hashes") List<String> hashes);

    @Query("SELECT a FROM Attachment a WHERE a.analysisStatus = 'PENDING' ORDER BY a.createdAt ASC")
    List<Attachment> findPendingAnalysis(@Param("limit") int limit);

    @Query("SELECT a FROM Attachment a WHERE a.fraudRiskScore >= :minScore ORDER BY a.fraudRiskScore DESC")
    List<Attachment> findHighRiskAttachments(@Param("minScore") double minScore);

    @Query("SELECT a FROM Attachment a WHERE a.exifDatetimeOriginal IS NOT NULL AND a.createdAt <= :cutoff")
    List<Attachment> findPotentiallyStaledImages(@Param("cutoff") LocalDateTime cutoff);

    List<Attachment> findTop5ByStorageProviderAndProcessedAtIsNullOrderByCreatedAtAsc(
            Attachment.StorageProvider storageProvider);

    long countByIsDuplicateFingerprintTrue();

    long countByAnalysisStatus(Attachment.AnalysisStatus status);

    @Query("SELECT COUNT(a) FROM Attachment a WHERE a.createdAt >= :since AND a.fraudRiskScore >= :minScore")
    long countSuspiciousAttachmentsSince(@Param("since") LocalDateTime since, @Param("minScore") double minScore);
}