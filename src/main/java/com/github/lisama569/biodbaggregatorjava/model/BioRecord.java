package com.github.lisama569.biodbaggregatorjava.model;

import java.util.Map;

/** Unified, normalized record for a gene or a protein from one snapshot source. */
public record BioRecord(
        String recordUid,
        String source,
        EntityType type,
        String localId,
        String symbol,
        String name,
        String sequence,
        String organism,
        Map<String, String> crossReferences
) {
}
