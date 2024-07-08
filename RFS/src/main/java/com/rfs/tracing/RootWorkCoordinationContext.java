package com.rfs.tracing;

import io.opentelemetry.api.OpenTelemetry;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.RootOtelContext;

public class RootWorkCoordinationContext extends RootOtelContext {
    private static final String SCOPE_NAME = "workCoordination";

    public final WorkCoordinationContexts.InitializeCoordinatorStateContext.MetricInstruments initializeMetrics;
    public final WorkCoordinationContexts.CreateUnassignedWorkItemContext.MetricInstruments createUnassignedWorkMetrics;
    public final WorkCoordinationContexts.Refresh.MetricInstruments refreshMetrics;
    public final WorkCoordinationContexts.PendingItems.MetricInstruments pendingItemsMetrics;
    public final WorkCoordinationContexts.AcquireSpecificWorkContext.MetricInstruments acquireSpecificWorkMetrics;
    public final WorkCoordinationContexts.CompleteWorkItemContext.MetricInstruments completeWorkMetrics;
    public final WorkCoordinationContexts.AcquireNextWorkItemContext.MetricInstruments acquireNextWorkMetrics;

    public RootWorkCoordinationContext(OpenTelemetry sdk, IContextTracker contextTracker) {
        super(SCOPE_NAME, contextTracker, sdk);
        var meter = this.getMeterProvider().get(SCOPE_NAME);

        initializeMetrics = WorkCoordinationContexts.InitializeCoordinatorStateContext.makeMetrics(meter);
        createUnassignedWorkMetrics = WorkCoordinationContexts.CreateUnassignedWorkItemContext.makeMetrics(meter);
        refreshMetrics = WorkCoordinationContexts.Refresh.makeMetrics(meter);
        pendingItemsMetrics = WorkCoordinationContexts.PendingItems.makeMetrics(meter);
        acquireSpecificWorkMetrics = WorkCoordinationContexts.AcquireSpecificWorkContext.makeMetrics(meter);
        completeWorkMetrics = WorkCoordinationContexts.CompleteWorkItemContext.makeMetrics(meter);
        acquireNextWorkMetrics = WorkCoordinationContexts.AcquireNextWorkItemContext.makeMetrics(meter);
    }


    public IWorkCoordinationContexts.IInitializeCoordinatorStateContext createCoordinationInitializationStateContext() {
        return new WorkCoordinationContexts.InitializeCoordinatorStateContext(this);
    }

    public IWorkCoordinationContexts.IPendingWorkItemsContext createItemsPendingContext() {
        return new WorkCoordinationContexts.PendingItems(this);
    }
    public IWorkCoordinationContexts.ICreateUnassignedWorkItemContext createUnassignedWorkContext() {
        return createUnassignedWorkContext(null);
    }

    public IWorkCoordinationContexts.ICreateUnassignedWorkItemContext
    createUnassignedWorkContext(IScopedInstrumentationAttributes enclosingScope) {
        return new WorkCoordinationContexts.CreateUnassignedWorkItemContext(this, enclosingScope);
    }
    public IWorkCoordinationContexts.IAcquireSpecificWorkContext createAcquireSpecificItemContext() {
        return createAcquireSpecificItemContext(null);
    }

    public IWorkCoordinationContexts.IAcquireSpecificWorkContext
    createAcquireSpecificItemContext(IScopedInstrumentationAttributes enclosingScope) {
        return new WorkCoordinationContexts.AcquireSpecificWorkContext(this, enclosingScope);
    }

    public IWorkCoordinationContexts.IAcquireNextWorkItemContext
    createAcquireNextItemContext() {
        return createAcquireNextItemContext(null);
    }

    public IWorkCoordinationContexts.IAcquireNextWorkItemContext
    createAcquireNextItemContext(IScopedInstrumentationAttributes enclosingScope) {
        return new WorkCoordinationContexts.AcquireNextWorkItemContext(this, enclosingScope);
    }

    public IWorkCoordinationContexts.ICompleteWorkItemContext createCompleteWorkContext() {
        return createCompleteWorkContext(null);
    }

    public IWorkCoordinationContexts.ICompleteWorkItemContext
    createCompleteWorkContext(IScopedInstrumentationAttributes enclosingScope) {
        return new WorkCoordinationContexts.CompleteWorkItemContext(this, enclosingScope);
    }
}
