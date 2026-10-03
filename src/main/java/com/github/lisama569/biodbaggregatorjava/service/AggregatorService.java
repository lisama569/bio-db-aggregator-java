package com.github.lisama569.biodbaggregatorjava.service;

import com.github.lisama569.biodbaggregatorjava.model.ImportReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.model.rules.ResolutionComparison;
import com.github.lisama569.biodbaggregatorjava.ruleset.RuleSet;

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

    /** The pinned, historically published conclusion of a version (rules it shipped with). */
    Optional<ResolutionReport> getResolution(String version);

    /**
     * The conclusion of a version reproduced under an explicitly named rule set. The
     * pinned historical conclusion is never mutated; both reports coexist.
     */
    Optional<ResolutionReport> getResolution(String version, String rulesId);

    Optional<ResolvedEntity> resolveIdentifier(String identifier);

    /** Point lookup within one version under an explicit rule set. */
    Optional<ResolvedEntity> resolveIdentifier(String version, String rulesId, String identifier);

    List<ResolvedEntity> queryAll();

    /** All entities of the current data version resolved under an explicit rule set. */
    List<ResolvedEntity> queryAll(String rulesId);

    // ---- rule set management -------------------------------------------------

    List<RuleSet> ruleSets();

    String currentRulesId();

    /** Atomically select the rule set used for unqualified reads and new version pins. */
    boolean switchRules(String rulesId);

    /** Compare one data snapshot's conclusions under two distinct rule sets. */
    ResolutionComparison compareRules(String version, String rulesIdA, String rulesIdB);
}
