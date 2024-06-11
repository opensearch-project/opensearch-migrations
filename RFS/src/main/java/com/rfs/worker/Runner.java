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
            getLogger().error(getPhaseName() + " Phase failed w/ an exception ", e);

            throw e;
        }
    }

    public static class PhaseFailed extends RfsException {
        public final GlobalState.Phase phase;
        public final Optional<CmsEntry.Base> cmsEntry;

        public PhaseFailed(String message, GlobalState.Phase phase, Optional<CmsEntry.Base> cmsEntry, Exception e) {
            super(message, e);
            this.phase = phase;
            this.cmsEntry = cmsEntry;
        }
    }
}
