package com.github.lisama569.biodbaggregatorjava.model;

import java.util.List;

/**
 * 一次快照导入的结果报告。
 */
public record ImportReport(
        String version,
        String contentHash,
        int importedCount,
        int rejectedCount,
        int conflictCount,
        boolean idempotentReuse,
        List<RejectedRecord> rejected
) {
}
