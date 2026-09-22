package com.github.lisama569.biodbaggregatorjava.parser;

import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Looks up the parser for a given source format. */
@Component
public class ParserRegistry {

    private final Map<SourceFormat, SnapshotParser> parsers = new EnumMap<>(SourceFormat.class);

    public ParserRegistry(List<SnapshotParser> discovered) {
        for (SnapshotParser parser : discovered) {
            parsers.put(parser.format(), parser);
        }
    }

    public SnapshotParser parserFor(SourceFormat format) {
        SnapshotParser parser = parsers.get(format);
        if (parser == null) {
            throw new IllegalArgumentException("no parser registered for format " + format);
        }
        return parser;
    }
}
