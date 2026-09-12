package com.bhukkad.admin.domain.repository;
import com.bhukkad.admin.domain.entity.AnalyticsExportTask;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalyticsExportTaskRepository extends JpaRepository<AnalyticsExportTask, Long> {
    List<AnalyticsExportTask> findByStatus(String status);
}