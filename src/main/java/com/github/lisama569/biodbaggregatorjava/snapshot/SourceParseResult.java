package com.github.lisama569.biodbaggregatorjava.snapshot;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;

import java.util.List;

/**
 * 单个来源快照的解析结果：接受记录 + 被拒绝/冲突记录。
 */
public record SourceParseResult(
        String source,
        List<BioRecord> accepted,
        List<RejectedRecord> rejected
) {
}
