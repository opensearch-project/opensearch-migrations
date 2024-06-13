package com.rfs.common;

import java.util.Arrays;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.cms.CmsEntry;
import com.rfs.worker.GlobalState;
import com.rfs.worker.Runner;
import com.rfs.worker.WorkerStep;

public class TryHandlePhaseFailure {
    private static final Logger logger = LogManager.getLogger(TryHandlePhaseFailure.class);

    @FunctionalInterface
    public static interface TryBlock {
        void run() throws Exception;
    }

    public static void executeWithTryCatch(TryBlock tryBlock) throws Exception {
        try {
            tryBlock.run();
        } catch (Runner.PhaseFailed e) {
            logPhaseFailureRecord(e.phase, e.nextStep, e.cmsEntry, e.getCause());
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error running RfsWorker", e);
            throw e;
        }
    }

    public static void logPhaseFailureRecord(GlobalState.Phase phase, WorkerStep nextStep, Optional<CmsEntry.Base> cmsEntry, Throwable e) {
        ObjectNode errorBlob = new ObjectMapper().createObjectNode();
        errorBlob.put("exceptionMessage", e.getMessage());
        errorBlob.put("exceptionClass", e.getClass().getSimpleName());
        errorBlob.put("exceptionTrace", Arrays.toString(e.getStackTrace()));

        errorBlob.put("phase", phase.toString());

        String currentStep = (nextStep != null) ? nextStep.getClass().getSimpleName() : "null";
        errorBlob.put("currentStep", currentStep);

        String currentEntry = (cmsEntry.isPresent()) ? cmsEntry.get().toRepresentationString() : "null";
        errorBlob.put("cmsEntry", currentEntry);

        
        logger.error(errorBlob.toString());
    }    
}
