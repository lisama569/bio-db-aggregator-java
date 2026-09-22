package com.github.lisama569.biodbaggregatorjava.service;

import com.github.lisama569.biodbaggregatorjava.model.ImportReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;

import java.util.List;
import java.util.Optional;

/** Facade: import snapshots, switch versions, query consistent resolved views. */
public interface AggregatorService {

    /**
     * Import a local snapshot export. Idempotent for the same (version, source, content).
     */
    ImportReport importSnapshot(String version, String source, SourceFormat format, String rawInput);

    boolean switchVersion(String version);

    Optional<String> currentVersion();

    List<String> versions();

    Optional<Snapshot> getSnapshot(String version);

    Optional<ResolutionReport> getResolution(String version);

    Optional<ResolvedEntity> resolveIdentifier(String identifier);

    List<ResolvedEntity> queryAll();
}
