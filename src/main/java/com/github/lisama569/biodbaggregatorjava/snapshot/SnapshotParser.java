package com.github.lisama569.biodbaggregatorjava.snapshot;

/** 来源快照解析器：每种格式一个实现。 */
public interface SnapshotParser {

    /** 是否支持该格式。 */
    boolean supports(SourceFormat format);

    /** 解析单个来源快照，任何坏记录都进入 rejected，不得抛异常静默丢弃。 */
    SourceParseResult parse(SnapshotInput input);
}
