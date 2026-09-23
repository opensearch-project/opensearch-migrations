package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import lombok.NonNull;

public class TupleParserChainConsumer implements Consumer<SourceTargetCaptureTuple> {
    private final BiConsumer<SourceTargetCaptureTuple, ParsedHttpMessagesAsDicts> innerConsumer;

    public TupleParserChainConsumer(@NonNull BiConsumer<SourceTargetCaptureTuple, ParsedHttpMessagesAsDicts> innerConsumer) {
        this.innerConsumer = innerConsumer;
    }

    @Override
    public void accept(SourceTargetCaptureTuple tuple) {
        var parsedMsgs = new ParsedHttpMessagesAsDicts(tuple);
        innerConsumer.accept(tuple, parsedMsgs);
    }
}

*/
// REBUILD-LIMBO-END(G11)