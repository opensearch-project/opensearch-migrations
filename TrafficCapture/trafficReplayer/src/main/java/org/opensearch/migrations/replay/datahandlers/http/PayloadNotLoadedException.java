package org.opensearch.migrations.replay.datahandlers.http;

/**
 * This class is used to throw in LazyLoadingPayloadMap when the json payload has yet to be loaded.
 * We need to use an exception to trigger control flow since an end-user's json transformation
 * may be the thing that tries to access that key.  Think of this as being a page-fault for a
 * specific key.
 */
public class PayloadNotLoadedException extends RuntimeException {
    // TODO - investigate if it makes sense to store these in a ThreadLocal and reuse them
    public static PayloadNotLoadedException getInstance() {
        return new PayloadNotLoadedException();
    }
}
