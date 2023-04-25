package org.opensearch.migrations.trafficcapture.model;

/**
 * This class is a minimal for now but should be enhanced to capture metadata concerning a given OutputStream
 */
public class CaptureOutputStreamMetadata {

    private boolean isMutating;

    public CaptureOutputStreamMetadata() {
    }

    public boolean isMutating() {
        return isMutating;
    }

    public void setMutating(boolean mutating) {
        isMutating = mutating;
    }
}
