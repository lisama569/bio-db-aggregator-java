package com.github.lisama569.biodbaggregatorjava.snapshot;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;

/** 单行归一结果：要么接受、要么拒绝（带可区分原因）。 */
public sealed interface NormalizeOutcome {

    record Accepted(BioRecord record) implements NormalizeOutcome {
    }

    record Rejected(RejectedRecord rejected) implements NormalizeOutcome {
    }
}
