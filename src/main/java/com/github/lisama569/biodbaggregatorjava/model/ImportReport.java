package com.github.lisama569.biodbaggregatorjava.model;

import java.util.List;

/**
 * Outcome of importing one snapshot source.
 *
 * @param status one of CREATED / DUPLICATE / CONFLICTING_REIMPORT / RULE_SET_MISMATCH
 * @param pinnedRuleSetId rule set the version is (now) stamped with; null when the import
 *                        was refused before the version existed
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
        boolean duplicateImport,
        String pinnedRuleSetId
) {

    public ImportReport(String snapshotVersion, String source, String status,
                        int acceptedCount, int rejectedCount, List<RejectedRecord> rejected,
                        int conflictCount, List<RejectedRecord> conflicts,
                        boolean duplicateImport) {
        this(snapshotVersion, source, status, acceptedCount, rejectedCount, rejected,
                conflictCount, conflicts, duplicateImport, null);
    }
}
