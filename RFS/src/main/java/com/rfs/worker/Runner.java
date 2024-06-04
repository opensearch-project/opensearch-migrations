package com.rfs.worker;

import org.apache.logging.log4j.Logger;

import java.util.Optional;

import com.rfs.cms.CmsEntry;
import com.rfs.common.RfsException;

public abstract interface Runner {
    abstract void runInternal();
    abstract String getPhaseName();
    abstract Logger getLogger();

    default void run() {
        try {
            getLogger().info("Checking if work remains in the " + getPhaseName() +" Phase...");
            runInternal();
            getLogger().info(getPhaseName() + " Phase is complete");
        } catch (Exception e) {
            getLogger().error(getPhaseName() + " Phase failed w/ an exception");

            throw e;
        }
    }

    public static class PhaseFailed extends RfsException {
        public final GlobalState.Phase phase;
        public final WorkerStep nextStep;
        public final Optional<CmsEntry.Base> cmsEntry;
        public final Exception e;

        public PhaseFailed(String message, GlobalState.Phase phase, WorkerStep nextStep, Optional<CmsEntry.Base> cmsEntry, Exception e) {
            super(message);
            this.phase = phase;
            this.nextStep = nextStep;
            this.cmsEntry = cmsEntry;
            this.e = e;
        }
    }
}
