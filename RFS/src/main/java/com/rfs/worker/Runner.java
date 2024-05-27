package com.rfs.worker;

import java.util.Arrays;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.cms.CmsEntry;

public abstract interface Runner {
    public abstract void run();

    default ObjectNode getPhaseFailureRecord(GlobalState.Phase phase, WorkerStep nextStep, Optional<CmsEntry.Base> cmsEntry, Exception e) {
        ObjectNode errorBlob = new ObjectMapper().createObjectNode();
        errorBlob.put("exceptionMessage", e.getMessage());
        errorBlob.put("exceptionClass", e.getClass().getSimpleName());
        errorBlob.put("exceptionTrace", Arrays.toString(e.getStackTrace()));

        errorBlob.put("phase", phase.toString());

        String currentStep = (nextStep != null) ? nextStep.getClass().getSimpleName() : "null";
        errorBlob.put("currentStep", currentStep);

        String currentEntry = (cmsEntry.isPresent()) ? cmsEntry.toString() : "null";
        errorBlob.put("cmsEntry", currentEntry);
        return errorBlob;
    }
}
