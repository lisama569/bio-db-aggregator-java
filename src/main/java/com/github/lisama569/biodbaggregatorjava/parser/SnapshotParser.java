package com.github.lisama569.biodbaggregatorjava.parser;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;

import java.util.List;

/** Parses raw text of one local snapshot export into normalized records and rejections. */
public interface SnapshotParser {

    SourceFormat format();

    /**
     * @param source   logical source name (stamped on every produced record)
     * @param rawInput raw snapshot text
     * @return accepted normalized records plus distinguishable rejections
     */
    ParseResult parse(String source, String rawInput);

    /** Parsed outcome: accepted records, rejections, and within-source duplicate conflicts. */
    record ParseResult(List<BioRecord> accepted, List<RejectedRecord> rejected, List<RejectedRecord> conflicts) {
    }
}
