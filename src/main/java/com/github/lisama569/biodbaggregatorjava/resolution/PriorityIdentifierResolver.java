package com.github.lisama569.biodbaggregatorjava.resolution;

import com.github.lisama569.biodbaggregatorjava.model.AmbiguousLink;
import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.EntityType;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionConflict;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.rules.FieldRule;
import com.github.lisama569.biodbaggregatorjava.rules.InvalidRuleSetException;
import com.github.lisama569.biodbaggregatorjava.rules.RuleRejectionReason;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Deterministic cross-source identifier resolver driven by an immutable {@link RuleSet}.
 *
 * <h2>Resolution rules</h2>
 * <ol>
 *   <li><b>Within-source duplicates</b> are handled at import time; the resolver sees at
 *       most one record per (source, localId).</li>
 *   <li><b>Explicit cross-references link entities:</b> record A references
 *       {@code namespace:value}; if a record B owns that token as its home identity
 *       ({@code B.source == namespace} and {@code B.localId == value}), and both have the
 *       same {@link EntityType}, A and B are merged. Links form an equivalence relation
 *       computed with union-find, so they are order-independent and transitive. Note that
 *       merging itself does not depend on source priority.</li>
 *   <li><b>No silent merging:</b> a reference to a token nobody owns is a
 *       {@link AmbiguousLink#DANGLING_REFERENCE}; a same-token claim across different
 *       entity types is a {@link AmbiguousLink#CROSS_TYPE_COLLISION}.</li>
 *   <li><b>Source priority comes from the rule set:</b> every merged field is taken from
 *       the first source (in that field's effective priority) providing a non-blank value.
 *       Per-field overrides ({@link FieldRule}) may reorder adjudication independently.
 *       Sources not declared by the rule set are appended lexicographically unless the
 *       rule set is strict, in which case the data is refused.</li>
 *   <li><b>Conflicts are never silent:</b> whenever a lower-priority source asserts a
 *       different non-blank value, a {@link ResolutionConflict} records winner and losers.</li>
 *   <li><b>Entity key:</b> {@code TYPE:anchor-source:localId} of the first member in the
 *       rule set's global source order (ties broken by ascending localId).</li>
 *   <li><b>Determinism:</b> entity order, member order, ambiguity order and map iteration
 *       are fully ordered; the same snapshot under the same rule set always yields
 *       byte-identical conclusions.</li>
 * </ol>
 */
@Component
public class PriorityIdentifierResolver implements IdentifierResolver {

    static final String RULES_VERSION = "priority-rules-v1";
    private static final List<String> FIELDS = List.of("symbol", "name", "sequence", "organism");
    private static final Logger log = LoggerFactory.getLogger(PriorityIdentifierResolver.class);

    private final List<String> sourcePriority;

    public PriorityIdentifierResolver(
            @Value("${aggregator.source-priority:refseq,uniprot,ensembl,genbank,ncbi}")
            String sourcePriorityCsv) {
        List<String> configured = new ArrayList<>();
        for (String s : sourcePriorityCsv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                configured.add(t);
            }
        }
        this.sourcePriority = List.copyOf(configured);
    }

    @Override
    public String rulesVersion() {
        return RULES_VERSION;
    }

    /** Legacy rule set derived from the CSV property, preserving the original behavior. */
    RuleSet legacyRuleSet() {
        return new RuleSet(RULES_VERSION, "legacy csv-configured priority",
                sourcePriority, sourcePriority, Map.of(), false, null);
    }

    @Override
    public ResolutionReport resolve(Snapshot snapshot) {
        return resolve(snapshot, legacyRuleSet());
    }

    @Override
    public ResolutionReport resolve(Snapshot snapshot, RuleSet ruleSet) {
        ruleSet.validate();
        List<BioRecord> records = snapshot.records();

        ensureSourcesKnown(ruleSet, records);
        Comparator<String> globalOrder = sourceOrder(ruleSet, ruleSet.sourcePriority(), records);

        int n = records.size();

        // 1) Union-find over record indices.
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }

        // 2) Home identity tokens: each record owns "source:localId" (no type prefix,
        //    so same-namespace claims across types are observable collisions).
        Map<String, TokenClaim> tokenOwner = new HashMap<>();
        for (int i = 0; i < n; i++) {
            BioRecord r = records.get(i);
            tokenOwner.putIfAbsent(homeToken(r), new TokenClaim(i, r.type()));
        }

        // 3) External xref claims in deterministic record order.
        List<AmbiguousLink> ambiguities = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BioRecord r = records.get(i);
            for (Map.Entry<String, String> x : r.crossReferences().entrySet()) {
                String token = x.getKey() + ":" + x.getValue();
                TokenClaim existing = tokenOwner.get(token);
                if (existing == null) {
                    ambiguities.add(new AmbiguousLink(token, AmbiguousLink.DANGLING_REFERENCE,
                            null, null, r.source(), r.localId()));
                } else if (existing.type() == r.type()) {
                    union(parent, existing.owner(), i);
                } else {
                    BioRecord owner = records.get(existing.owner());
                    ambiguities.add(new AmbiguousLink(token, AmbiguousLink.CROSS_TYPE_COLLISION,
                            owner.source(), owner.localId(), r.source(), r.localId()));
                }
            }
        }
        ambiguities.sort(Comparator.comparing(AmbiguousLink::token)
                .thenComparing(AmbiguousLink::claimerSource)
                .thenComparing(AmbiguousLink::claimerId));

        // 4) Group members deterministically.
        TreeSet<Integer> roots = new TreeSet<>();
        Map<Integer, List<BioRecord>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            roots.add(root);
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(records.get(i));
        }

        List<ResolvedEntity> entities = new ArrayList<>();
        int totalConflicts = 0;
        for (Integer root : roots) {
            List<BioRecord> members = groups.get(root);
            members.sort(memberOrder(globalOrder));
            ResolvedEntity entity = merge(members, snapshot.version(), ruleSet, globalOrder);
            entities.add(entity);
            totalConflicts += entity.fieldConflicts().size();
            log.info("resolution: snapshot={} rules={} entityKey={} identifiers={} conflicts={} basis={}",
                    snapshot.version(), ruleSet.id(), entity.entityKey(),
                    entity.identifiersBySource(), entity.fieldConflicts().size(),
                    describeBasis(members, ruleSet, entity));
        }
        entities.sort(Comparator.comparing(ResolvedEntity::entityKey));
        for (AmbiguousLink a : ambiguities) {
            log.info("resolution ambiguity: snapshot={} rules={} token={} reason={} claimer={}:{}",
                    snapshot.version(), ruleSet.id(), a.token(), a.reason(),
                    a.claimerSource(), a.claimerId());
        }
        log.info("resolution complete: snapshot={} rules={} entities={} fieldConflicts={} ambiguities={}",
                snapshot.version(), ruleSet.id(), entities.size(), totalConflicts, ambiguities.size());
        return new ResolutionReport(snapshot.version(), ruleSet.id(),
                List.copyOf(entities), totalConflicts, List.copyOf(ambiguities));
    }

    private void ensureSourcesKnown(RuleSet ruleSet, List<BioRecord> records) {
        if (!ruleSet.strictSources()) {
            return;
        }
        for (BioRecord r : records) {
            if (!ruleSet.sources().contains(r.source())) {
                throw new InvalidRuleSetException(RuleRejectionReason.UNKNOWN_SOURCE_REFERENCED,
                        ruleSet.id(),
                        "strict rule set does not declare source '" + r.source()
                                + "' present in snapshot; declared sources=" + ruleSet.sources());
            }
            for (String ns : r.crossReferences().keySet()) {
                if (!ruleSet.sources().contains(ns)) {
                    throw new InvalidRuleSetException(RuleRejectionReason.UNKNOWN_SOURCE_REFERENCED,
                            ruleSet.id(),
                            "strict rule set does not declare xref namespace '" + ns
                                    + "' referenced by " + r.source() + ":" + r.localId());
                }
            }
        }
    }

    /** Total order on sources: declared priority first, then (if non-strict) extra
     *  sources appearing in the data in ascending name order. */
    private Comparator<String> sourceOrder(RuleSet ruleSet, List<String> priority,
                                           List<BioRecord> records) {
        if (ruleSet.strictSources()) {
            return Comparator.comparingInt((String s) -> {
                int idx = priority.indexOf(s);
                if (idx < 0) {
                    // strict sets reject unknown data before ordering; put unknowns last defensively
                    return Integer.MAX_VALUE;
                }
                return idx;
            }).thenComparing(Comparator.naturalOrder());
        }
        List<String> extra = new ArrayList<>();
        for (BioRecord r : records) {
            if (!priority.contains(r.source()) && !extra.contains(r.source())) {
                extra.add(r.source());
            }
            for (String ns : r.crossReferences().keySet()) {
                if (!priority.contains(ns) && !extra.contains(ns)) {
                    extra.add(ns);
                }
            }
        }
        extra.sort(Comparator.naturalOrder());
        List<String> all = new ArrayList<>(priority);
        all.addAll(extra);
        return Comparator.comparingInt((String s) -> {
            int idx = all.indexOf(s);
            return idx >= 0 ? idx : Integer.MAX_VALUE;
        }).thenComparing(Comparator.naturalOrder());
    }

    private static String homeToken(BioRecord r) {
        return r.source() + ":" + r.localId();
    }

    private String describeBasis(List<BioRecord> members, RuleSet ruleSet, ResolvedEntity entity) {
        List<String> perField = new ArrayList<>();
        for (String field : FIELDS) {
            String winner = winnerSourceFor(members, ruleSet, field);
            if (winner != null) {
                perField.add(field + "<-" + winner + "(order=" + ruleSet.priorityFor(field) + ")");
            }
        }
        return "contributing=" + entity.contributingSources() + " globalPriority="
                + ruleSet.sourcePriority() + " fields={" + String.join("; ", perField) + "}";
    }

    private String winnerSourceFor(List<BioRecord> members, RuleSet ruleSet, String field) {
        String winnerSource = null;
        int winnerRank = Integer.MAX_VALUE;
        for (BioRecord m : members) {
            if (getter(field).apply(m) != null) {
                int idx = effectiveIndex(ruleSet, field, m.source(), members);
                if (idx < winnerRank) {
                    winnerRank = idx;
                    winnerSource = m.source();
                }
            }
        }
        return winnerSource;
    }

    private int effectiveIndex(RuleSet ruleSet, String field, String source,
                               List<BioRecord> members) {
        List<String> priority = ruleSet.priorityFor(field);
        int idx = priority.indexOf(source);
        if (idx >= 0) {
            return idx;
        }
        if (ruleSet.strictSources()) {
            return Integer.MAX_VALUE;
        }
        List<String> extra = new ArrayList<>();
        for (BioRecord r : members) {
            if (!priority.contains(r.source()) && !extra.contains(r.source())) {
                extra.add(r.source());
            }
        }
        extra.sort(Comparator.naturalOrder());
        int extraIdx = extra.indexOf(source);
        return extraIdx < 0 ? Integer.MAX_VALUE : priority.size() + extraIdx;
    }

    /** Member order: global source priority ascending, then local id ascending. */
    private Comparator<BioRecord> memberOrder(Comparator<String> sourceComparator) {
        return Comparator.comparing(BioRecord::source, sourceComparator)
                .thenComparing(BioRecord::localId);
    }

    private ResolvedEntity merge(List<BioRecord> members, String version,
                                 RuleSet ruleSet, Comparator<String> globalOrder) {
        // members is already ordered; the first one defines key/type and is the
        // top-priority member under the rule set's global source order.
        BioRecord anchor = members.get(0);
        EntityType type = anchor.type();

        Map<String, String> identifiersBySource = new LinkedHashMap<>();
        List<String> contributing = new ArrayList<>();
        for (BioRecord m : members) {
            if (!contributing.contains(m.source())) {
                contributing.add(m.source());
            }
            // first write wins = highest global-priority source's localId
            identifiersBySource.putIfAbsent(m.source(), m.localId());
        }
        contributing.sort(globalOrder);

        List<ResolutionConflict> conflicts = new ArrayList<>();
        String symbol = chooseField(members, ruleSet, "symbol", conflicts);
        String name = chooseField(members, ruleSet, "name", conflicts);
        String sequence = chooseField(members, ruleSet, "sequence", conflicts);
        String organism = chooseField(members, ruleSet, "organism", conflicts);

        String entityKey = type.name() + ":" + anchor.source() + ":" + anchor.localId();
        return new ResolvedEntity(
                entityKey,
                type,
                symbol,
                name,
                sequence,
                organism,
                Map.copyOf(identifiersBySource),
                List.copyOf(conflicts),
                List.copyOf(contributing),
                version,
                ruleSet.id());
    }

    private String chooseField(List<BioRecord> members,
                               RuleSet ruleSet,
                               String field,
                               List<ResolutionConflict> conflicts) {
        List<String> priority = ruleSet.priorityFor(field);
        // rank each contributing source by this field's effective order
        String winnerSource = null;
        int winnerRank = Integer.MAX_VALUE;
        String winnerValue = null;
        for (BioRecord m : members) {
            String v = getter(field).apply(m);
            if (v != null) {
                int rank = effectiveIndex(ruleSet, field, m.source(), members);
                if (rank < winnerRank) {
                    winnerRank = rank;
                    winnerSource = m.source();
                    winnerValue = v;
                }
            }
        }
        if (winnerValue == null) {
            return null;
        }
        // retain one differing loser value per source, ordered by the field's source order
        Map<String, String> loserBySource = new LinkedHashMap<>();
        for (BioRecord m : members) {
            if (m.source().equals(winnerSource)) {
                continue;
            }
            String v = getter(field).apply(m);
            if (v != null && !v.equals(winnerValue)) {
                loserBySource.putIfAbsent(m.source(), v);
            }
        }
        if (!loserBySource.isEmpty()) {
            List<String> orderedSources = new ArrayList<>(loserBySource.keySet());
            orderedSources.sort(Comparator.comparingInt(
                    s -> effectiveIndex(ruleSet, field, s, members)));
            List<String> orderedValues = orderedSources.stream()
                    .map(loserBySource::get).toList();
            conflicts.add(new ResolutionConflict(field, winnerSource, winnerValue,
                    List.copyOf(orderedSources), List.copyOf(orderedValues)));
        }
        return winnerValue;
    }

    private static java.util.function.Function<BioRecord, String> getter(String field) {
        return switch (field) {
            case "symbol" -> BioRecord::symbol;
            case "name" -> BioRecord::name;
            case "sequence" -> BioRecord::sequence;
            case "organism" -> BioRecord::organism;
            default -> throw new IllegalArgumentException("unknown adjudicated field " + field);
        };
    }

    private static int find(int[] parent, int x) {
        int root = x;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[x] != x) {
            int next = parent[x];
            parent[x] = root;
            x = next;
        }
        return root;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            // attach larger index under smaller index for determinism
            if (ra < rb) {
                parent[rb] = ra;
            } else {
                parent[ra] = rb;
            }
        }
    }

    private record TokenClaim(int owner, EntityType type) {
    }
}
