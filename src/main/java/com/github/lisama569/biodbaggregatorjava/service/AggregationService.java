package com.github.lisama569.biodbaggregatorjava.service;

import com.github.lisama569.biodbaggregatorjava.model.EntityType;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.snapshot.Snapshot;
import com.github.lisama569.biodbaggregatorjava.store.SnapshotInfo;
import com.github.lisama569.biodbaggregatorjava.store.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 聚合查询服务。
 *
 * <p>一致性语义：每个查询方法在入口处只读取一次当前快照引用，
 * 随后全程基于这个不可变 {@link Snapshot} 计算。即便查询进行中
 * 另一个线程导入/回退切换了版本，本查询看到的仍是同一版本的完整视图，
 * 绝不会新旧混合。
 */
@Service
public class AggregationService {

    private static final Logger log = LoggerFactory.getLogger(AggregationService.class);

    private final SnapshotStore store;

    public AggregationService(SnapshotStore store) {
        this.store = store;
    }

    /** 当前版本号。 */
    public Optional<String> currentVersion() {
        return store.current().map(Snapshot::version);
    }

    /** 在调用入口钉住一个完整快照视图，供一次查询内的多个读取保持同一版本。 */
    public Snapshot pin() {
        return store.current().orElse(null);
    }

    /** 在已钉住的快照上按标识符解析。 */
    public java.util.Optional<ResolvedEntity> resolveOn(Snapshot pinned,
                                                        String namespace, String value) {
        if (pinned == null) {
            return java.util.Optional.empty();
        }
        var lookup = pinned.canonicalIdByIdentifier();
        String canonical = lookup.get(namespace + ":" + value);
        if (canonical == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(
                pinned.entitiesByCanonicalId().get(canonical));
    }

    /**
     * 按任意标识符解析实体（如 {@code uniprot:P12345} 或 {@code geneId:7157}）。
     * symbol 在基因/蛋白间共享，按 symbol 查询时需带类型。
     */
    public Optional<ResolvedEntity> resolveByIdentifier(String namespace, String value) {
        return resolveByIdentifier(namespace, value, null);
    }

    /** 按标识符解析实体；type 仅对 symbol 命名空间有意义。 */
    public Optional<ResolvedEntity> resolveByIdentifier(String namespace, String value,
                                                        EntityType type) {
        Snapshot pinned = store.current().orElse(null);
        if (pinned == null) {
            return Optional.empty();
        }
        var lookup = pinned.canonicalIdByIdentifier();
        String canonical = lookup.get(namespace + ":" + value);
        if (canonical == null && "symbol".equals(namespace) && type != null) {
            canonical = lookup.get(type.name() + "|symbol:" + value);
        }
        if (canonical == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(pinned.entitiesByCanonicalId().get(canonical));
    }

    /** 当前版本全部实体（按 canonicalId 排序，结论确定）。 */
    public List<ResolvedEntity> listEntities() {
        Snapshot pinned = store.current().orElse(null);
        if (pinned == null) {
            return List.of();
        }
        return pinned.entitiesByCanonicalId().values().stream()
                .sorted((a, b) -> a.canonicalId().compareTo(b.canonicalId()))
                .toList();
    }

    /** 读取指定历史版本的实体视图（不改变当前版本）。 */
    public List<ResolvedEntity> listEntitiesAt(String version) {
        return store.byVersion(version)
                .map(s -> s.entitiesByCanonicalId().values().stream()
                        .sorted((a, b) -> a.canonicalId().compareTo(b.canonicalId()))
                        .toList())
                .orElse(List.of());
    }

    /** 当前快照被拒绝的记录（含原因）。 */
    public List<com.github.lisama569.biodbaggregatorjava.model.RejectedRecord> rejected() {
        return store.current()
                .map(Snapshot::rejected)
                .orElse(List.of());
    }

    /** 回退到历史版本，原子切换。 */
    public boolean rollback(String version) {
        boolean ok = store.rollback(version).isPresent();
        log.info("[snapshot-rollback] target={} success={}", version, ok);
        return ok;
    }

    /** 全部版本。 */
    public List<SnapshotInfo> versions() {
        return store.list();
    }
}
