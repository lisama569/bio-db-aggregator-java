package com.github.lisama569.biodbaggregatorjava.snapshot;

/** 支持的本地快照来源格式。 */
public enum SourceFormat {
    /** 每行一个 JSON 对象。 */
    JSONL,
    /** 逗号分隔，首行表头。 */
    CSV,
    /** 制表符分隔，首行表头。 */
    TSV
}
