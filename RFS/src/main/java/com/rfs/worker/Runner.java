package com.rfs.worker;

import lombok.Lombok;
import lombok.SneakyThrows;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Optional;

import com.rfs.cms.CmsEntry;
import com.rfs.common.RfsException;

public abstract interface Runner {
    abstract void runInternal() throws IOException;

    @SneakyThrows
    default void run() {
        runInternal();
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
