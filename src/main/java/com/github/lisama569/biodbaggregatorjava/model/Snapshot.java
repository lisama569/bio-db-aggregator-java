package com.github.lisama569.biodbaggregatorjava.model;

import java.util.List;

/** An immutable, point-in-time view of all records imported for one snapshot version. */
public record Snapshot(
        String version,
        List<BioRecord> records,
        List<RejectedRecord> rejected,
        List<RejectedRecord> conflicts
) {
}
