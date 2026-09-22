package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.snapshot.SnapshotInput;
import com.github.lisama569.biodbaggregatorjava.snapshot.SourceFormat;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 测试夹具加载工具。 */
final class SnapshotTestSupport {

    private SnapshotTestSupport() {
    }

    static String resource(String name) {
        try {
            return new String(new ClassPathResource("snapshots/" + name).getInputStream()
                    .readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static SnapshotInput input(String source, SourceFormat format, String resourceName) {
        return new SnapshotInput(source, format, resource(resourceName));
    }
}
