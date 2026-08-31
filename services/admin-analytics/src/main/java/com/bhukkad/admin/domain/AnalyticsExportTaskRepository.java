package com.bhukkad.admin.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalyticsExportTaskRepository extends JpaRepository<AnalyticsExportTask, Long> {
    List<AnalyticsExportTask> findByStatus(String status);
}