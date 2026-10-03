package com.github.lisama569.biodbaggregatorjava.service;

import com.github.lisama569.biodbaggregatorjava.model.ResolutionConflict;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.rules.EntityClusterDifference;
import com.github.lisama569.biodbaggregatorjava.model.rules.FieldWinnerDifference;
import com.github.lisama569.biodbaggregatorjava.model.rules.ResolutionComparison;
import com.github.lisama569.biodbaggregatorjava.store.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Computes a deterministic field-level diff of one data snapshot resolved under two rule
 * sets. Entities are matched by stable cluster identity (type + sorted member
 * identifiers), not by entity key, because the key itself is priority-dependent and can
 * change when the winning source changes.
 */
@Service
public class RuleComparisonService {

    private static final Logger log = LoggerFactory.getLogger(RuleComparisonService.class);

    private final SnapshotStore store;

    public RuleComparisonService(SnapshotStore store) {
        this.store = store;
    }

    public ResolutionComparison compare(String version, String rulesIdA, String rulesIdB) {
        if (Objects.equals(rulesIdA, rulesIdB)) {
            throw new IllegalArgumentException(
                    "comparison requires two distinct rule sets, got '" + rulesIdA + "' twice");
        }
        ResolutionReport a = store.resolutionFor(version, rulesIdA);
        ResolutionReport b = store.resolutionFor(version, rulesIdB);

        Map<String, ResolvedEntity> clustersA = indexByCluster(a);
        Map<String, ResolvedEntity> clustersB = indexByCluster(b);

        TreeSet<String> allClusters = new TreeSet<>();
        allClusters.addAll(clustersA.keySet());
        allClusters.addAll(clustersB.keySet());

        List<EntityClusterDifference> differences = new ArrayList<>();
        int fieldWinnerChanges = 0;
        for (String clusterKey : allClusters) {
            ResolvedEntity ea = clustersA.get(clusterKey);
            ResolvedEntity eb = clustersB.get(clusterKey);
            if (ea == null || eb == null) {
                // Cluster exists only under one rule set => merging split/joined differently.
                differences.add(new EntityClusterDifference(
                        clusterKey,
                        ea == null ? null : ea.entityKey(),
                        eb == null ? null : eb.entityKey(),
                        List.of(),
                        (eb == null ? 0 : eb.fieldConflicts().size())
                                - (ea == null ? 0 : ea.fieldConflicts().size()),
                        true));
                continue;
            }
            List<FieldWinnerDifference> fieldDiffs = new ArrayList<>();
            for (String field : ResolutionComparison.CANONICAL_FIELDS) {
                String valueA = fieldValue(ea, field);
                String valueB = fieldValue(eb, field);
                String winnerA = ea.fieldWinnerSources().get(field);
                String winnerB = eb.fieldWinnerSources().get(field);
                if (!Objects.equals(valueA, valueB) || !Objects.equals(winnerA, winnerB)) {
                    fieldDiffs.add(new FieldWinnerDifference(
                            clusterKey, field, winnerA, valueA, winnerB, valueB));
                }
            }
            boolean mergeChanged =
                    !java.util.Objects.equals(ea.identifiersBySource(), eb.identifiersBySource());
            int conflictDelta = eb.fieldConflicts().size() - ea.fieldConflicts().size();
            if (!fieldDiffs.isEmpty() || conflictDelta != 0 || mergeChanged
                    || !ea.entityKey().equals(eb.entityKey())) {
                differences.add(new EntityClusterDifference(
                        clusterKey, ea.entityKey(), eb.entityKey(),
                        List.copyOf(fieldDiffs), conflictDelta, mergeChanged));
                fieldWinnerChanges += fieldDiffs.size();
            }
        }

        TreeSet<String> ambiguousA = ambiguousTokens(a);
        TreeSet<String> ambiguousB = ambiguousTokens(b);
        List<String> onlyA = ambiguousA.stream().filter(t -> !ambiguousB.contains(t)).toList();
        List<String> onlyB = ambiguousB.stream().filter(t -> !ambiguousA.contains(t)).toList();

        boolean identical = differences.isEmpty() && onlyA.isEmpty() && onlyB.isEmpty()
                && a.entities().size() == b.entities().size();
        ResolutionComparison result = new ResolutionComparison(
                version, rulesIdA, rulesIdB, identical,
                a.entities().size(), b.entities().size(),
                List.copyOf(differences), List.copyOf(onlyA), List.copyOf(onlyB),
                fieldWinnerChanges, a.conflictCount(), b.conflictCount());

        log.info("rule comparison: snapshot={} rulesA={} rulesB={} identical={} entitiesA={} entitiesB={} "
                        + "clustersWithDiffs={} fieldWinnerChanges={} conflictsA={} conflictsB={} ambiguitiesOnlyA={} ambiguitiesOnlyB={}",
                version, rulesIdA, rulesIdB, identical, result.entityCountA(), result.entityCountB(),
                differences.size(), fieldWinnerChanges, a.conflictCount(), b.conflictCount(),
                onlyA, onlyB);
        for (EntityClusterDifference d : differences) {
            log.info("  diff cluster={} mergeChanged={} keyA={} keyB={} conflictDelta={} changedFields={}",
                    d.clusterKey(), d.mergeChanged(), d.entityKeyA(), d.entityKeyB(),
                    d.conflictCountDelta(),
                    d.changedFields().stream().map(f -> f.field()
                            + ":[" + f.rulesAWinnerSource() + "->" + f.rulesBWinnerSource() + "]").toList());
        }
        return result;
    }

    private static Map<String, ResolvedEntity> indexByCluster(ResolutionReport report) {
        Map<String, ResolvedEntity> map = new LinkedHashMap<>();
        for (ResolvedEntity entity : report.entities()) {
            map.put(ResolutionComparison.clusterKey(entity), entity);
        }
        return map;
    }

    private static TreeSet<String> ambiguousTokens(ResolutionReport report) {
        TreeSet<String> tokens = new TreeSet<>();
        report.ambiguities().forEach(a -> tokens.add(ResolutionComparison.ambiguityToken(a)));
        return tokens;
    }

    private static String fieldValue(ResolvedEntity e, String field) {
        return switch (field) {
            case "symbol" -> e.canonicalSymbol();
            case "name" -> e.canonicalName();
            case "sequence" -> e.sequence();
            case "organism" -> e.organism();
            default -> throw new IllegalStateException("unknown field " + field);
        };
    }
}
