package com.github.lisama569.biodbaggregatorjava.model;

/** A raw record that was not imported, with a distinguishable reason. */
public record RejectedRecord(
        String sourceId,
        String rawLine,
        RejectionReason reason,
        String detail
) {
}
