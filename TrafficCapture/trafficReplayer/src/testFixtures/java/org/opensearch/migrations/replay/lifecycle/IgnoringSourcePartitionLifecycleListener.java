package org.opensearch.migrations.replay.lifecycle;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: SourcePartitionLifecycleListener . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.util.Collection;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

*/
// REBUILD-LIMBO-END(G10)
/**
 * Explicit test listener for cases that exercise a source without asserting lifecycle events.
 */
// REBUILD-LIMBO-START(G10)
/*
public final class IgnoringSourcePartitionLifecycleListener
    implements SourcePartitionLifecycleListener {

    @Override
    public void onAssigned(Collection<SourcePartitionKey> partitions) {}

    @Override
    public void onRevoked(Collection<SourcePartitionKey> partitions) {}

    @Override
    public void onRetired(Collection<SourcePartitionKey> partitions) {}
}

*/
// REBUILD-LIMBO-END(G10)