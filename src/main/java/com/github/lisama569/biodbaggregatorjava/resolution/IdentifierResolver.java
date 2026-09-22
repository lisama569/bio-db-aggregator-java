package com.github.lisama569.biodbaggregatorjava.resolution;

import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;

/**
 * Merges normalized records of one snapshot into resolved entities.
 * Implementations are deterministic and versioned (rulesVersion), so historical
 * conclusions remain reproducible when rules change.
 */
public interface IdentifierResolver {

    String rulesVersion();

    ResolutionReport resolve(Snapshot snapshot);
}
