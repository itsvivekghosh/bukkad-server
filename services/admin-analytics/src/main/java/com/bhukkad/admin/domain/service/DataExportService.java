package com.bhukkad.admin.domain.service;

import com.bhukkad.admin.domain.entity.DataExportRequest;
import com.bhukkad.admin.domain.repository.DataExportRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * GDPR data export requests (Batch E depth). A worker generates the file and
 * flips the status; this service owns the request lifecycle.
 */
@Service
@RequiredArgsConstructor
public class DataExportService {

    private final DataExportRequestRepository repository;

    @Transactional
    public DataExportRequest request(Long customerId, String format) {
        DataExportRequest request = new DataExportRequest();
        request.setCustomerId(customerId);
        request.setFormat(format);
        request.setStatus(DataExportRequest.STATUS_PENDING);
        return repository.save(request);
    }

    @Transactional
    public DataExportRequest complete(Long requestId) {
        DataExportRequest request = repository.findById(requestId).orElseThrow();
        request.setStatus(DataExportRequest.STATUS_COMPLETED);
        request.setFileUrl("/exports/" + UUID.randomUUID());
        return repository.save(request);
    }

    @Transactional(readOnly = true)
    public List<DataExportRequest> byCustomer(Long customerId) {
        return repository.findByCustomerId(customerId);
    }
}