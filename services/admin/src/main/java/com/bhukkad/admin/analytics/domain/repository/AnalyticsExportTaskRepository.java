package com.bhukkad.admin.analytics.domain.repository;
import com.bhukkad.admin.analytics.domain.entity.AnalyticsExportTask;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalyticsExportTaskRepository extends JpaRepository<AnalyticsExportTask, Long> {
    List<AnalyticsExportTask> findByStatus(String status);
}