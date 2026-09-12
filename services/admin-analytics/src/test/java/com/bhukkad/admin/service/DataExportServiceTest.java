package com.bhukkad.admin.service;
import com.bhukkad.admin.domain.service.DataExportService;

import com.bhukkad.admin.domain.entity.DataExportRequest;
import com.bhukkad.admin.domain.repository.DataExportRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataExportServiceTest {

    @Mock private DataExportRequestRepository repository;
    @InjectMocks private DataExportService service;

    @Test
    void request_createsPendingRequest() {
        when(repository.save(any(DataExportRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        DataExportRequest request = service.request(7L, "json");

        assertThat(request.getCustomerId()).isEqualTo(7L);
        assertThat(request.getFormat()).isEqualTo("json");
        assertThat(request.getStatus()).isEqualTo(DataExportRequest.STATUS_PENDING);
    }

    @Test
    void complete_marksCompletedAndGeneratesFileUrl() {
        DataExportRequest request = new DataExportRequest();
        request.setId(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(request));
        when(repository.save(any(DataExportRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        DataExportRequest completed = service.complete(1L);

        assertThat(completed.getStatus()).isEqualTo(DataExportRequest.STATUS_COMPLETED);
        assertThat(completed.getFileUrl()).startsWith("/exports/");
    }

    @Test
    void complete_unknownRequest_throws() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(99L))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void byCustomer_returnsRequestsForCustomer() {
        when(repository.findByCustomerId(7L)).thenReturn(List.of());

        assertThat(service.byCustomer(7L)).isEmpty();
        verify(repository).findByCustomerId(7L);
    }
}
