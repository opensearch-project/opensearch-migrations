package org.opensearch.migrations.transform.shim.reporting;

/**
 * Pluggable sink for validation report documents.
 * Implementations must be thread-safe — submit() may be called
 * from Netty event loop threads concurrently.
 */
public interface ReportingSink extends AutoCloseable {

    /**
     * Accept a validation document for delivery.
     * Must not block the calling thread.
     * Must not throw exceptions to the caller.
     */
    void submit(ValidationDocument document);

    /**
     * Flush any buffered documents. May block briefly.
     */
    void flush();
}
