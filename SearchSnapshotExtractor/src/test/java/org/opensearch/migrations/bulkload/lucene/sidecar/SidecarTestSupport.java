package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import shadow.lucene10.org.apache.lucene.util.IOUtils;

/** Shared helpers for sidecar unit tests: term-bytes wrapping, spill-dir cleanup. */
final class SidecarTestSupport {

    private SidecarTestSupport() {}

    /** Wraps {@code s} as a {@link BytesRefLike} over its UTF-8 bytes. */
    static BytesRefLike bytesRef(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return new BytesRefLike(b, 0, b.length);
    }

    /** Recursively deletes {@code root} and everything under it. No-op if it doesn't exist. */
    static void rm(Path root) throws IOException {
        if (root != null) IOUtils.rm(root);
    }
}
