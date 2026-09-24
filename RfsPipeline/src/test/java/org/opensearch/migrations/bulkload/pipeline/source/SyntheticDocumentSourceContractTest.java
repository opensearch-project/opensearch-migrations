package org.opensearch.migrations.bulkload.pipeline.source;

/** Pins the contract for the in-memory fixture the sink-side tests rely on. */
class SyntheticDocumentSourceContractTest extends DocumentSourceContractTest {

    private static final String COLLECTION = "synthetic_contract";

    @Override
    protected DocumentSource newSource() {
        return new SyntheticDocumentSource(COLLECTION, 3, 5);
    }

    @Override
    protected String collectionUnderTest() {
        return COLLECTION;
    }
}
