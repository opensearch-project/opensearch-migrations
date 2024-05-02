package com.rfs.worker;

import java.util.concurrent.atomic.AtomicReference;

/*
 * This class is a Singleton that contains global process state.
 */
public class GlobalData {
    private static final AtomicReference<GlobalData> instance = new AtomicReference<>();

    enum Phase {
        UNSET,
        SNAPSHOT_IN_PROGRESS,
        SNAPSHOT_COMPLETED,
        SNAPSHOT_FAILED,
    }    

    private AtomicReference<Phase> phase = new AtomicReference<>(Phase.UNSET);

    private GlobalData() {}

    public static GlobalData getInstance() {
        instance.updateAndGet(existingInstance -> {
            if (existingInstance == null) {
                return new GlobalData();
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
}
