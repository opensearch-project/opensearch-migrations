package com.rfs.worker;

import java.util.concurrent.atomic.AtomicReference;

/*
 * This class is a Singleton that contains global process state.
 */
public class GlobalState {
    private static final AtomicReference<GlobalState> instance = new AtomicReference<>();

    public enum Phase {
        UNSET,
        SNAPSHOT_IN_PROGRESS,
        SNAPSHOT_COMPLETED,
        SNAPSHOT_FAILED,
        METADATA_IN_PROGRESS,
        METADATA_COMPLETED,
        METADATA_FAILED
    }    

    private AtomicReference<Phase> phase = new AtomicReference<>(Phase.UNSET);
    private AtomicReference<OpenSearchWorkItem> workItem = new AtomicReference<>(null);

    private GlobalState() {}

    public static GlobalState getInstance() {
        instance.updateAndGet(existingInstance -> {
            if (existingInstance == null) {
                return new GlobalState();
            } else {
                return existingInstance;
            }
        });
        return instance.get();
    }

    public void updatePhase(Phase newValue) {
        phase.set(newValue);
    }

    public Phase getPhase() {
        return phase.get();
    }

    public void updateWorkItem(OpenSearchWorkItem newValue) {
        workItem.set(newValue);
    }

    public OpenSearchWorkItem getWorkItem() {
        return workItem.get();
    }
}
