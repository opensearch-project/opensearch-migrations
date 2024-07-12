package org.opensearch.migrations.replay;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import lombok.NonNull;

public class TupleParserChainConsumer implements Consumer<SourceTargetCaptureTuple> {
    private final BiConsumer<SourceTargetCaptureTuple, ParsedHttpMessagesAsDicts> innerConsumer;

    public TupleParserChainConsumer(
        @NonNull BiConsumer<SourceTargetCaptureTuple, ParsedHttpMessagesAsDicts> innerConsumer
    ) {
        this.innerConsumer = innerConsumer;
    }

    @Override
    public void accept(SourceTargetCaptureTuple tuple) {
        var parsedMsgs = new ParsedHttpMessagesAsDicts(tuple);
        innerConsumer.accept(tuple, parsedMsgs);
    }
}
