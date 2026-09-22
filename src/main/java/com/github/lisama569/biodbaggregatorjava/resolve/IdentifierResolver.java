package com.github.lisama569.biodbaggregatorjava.resolve;

import com.github.lisama569.biodbaggregatorjava.model.AttributeDecision;
import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.EntityType;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionStatus;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.SourceValue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 跨源标识符解析器。
 *
 * <p>解析算法（完全确定性，可复现）：
 * <ol>
 *   <li><b>聚类</b>：把每条记录的所有标识符（namespace:value）作为节点，
 *       同一条记录内的标识符用并查集合并；不同记录共享同一标识符即连通，
 *       形成同一实体的簇。只允许相同 {@code type} 的记录合并，类型冲突单列为歧义。</li>
 *   <li><b>来源内冲突</b>：同一 source + primaryId 出现内容不一致的多条记录时，
 *       该实体标记 AMBIGUOUS（来源内身份不可判定，绝不静默取其一）。</li>
 *   <li><b>属性裁决</b>：symbol/name/sequence 每个属性独立裁决：
 *       先按来源优先级（{@link ResolutionRules#sourcePriority()}），
 *       平局按记录在来源文件内的行号 ordinal，再平局按值字典序——
 *       保证规则/数据版本不变时结论不变。</li>
 *   <li><b>跨源冲突</b>：若多个来源对同一属性给出不同值且来源优先级
 *       无法排序（来源不在优先级表，或不同值恰好在并列来源），
 *       属性标记 CONFLICT，保留全部候选值。</li>
 *   <li><b>规范标识符</b>：按命名空间优先级取簇内最高优先级标识符；
 *       同命名空间多个值本身是歧义信号，状态降级为 AMBIGUOUS。</li>
 * </ol>
 */
@Component
public class IdentifierResolver {

    public ResolutionResult resolve(List<BioRecord> records, ResolutionRules rules) {
        // 1) 建立标识符节点索引：key -> 节点 id
        Map<String, Integer> nodeIndex = new LinkedHashMap<>();
        List<String> nodeKeys = new ArrayList<>();
        for (BioRecord r : records) {
            for (Map.Entry<String, String> id : r.identifiers().entrySet()) {
                String key = nodeKey(r.type(), id.getKey(), id.getValue());
                if (!nodeIndex.containsKey(key)) {
                    nodeIndex.put(key, nodeKeys.size());
                    nodeKeys.add(key);
                }
            }
        }
        int n = nodeKeys.size();
        DSU dsu = new DSU(Math.max(n, 1));

        // 2) 同记录内标识符合并
        List<int[]> recordNodeGroups = new ArrayList<>();
        for (BioRecord r : records) {
            int first = -1;
            for (Map.Entry<String, String> e : r.identifiers().entrySet()) {
                int id = nodeIndex.get(nodeKey(r.type(), e.getKey(), e.getValue()));
                if (first < 0) {
                    first = id;
                } else {
                    dsu.union(first, id);
                }
            }
            recordNodeGroups.add(new int[]{first});
        }

        // 3) 按来源内主键分组（后续检测来源内冲突）
        Map<String, List<BioRecord>> byRecordKey = new LinkedHashMap<>();
        for (int ri = 0; ri < records.size(); ri++) {
            BioRecord r = records.get(ri);
            byRecordKey.computeIfAbsent(r.recordKey(), k -> new ArrayList<>()).add(r);
        }

        // 4) 来源内重复主键：完全相同 -> 去重；不一致 -> 保留全部记录进入簇，
        //    簇整体标记 AMBIGUOUS（绝不静默取其一，也不把记录丢弃）
        Map<String, BioRecord> dedupedByKey = new LinkedHashMap<>();
        List<BioRecord> conflictingRecords = new ArrayList<>();
        Map<String, String> withinSourceConflicts = new LinkedHashMap<>();
        for (Map.Entry<String, List<BioRecord>> e : byRecordKey.entrySet()) {
            List<BioRecord> rs = e.getValue();
            BioRecord first = rs.get(0);
            boolean identical = true;
            for (BioRecord r : rs) {
                if (!sameContent(first, r)) {
                    identical = false;
                    break;
                }
            }
            if (identical) {
                dedupedByKey.put(e.getKey(), first);
            } else {
                withinSourceConflicts.put(e.getKey(),
                        "来源 " + first.source() + " 内主键 " + first.primaryId()
                                + " 出现 " + rs.size() + " 条不一致记录（行: "
                                + rs.stream().map(r -> String.valueOf(r.ordinal())).toList()
                                + "）");
                conflictingRecords.addAll(rs);
            }
        }
        // 用去重后的干净记录 + 冲突组的全部记录重算簇成员
        Map<Integer, List<BioRecord>> cleanClusters = new TreeMap<>();
        List<BioRecord> membersForClustering = new ArrayList<>(dedupedByKey.values());
        membersForClustering.addAll(conflictingRecords);
        for (BioRecord r : membersForClustering) {
            Map.Entry<String, String> firstId =
                    r.identifiers().entrySet().iterator().next();
            int any = nodeIndex.get(nodeKey(r.type(), firstId.getKey(), firstId.getValue()));
            int root = dsu.find(any);
            cleanClusters.computeIfAbsent(root, k -> new ArrayList<>()).add(r);
        }

        // 5) 逐簇产出 ResolvedEntity
        Map<String, ResolvedEntity> byCanonical = new LinkedHashMap<>();
        Map<String, String> identLookup = new LinkedHashMap<>();
        int ambiguous = 0;
        int conflicts = 0;

        for (Map.Entry<Integer, List<BioRecord>> e : cleanClusters.entrySet()) {
            List<BioRecord> members = e.getValue();
            members.sort(Comparator
                    .comparingInt((BioRecord r) -> sourceRank(r.source(), rules))
                    .thenComparingLong(BioRecord::ordinal)
                    .thenComparing(BioRecord::primaryId));

            List<String> notes = new ArrayList<>();
            boolean ambiguousFlag = false;
            boolean conflictFlag = false;

            // 5.1 类型一致性
            var types = members.stream().map(BioRecord::type).distinct().toList();
            if (types.size() > 1) {
                ambiguousFlag = true;
                notes.add("同一标识符簇出现多种实体类型: " + types);
            }

            // 5.2 来源内冲突命中本簇
            for (BioRecord r : members) {
                String note = withinSourceConflicts.get(r.recordKey());
                if (note != null) {
                    ambiguousFlag = true;
                    notes.add(note);
                }
            }

            // 5.3 汇总标识符，检测同命名空间多值
            TreeMap<String, java.util.TreeSet<String>> idByNamespace = new TreeMap<>();
            for (BioRecord r : members) {
                for (Map.Entry<String, String> id : r.identifiers().entrySet()) {
                    idByNamespace.computeIfAbsent(id.getKey(), k -> new java.util.TreeSet<>())
                            .add(id.getValue());
                }
            }
            for (Map.Entry<String, java.util.TreeSet<String>> id : idByNamespace.entrySet()) {
                if (id.getValue().size() > 1
                        && !id.getKey().startsWith("local:")
                        && !id.getKey().equals("symbol")) {
                    ambiguousFlag = true;
                    notes.add("命名空间 " + id.getKey() + " 在同一实体内存在多个不同值: "
                            + id.getValue());
                }
            }

            // 5.4 属性裁决
            Map<String, AttributeDecision> attrDecisions = new LinkedHashMap<>();
            for (String attr : List.of("symbol", "name", "sequence")) {
                List<SourceValue> candidates = new ArrayList<>();
                for (BioRecord r : members) {
                    String v = switch (attr) {
                        case "symbol" -> r.symbol();
                        case "name" -> r.name();
                        case "sequence" -> r.sequence();
                        default -> null;
                    };
                    if (v != null && !v.isBlank()) {
                        candidates.add(new SourceValue(r.source(), r.primaryId(), v, r.ordinal()));
                    }
                }
                if (candidates.isEmpty()) {
                    continue;
                }
                AttributeDecision decision = decideAttribute(attr, candidates, rules);
                attrDecisions.put(attr, decision);
                if (decision.tieBreak()) {
                    conflictFlag = true;
                    notes.add("属性 " + attr + " 跨来源冲突，已按平局规则确定性选中 '"
                            + decision.chosen() + "'，但保留全部候选: "
                            + candidates.stream().map(SourceValue::value).distinct().toList());
                }
            }

            // 5.5 canonicalId：按命名空间优先级
            String canonicalId = null;
            for (String ns : rules.namespacePriority()) {
                java.util.TreeSet<String> vals = idByNamespace.get(ns);
                if (vals != null && !vals.isEmpty()) {
                    canonicalId = identKey(ns, vals.first());
                    break;
                }
            }
            if (canonicalId == null) {
                // 全部命名空间都不在优先级表：取排序后的第一个，确定性
                Map.Entry<String, java.util.TreeSet<String>> first =
                        idByNamespace.firstEntry();
                canonicalId = identKey(first.getKey(), first.getValue().first());
                notes.add("簇内标识符命名空间均不在优先级表，canonicalId 按字典序确定: "
                        + canonicalId);
            }

            Map<String, String> flatIds = new TreeMap<>();
            idByNamespace.forEach((ns, vals) -> flatIds.put(ns, String.join("|", vals)));

            List<String> memberKeys = members.stream().map(BioRecord::recordKey)
                    .distinct().sorted().toList();

            ResolutionStatus status = ambiguousFlag
                    ? ResolutionStatus.AMBIGUOUS
                    : conflictFlag ? ResolutionStatus.CONFLICT
                    : ResolutionStatus.RESOLVED;
            if (ambiguousFlag) {
                ambiguous++;
            }
            if (conflictFlag && !ambiguousFlag) {
                conflicts++;
            }

            ResolvedEntity entity = new ResolvedEntity(
                    canonicalId,
                    types.get(0),
                    status,
                    Map.copyOf(flatIds),
                    Map.copyOf(attrDecisions),
                    memberKeys,
                    notes.isEmpty() ? null : String.join("; ", notes));
            byCanonical.put(canonicalId, entity);

            for (Map.Entry<String, String> id : flatIds.entrySet()) {
                for (String v : id.getValue().split("\\|")) {
                    identLookup.put(identKey(id.getKey(), v), canonicalId);
                }
            }
        }

        return new ResolutionResult(
                rules.rulesVersion(),
                Map.copyOf(byCanonical),
                Map.copyOf(identLookup),
                ambiguous,
                conflicts);
    }

    private static AttributeDecision decideAttribute(String attr,
                                                      List<SourceValue> candidates,
                                                      ResolutionRules rules) {
        // 去重保留出处：相同值合并来源
        Map<String, List<SourceValue>> byValue = new LinkedHashMap<>();
        for (SourceValue sv : candidates) {
            byValue.computeIfAbsent(sv.value(), k -> new ArrayList<>()).add(sv);
        }
        if (byValue.size() == 1) {
            Map.Entry<String, List<SourceValue>> only = byValue.entrySet().iterator().next();
            return new AttributeDecision(attr, only.getKey(),
                    "全部来源一致: " + only.getKey(),
                    List.copyOf(candidates), false);
        }

        // 每个值的“最佳来源名次”
        record Scored(String value, int rank, long ordinal, List<SourceValue> sources)
                implements Comparable<Scored> {
            @Override
            public int compareTo(Scored o) {
                int c = Integer.compare(this.rank, o.rank);
                if (c != 0) {
                    return c;
                }
                c = Long.compare(this.ordinal, o.ordinal);
                if (c != 0) {
                    return c;
                }
                return this.value.compareTo(o.value);
            }
        }

        List<Scored> scored = new ArrayList<>();
        for (Map.Entry<String, List<SourceValue>> e : byValue.entrySet()) {
            int bestRank = Integer.MAX_VALUE;
            long bestOrdinal = Long.MAX_VALUE;
            for (SourceValue sv : e.getValue()) {
                int rank = sourceRank(sv.source(), rules);
                if (rank < bestRank || (rank == bestRank && sv.ordinal() < bestOrdinal)) {
                    bestRank = rank;
                    bestOrdinal = sv.ordinal();
                }
            }
            scored.add(new Scored(e.getKey(), bestRank, bestOrdinal, e.getValue()));
        }
        scored.sort(Comparator.naturalOrder());
        Scored winner = scored.get(0);
        Scored runner = scored.get(1);

        // 真正的裁决条件：获胜来源名次严格优先
        if (winner.rank < runner.rank) {
            String winnerSource = winner.sources().stream()
                    .filter(sv -> sourceRank(sv.source(), rules) == winner.rank)
                    .map(SourceValue::source).findFirst().orElse("?");
            String basis = "属性 " + attr + " 存在跨源差异，按来源优先级 "
                    + rules.sourcePriority() + " 裁决: 来源 " + winnerSource
                    + "(rank=" + winner.rank + ") 胜出，选中 '" + winner.value + "'";
            return new AttributeDecision(attr, winner.value, basis,
                    List.copyOf(candidates), false);
        }
        // 名次相同（含都不在优先级表）-> 不静默取其一：给出确定性平局结论并标记冲突，
        // 同时保留全部候选值，实体状态降为 CONFLICT。
        String basis = "属性 " + attr + " 跨来源值不同且来源优先级相同(rank=" + winner.rank
                + ")，启用确定性平局规则(ordinal=" + winner.ordinal()
                + ", 值字典序)选中 '" + winner.value + "'，全部候选予以保留";
        return new AttributeDecision(attr, winner.value, basis,
                List.copyOf(candidates), true);
    }

    private static int sourceRank(String source, ResolutionRules rules) {
        int idx = rules.sourcePriority().indexOf(source);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    private static boolean sameContent(BioRecord a, BioRecord b) {
        return a.identifiers().equals(b.identifiers())
                && nullSafeEq(a.symbol(), b.symbol())
                && nullSafeEq(a.name(), b.name())
                && nullSafeEq(a.sequence(), b.sequence());
    }

    private static boolean nullSafeEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String identKey(String namespace, String value) {
        return namespace + ":" + value;
    }

    /**
     * 并查集节点键。symbol 在基因与其编码蛋白之间天然共享，
     * 若全局连通会把两种实体错误合并，因此 symbol 按实体类型隔离；
     * 其余命名空间（uniprot/refseq/geneId 等）全局连通。
     */
    private static String nodeKey(EntityType type, String namespace, String value) {
        if ("symbol".equals(namespace)) {
            return type.name() + "|" + namespace + ":" + value;
        }
        return identKey(namespace, value);
    }

    /** 查询索引键：与 {@link #nodeKey} 保持一致的类型隔离规则。 */
    static String lookupKey(EntityType type, String namespace, String value) {
        return nodeKey(type, namespace, value);
    }

    /** 并查集。 */
    private static final class DSU {
        private final int[] parent;

        DSU(int n) {
            this.parent = new int[n];
            for (int i = 0; i < n; i++) {
                parent[i] = i;
            }
        }

        int find(int x) {
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

        void union(int a, int b) {
            int ra = find(a);
            int rb = find(b);
            if (ra != rb) {
                // 让较小的节点 id 做根，进一步保证确定性
                if (ra < rb) {
                    parent[rb] = ra;
                } else {
                    parent[ra] = rb;
                }
            }
        }
    }
}
