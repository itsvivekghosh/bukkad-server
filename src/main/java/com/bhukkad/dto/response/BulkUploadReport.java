package com.bhukkad.dto.response;

import java.util.List;

/**
 * Per-file summary of a bulk CSV menu upload. {@code failed} equals the number
 * of {@code errors} entries (one per failed data row).
 */
public record BulkUploadReport(
        int processed,
        int succeeded,
        int failed,
        List<RowError> errors) {

    /** Validation/processing errors collected for a single CSV data row. */
    public record RowError(int row, List<String> messages) {
    }
}
