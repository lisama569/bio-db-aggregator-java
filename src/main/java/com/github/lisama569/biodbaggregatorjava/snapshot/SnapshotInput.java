package com.github.lisama569.biodbaggregatorjava.snapshot;

/**
 * 单个来源的本地快照输入。
 *
 * @param source 来源名称（同时决定标识符优先级）
 * @param format 文件格式
 * @param content 文件文本内容
 */
public record SnapshotInput(
        String source,
        SourceFormat format,
        String content
) {
}
