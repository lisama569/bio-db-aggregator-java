package com.github.lisama569.biodbaggregatorjava.snapshot;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;

import java.util.List;
import java.util.Map;

/**
 * 不可变快照：一次导入的全部归一记录、拒绝记录与预计算解析结论。
 * 查询线程通过原子引用整体读取，保证看到同一版本的完整视图。
 */
public final class Snapshot {

    private final String version;
    private final String contentHash;
    private final List<BioRecord> records;
    private final List<RejectedRecord> rejected;
    private final Map<String, ResolvedEntity> entitiesByCanonicalId;
    private final Map<String, String> canonicalIdByIdentifier;

    public Snapshot(String version,
                    String contentHash,
                    List<BioRecord> records,
                    List<RejectedRecord> rejected,
                    Map<String, ResolvedEntity> entitiesByCanonicalId,
                    Map<String, String> canonicalIdByIdentifier) {
        this.version = version;
        this.contentHash = contentHash;
        this.records = records;
        this.rejected = rejected;
        this.entitiesByCanonicalId = entitiesByCanonicalId;
        this.canonicalIdByIdentifier = canonicalIdByIdentifier;
    }

    public String version() {
        return version;
    }

    public String contentHash() {
        return contentHash;
    }

    public List<BioRecord> records() {
        return records;
    }

    public List<RejectedRecord> rejected() {
        return rejected;
    }

    public Map<String, ResolvedEntity> entitiesByCanonicalId() {
        return entitiesByCanonicalId;
    }

    public Map<String, String> canonicalIdByIdentifier() {
        return canonicalIdByIdentifier;
    }
}
