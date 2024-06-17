package com.rfs.common;

import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Slf4j
public class TryHandlePhaseFailure {
    @FunctionalInterface
    public interface TryBlock {
        void run() throws Exception;
    }

    public static void executeWithTryCatch(TryBlock tryBlock) throws Exception {
        try {
            tryBlock.run();
        } catch (Exception e) {
            log.atError().setMessage("Unexpected error running RfsWorker").setCause(e).log();
            throw e;
        }
    }
}
