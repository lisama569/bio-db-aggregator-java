package com.github.lisama569.biodbaggregatorjava.store;

/** 快照元信息。 */
public record SnapshotInfo(
        String version,
        String contentHash,
        int recordCount,
        int rejectedCount,
        boolean current
) {
}
