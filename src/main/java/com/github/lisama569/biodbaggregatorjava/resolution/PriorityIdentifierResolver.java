package com.github.lisama569.biodbaggregatorjava.resolution;

import com.github.lisama569.biodbaggregatorjava.model.AmbiguousLink;
import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.EntityType;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionConflict;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic cross-source identifier resolver.
 *
 * <h2>Resolution rules (rulesVersion {@value #RULES_VERSION})</h2>
 * <ol>
 *   <li><b>Within-source duplicates</b> are handled at import time; the resolver sees at
 *       most one record per (source, localId).</li>
 *   <li><b>Explicit cross-references link entities:</b> record A references
 *       {@code namespace:value}; if a record B owns that token as its home identity
 *       ({@code B.source == namespace} and {@code B.localId == value}) or asserts the same
 *       token, and both have the same {@link EntityType}, A and B are merged. Links form
 *       an equivalence relation computed with union-find, so they are order-independent
 *       and transitive.</li>
 *   <li><b>No silent merging:</b> a reference to a token nobody owns is a
 *       {@link AmbiguousLink#DANGLING_REFERENCE}; a same-token claim across different
 *       entity types is a {@link AmbiguousLink#CROSS_TYPE_COLLISION}. Both are kept in
 *       the report instead of being dropped or guessed.</li>
 *   <li><b>Source priority:</b> every merged field is taken from the highest-priority
 *       source providing a non-blank value. Default priority (highest first):
 *       {@code refseq > uniprot > ensembl > genbank > ncbi}; unknown sources are appended
 *       after known ones in ascending source-name order.</li>
 *   <li><b>Conflicts are never silent:</b> whenever a lower-priority source asserts a
 *       different non-blank value, a {@link ResolutionConflict} records winner and losers
 *       with their values.</li>
 *   <li><b>Entity key:</b> {@code TYPE:priority-source:localId} of the highest-priority
 *       member (ties broken by ascending localId).</li>
 *   <li><b>Determinism:</b> entity order, member order, ambiguity order and map iteration
 *       are fully ordered; the same snapshot always yields byte-identical conclusions.</li>
 * </ol>
 */
@Component
public class PriorityIdentifierResolver implements IdentifierResolver {

    static final String RULES_VERSION = "priority-rules-v1";
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

    private int rank(String source) {
        int idx = sourcePriority.indexOf(source);
        return idx >= 0 ? idx : Integer.MAX_VALUE;
    }

    /** Total order on sources: configured priority first, then lexicographic name. */
    Comparator<String> sourceOrder() {
        return Comparator.comparingInt(this::rank).thenComparing(Comparator.naturalOrder());
    }

    @Override
    public ResolutionReport resolve(Snapshot snapshot) {
        List<BioRecord> records = snapshot.records();
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
            members.sort(memberOrder());
            ResolvedEntity entity = merge(members, snapshot.version());
            entities.add(entity);
            totalConflicts += entity.fieldConflicts().size();
            log.info("resolution: snapshot={} rules={} entityKey={} identifiers={} conflicts={} basis={}",
                    snapshot.version(), RULES_VERSION, entity.entityKey(),
                    entity.identifiersBySource(), entity.fieldConflicts().size(),
                    describeBasis(members));
        }
        entities.sort(Comparator.comparing(ResolvedEntity::entityKey));
        for (AmbiguousLink a : ambiguities) {
            log.info("resolution ambiguity: snapshot={} rules={} token={} reason={} claimer={}:{}",
                    snapshot.version(), RULES_VERSION, a.token(), a.reason(),
                    a.claimerSource(), a.claimerId());
        }
        log.info("resolution complete: snapshot={} rules={} entities={} fieldConflicts={} ambiguities={}",
                snapshot.version(), RULES_VERSION, entities.size(), totalConflicts, ambiguities.size());
        return new ResolutionReport(snapshot.version(), RULES_VERSION,
                List.copyOf(entities), totalConflicts, List.copyOf(ambiguities));
    }

    private static String homeToken(BioRecord r) {
        return r.source() + ":" + r.localId();
    }

    private String describeBasis(List<BioRecord> members) {
        List<String> orderedSources = members.stream().map(BioRecord::source)
                .distinct().sorted(sourceOrder()).toList();
        return "winner=" + orderedSources.get(0) + " priority=" + sourcePriority;
    }

    /** Member order: source priority ascending, then local id ascending; fully deterministic. */
    private Comparator<BioRecord> memberOrder() {
        return Comparator.comparing(BioRecord::source, sourceOrder())
                .thenComparing(BioRecord::localId);
    }

    private ResolvedEntity merge(List<BioRecord> members, String version) {
        // members is already ordered; the first one defines key/type and is the top-priority source
        BioRecord anchor = members.get(0);
        EntityType type = anchor.type();

        Map<String, String> identifiersBySource = new LinkedHashMap<>();
        Set<String> contributing = new LinkedHashSet<>();
        for (BioRecord m : members) {
            contributing.add(m.source());
            // first write wins = highest-priority source's localId
            identifiersBySource.putIfAbsent(m.source(), m.localId());
        }

        List<ResolutionConflict> conflicts = new ArrayList<>();
        String symbol = chooseField(members, BioRecord::symbol, "symbol", conflicts);
        String name = chooseField(members, BioRecord::name, "name", conflicts);
        String sequence = chooseField(members, BioRecord::sequence, "sequence", conflicts);
        String organism = chooseField(members, BioRecord::organism, "organism", conflicts);

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
                RULES_VERSION);
    }

    private String chooseField(List<BioRecord> members,
                               java.util.function.Function<BioRecord, String> getter,
                               String field,
                               List<ResolutionConflict> conflicts) {
        // members sorted by source order; first non-null value is the deterministic winner
        String winnerSource = null;
        String winnerValue = null;
        for (BioRecord m : members) {
            String v = getter.apply(m);
            if (v != null) {
                winnerSource = m.source();
                winnerValue = v;
                break;
            }
        }
        if (winnerValue == null) {
            return null;
        }
        List<String> loserSources = new ArrayList<>();
        List<String> loserValues = new ArrayList<>();
        for (BioRecord m : members) {
            if (m.source().equals(winnerSource)) {
                continue;
            }
            String v = getter.apply(m);
            if (v != null && !v.equals(winnerValue)) {
                loserSources.add(m.source());
                loserValues.add(v);
            }
        }
        if (!loserSources.isEmpty()) {
            // One retained loser value per source (first seen in priority-sorted members),
            // then order the result strictly by the deterministic source order.
            Map<String, String> loserBySource = new LinkedHashMap<>();
            for (int i = 0; i < loserSources.size(); i++) {
                loserBySource.putIfAbsent(loserSources.get(i), loserValues.get(i));
            }
            List<String> orderedSources = new ArrayList<>(loserBySource.keySet());
            orderedSources.sort(sourceOrder());
            List<String> orderedValues = orderedSources.stream()
                    .map(loserBySource::get).toList();
            conflicts.add(new ResolutionConflict(field, winnerSource, winnerValue,
                    List.copyOf(orderedSources), List.copyOf(orderedValues)));
        }
        return winnerValue;
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
