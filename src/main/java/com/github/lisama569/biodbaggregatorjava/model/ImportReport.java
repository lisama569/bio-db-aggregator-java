package com.github.lisama569.biodbaggregatorjava.model;

import java.util.List;

/**
 * Outcome of importing one snapshot source.
 *
 * @param status one of CREATED / DUPLICATE / CONFLICTING_REIMPORT
 */
public record ImportReport(
        String snapshotVersion,
        String source,
        String status,
        int acceptedCount,
        int rejectedCount,
        List<RejectedRecord> rejected,
        int conflictCount,
        List<RejectedRecord> conflicts,
        boolean duplicateImport
) {
}
