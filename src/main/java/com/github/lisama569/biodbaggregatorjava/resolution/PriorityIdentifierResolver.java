package com.github.lisama569.biodbaggregatorjava.resolution;

import com.github.lisama569.biodbaggregatorjava.model.AmbiguousLink;
import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.EntityType;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionConflict;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.ruleset.DefaultRuleSetRegistry;
import com.github.lisama569.biodbaggregatorjava.ruleset.FieldResolutionStrategy;
import com.github.lisama569.biodbaggregatorjava.ruleset.FieldRule;
import com.github.lisama569.biodbaggregatorjava.ruleset.RuleSet;
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
 * Deterministic cross-source identifier resolver, driven by an explicit {@link RuleSet}.
 *
 * <h2>Resolution rules</h2>
 * <ol>
 *   <li><b>Within-source duplicates</b> are handled at import time; the resolver sees at
 *       most one record per (source, localId).</li>
 *   <li><b>Explicit cross-references link entities:</b> record A references
 *       {@code namespace:value}; if a record B owns that token as its home identity
 *       ({@code B.source == namespace} and {@code B.localId == value}) and both have the
 *       same {@link EntityType}, A and B are merged. Links form an equivalence relation
 *       computed with union-find: order-independent and transitive.</li>
 *   <li><b>No silent merging:</b> a reference to a token nobody owns is a
 *       {@link AmbiguousLink#DANGLING_REFERENCE}; a same-token claim across different
 *       entity types is a {@link AmbiguousLink#CROSS_TYPE_COLLISION}. Both are retained.</li>
 *   <li><b>Source priority comes from the rule set</b> (highest first); sources not listed
 *       append after listed ones in ascending source-name order. Each canonical field may
 *       additionally override that order and pick an adjudication strategy
 *       ({@link FieldResolutionStrategy#SOURCE_PRIORITY} or
 *       {@link FieldResolutionStrategy#PREFER_LONGEST}).</li>
 *   <li><b>Conflicts are never silent:</b> whenever a non-winning source asserts a
 *       different non-blank value, a {@link ResolutionConflict} records winner and losers.</li>
 *   <li><b>Entity key:</b> {@code TYPE:priority-source:localId} of the highest-priority
 *       member under the rule set (ties broken by ascending localId).</li>
 *   <li><b>Determinism:</b> the same snapshot under the same rule set always yields an
 *       equal report, independent of record order, execution order or concurrency.</li>
 * </ol>
 *
 * <p>The Spring bean resolves against the currently selected registry rule set; the
 * legacy CSV constructor ({@link #RULES_VERSION}) is retained for standalone unit tests.</p>
 */
@Component
public class PriorityIdentifierResolver implements IdentifierResolver {

    /** Legacy built-in stamp, kept byte-compatible with pre-rule-set conclusions. */
    static final String RULES_VERSION = "priority-rules-v1";
    private static final Logger log = LoggerFactory.getLogger(PriorityIdentifierResolver.class);
    private static final List<String> CANONICAL_FIELDS =
            List.of("symbol", "name", "sequence", "organism");

    private final List<String> defaultPriority;
    private final DefaultRuleSetRegistry registry;

    /** Spring bean: rule sets are selected via the registry. */
    @org.springframework.beans.factory.annotation.Autowired
    public PriorityIdentifierResolver(DefaultRuleSetRegistry registry) {
        this.registry = registry;
        this.defaultPriority = List.of("refseq", "uniprot", "ensembl", "genbank", "ncbi");
    }

    /** Standalone constructor (legacy unit tests): fixed priority, fixed stamp. */
    public PriorityIdentifierResolver(@Value("${aggregator.source-priority:refseq,uniprot,ensembl,genbank,ncbi}")
                                      String sourcePriorityCsv) {
        this.registry = null;
        List<String> configured = new ArrayList<>();
        for (String s : sourcePriorityCsv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                configured.add(t);
            }
        }
        this.defaultPriority = List.copyOf(configured);
    }

    @Override
    public String rulesVersion() {
        return registry == null ? RULES_VERSION : registry.currentId();
    }

    @Override
    public ResolutionReport resolve(Snapshot snapshot) {
        if (registry == null) {
            RuleSet legacy = new RuleSet(RULES_VERSION, "legacy built-in",
                    defaultPriority, Map.of(), "legacy");
            return resolve(snapshot, legacy);
        }
        return resolve(snapshot, registry.current());
    }

    @Override
    public ResolutionReport resolve(Snapshot snapshot, RuleSet ruleSet) {
        Context ctx = new Context(ruleSet);
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
            members.sort(ctx.memberOrder());
            ResolvedEntity entity = merge(ctx, members, snapshot.version());
            entities.add(entity);
            totalConflicts += entity.fieldConflicts().size();
            log.info("resolution: snapshot={} rules={} rulesHash={} entityKey={} identifiers={} conflicts={} basis={}",
                    snapshot.version(), ruleSet.id(), ruleSet.contentHash(), entity.entityKey(),
                    entity.identifiersBySource(), entity.fieldConflicts().size(),
                    describeBasis(ctx, members));
        }
        entities.sort(Comparator.comparing(ResolvedEntity::entityKey));
        for (AmbiguousLink a : ambiguities) {
            log.info("resolution ambiguity: snapshot={} rules={} token={} reason={} claimer={}:{}",
                    snapshot.version(), ruleSet.id(), a.token(), a.reason(),
                    a.claimerSource(), a.claimerId());
        }
        log.info("resolution complete: snapshot={} rules={} rulesHash={} entities={} fieldConflicts={} ambiguities={}",
                snapshot.version(), ruleSet.id(), ruleSet.contentHash(),
                entities.size(), totalConflicts, ambiguities.size());
        return new ResolutionReport(snapshot.version(), ruleSet.id(),
                List.copyOf(entities), totalConflicts, List.copyOf(ambiguities));
    }

    private static String homeToken(BioRecord r) {
        return r.source() + ":" + r.localId();
    }

    private String describeBasis(Context ctx, List<BioRecord> members) {
        List<String> orderedSources = members.stream().map(BioRecord::source)
                .distinct().sorted(ctx.globalOrder()).toList();
        return "winner=" + orderedSources.get(0) + " globalPriority=" + ctx.rules.sourcePriority();
    }

    /** Mutable per-resolution holder of derived comparators/orders for one rule set. */
    private final class Context {
        private final RuleSet rules;

        private Context(RuleSet rules) {
            this.rules = rules;
        }

        /** Total order: listed priority first (ascending rank), then lexicographic name. */
        Comparator<String> globalOrder() {
            List<String> priority = rules.sourcePriority();
            Comparator<String> byRank = Comparator.comparingInt(s -> {
                int idx = priority.indexOf(s);
                return idx >= 0 ? idx : Integer.MAX_VALUE;
            });
            return byRank.thenComparing(Comparator.naturalOrder());
        }

        /** Effective order for one field: override list first, then global order. */
        Comparator<String> orderFor(String field) {
            FieldRule fr = rules.fieldRule(field);
            if (fr.priority().isEmpty()) {
                return globalOrder();
            }
            List<String> override = fr.priority();
            Comparator<String> byRank = Comparator.comparingInt(s -> {
                int idx = override.indexOf(s);
                return idx >= 0 ? idx : Integer.MAX_VALUE;
            });
            // Override sources rank first; non-override sources keep the global relative order.
            return Comparator.comparingInt((String s) -> override.contains(s) ? 0 : 1)
                    .thenComparing(byRank)
                    .thenComparing(globalOrder());
        }

        Comparator<BioRecord> memberOrder() {
            return Comparator.comparing(BioRecord::source, globalOrder())
                    .thenComparing(BioRecord::localId);
        }
    }

    private ResolvedEntity merge(Context ctx, List<BioRecord> members, String version) {
        // members is already ordered by global priority; the first member anchors key/type.
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
        Map<String, String> winnerSources = new LinkedHashMap<>();
        String symbol = chooseField(ctx, members, BioRecord::symbol, "symbol", conflicts, winnerSources);
        String name = chooseField(ctx, members, BioRecord::name, "name", conflicts, winnerSources);
        String sequence = chooseField(ctx, members, BioRecord::sequence, "sequence", conflicts, winnerSources);
        String organism = chooseField(ctx, members, BioRecord::organism, "organism", conflicts, winnerSources);

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
                ctx.rules.id(),
                Map.copyOf(winnerSources));
    }

    private String chooseField(Context ctx, List<BioRecord> members,
                               java.util.function.Function<BioRecord, String> getter,
                               String field,
                               List<ResolutionConflict> conflicts,
                               Map<String, String> winnerSources) {
        FieldRule rule = ctx.rules.fieldRule(field);
        // Re-order candidate sources for this field once; winner selection is then deterministic.
        List<BioRecord> ordered = new ArrayList<>(members);
        Comparator<BioRecord> bySource = Comparator.comparing(BioRecord::source, ctx.orderFor(field));
        if (rule.strategy() == FieldResolutionStrategy.PREFER_LONGEST) {
            // Longest non-blank value wins; ties broken by the field's source order.
            ordered.sort(Comparator
                    .comparingInt((BioRecord r) -> {
                        String v = getter.apply(r);
                        return v == null ? -1 : v.length();
                    }).reversed()
                    .thenComparing(bySource)
                    .thenComparing(BioRecord::localId));
        } else {
            ordered.sort(bySource.thenComparing(BioRecord::localId));
        }

        String winnerSource = null;
        String winnerValue = null;
        for (BioRecord m : ordered) {
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
        winnerSources.put(field, winnerSource);
        // Losers are always reported in the field's deterministic source order.
        List<BioRecord> byFieldOrder = new ArrayList<>(members);
        byFieldOrder.sort(bySource.thenComparing(BioRecord::localId));
        Map<String, String> loserBySource = new LinkedHashMap<>();
        for (BioRecord m : byFieldOrder) {
            if (m.source().equals(winnerSource)) {
                continue;
            }
            String v = getter.apply(m);
            if (v != null && !v.equals(winnerValue)) {
                loserBySource.putIfAbsent(m.source(), v);
            }
        }
        if (!loserBySource.isEmpty()) {
            conflicts.add(new ResolutionConflict(field, winnerSource, winnerValue,
                    List.copyOf(loserBySource.keySet()), List.copyOf(loserBySource.values())));
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
