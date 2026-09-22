package com.github.lisama569.biodbaggregatorjava.store;

import com.github.lisama569.biodbaggregatorjava.snapshot.Snapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 快照仓库（纯内存，模拟本地快照目录）。
 *
 * <p>并发语义：
 * <ul>
 *   <li>当前版本由 {@link AtomicReference} 持有，{@link #install(Snapshot)} 通过
 *       {@code compareAndSet} 整体替换，查询线程要么看到旧版本完整视图、
 *       要么看到新版本完整视图，绝不出现新旧混合；</li>
 *   <li>历史版本不可变、永不被覆盖，{@link #rollback(String)} 只是把原子引用
 *       指回历史快照（历史仍保留，回退本身不生成新版本）；</li>
 *   <li>以内容哈希 {@code contentHash} 去重：相同内容重复导入直接复用既有版本，
 *       不产生新版本、不产生计数漂移。</li>
 * </ul>
 */
@Component
public class SnapshotStore {

    private final ConcurrentMap<String, Snapshot> byVersion = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> versionByContentHash = new ConcurrentHashMap<>();
    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    /** 当前快照（可能为空）。 */
    public Optional<Snapshot> current() {
        return Optional.ofNullable(current.get());
    }

    /** 按版本获取快照。 */
    public Optional<Snapshot> byVersion(String version) {
        return Optional.ofNullable(byVersion.get(version));
    }

    /**
     * 幂等安装，语义为“把该内容对应的快照原子地置为当前版本”：
     * <ul>
     *   <li>内容首次出现：注册新版本并原子切换为当前版本；</li>
     *   <li>内容已存在（包括回退后再次导入同一历史内容）：不产生新版本、
     *       不产生计数漂移，但把当前指针原子切回该既有版本，
     *       标记 idempotentReuse=true。</li>
     * </ul>
     */
    public InstallOutcome install(Snapshot snapshot) {
        String existingVersion = versionByContentHash.putIfAbsent(
                snapshot.contentHash(), snapshot.version());
        if (existingVersion != null) {
            Snapshot existing = byVersion.get(existingVersion);
            current.set(existing);
            return new InstallOutcome(existing, true);
        }
        byVersion.put(snapshot.version(), snapshot);
        current.set(snapshot);
        return new InstallOutcome(snapshot, false);
    }

    /**
     * 回退到历史版本（必须是已经导入过的版本）。原子切换，历史数据不变。
     */
    public Optional<Snapshot> rollback(String version) {
        Snapshot target = byVersion.get(version);
        if (target == null) {
            return Optional.empty();
        }
        current.set(target);
        return Optional.of(target);
    }

    /** 全部版本元信息（按版本名排序），标注哪个是当前版本。 */
    public List<SnapshotInfo> list() {
        String currentVersion = current.get() == null ? null : current.get().version();
        List<SnapshotInfo> infos = new ArrayList<>();
        byVersion.values().stream()
                .map(s -> new SnapshotInfo(
                        s.version(),
                        s.contentHash(),
                        s.records().size(),
                        s.rejected().size(),
                        s.version().equals(currentVersion)))
                .sorted((a, b) -> a.version().compareTo(b.version()))
                .forEach(infos::add);
        return List.copyOf(infos);
    }

    /** 安装结果。 */
    public record InstallOutcome(Snapshot snapshot, boolean idempotentReuse) {
    }
}
