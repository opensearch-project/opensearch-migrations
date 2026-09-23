package org.opensearch.migrations.tracing;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: TestContext . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public class InstrumentationTest {

    protected TestContext rootContext;

    protected TestContext makeInstrumentationContext() {
        return TestContext.noOtelTracking();
    }

    @BeforeEach
    protected void initializeContext() {
        rootContext = makeInstrumentationContext();
    }

    @AfterEach
    protected void teardownContext() {
        rootContext.close();
        rootContext = null;
    }
}

*/
// REBUILD-LIMBO-END(G10)