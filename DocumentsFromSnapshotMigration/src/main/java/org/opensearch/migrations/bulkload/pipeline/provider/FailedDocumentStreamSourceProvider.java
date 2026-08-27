package org.opensearch.migrations.bulkload.pipeline.provider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.RepoUri;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.pipeline.spi.DocumentSourceProvider;
import org.opensearch.migrations.bulkload.pipeline.spi.SourceRuntime;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;
import org.opensearch.migrations.reindexer.faileddocumentstream.source.FailedDocumentStreamObjectStore;
import org.opensearch.migrations.reindexer.faileddocumentstream.source.FailedDocumentStreamRecordTransformer;
import org.opensearch.migrations.reindexer.faileddocumentstream.source.FailedDocumentStreamSource;
import org.opensearch.migrations.reindexer.faileddocumentstream.source.S3FailedDocumentStreamObjectStore;
import org.opensearch.migrations.reindexer.faileddocumentstream.source.SessionManifest;
import org.opensearch.migrations.reindexer.faileddocumentstream.source.SessionSealer;
import org.opensearch.migrations.transform.IJsonTransformer;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Serves a sealed failure-stream session, so a redrive inherits coordination, leases, retries,
 * progress and metrics, and a re-failure lands in a stream that can itself be redriven.
 *
 * <p>Only a sealed session can be read: an open one can still gain records after enumeration, and
 * silently skipping those is not good enough for a feature whose promise is completeness.
 */
@Slf4j
public class FailedDocumentStreamSourceProvider
    implements DocumentSourceProvider<FailedDocumentStreamSourceSpec> {

    public static final String KIND = "failed-document-stream";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public FailedDocumentStreamSourceSpec parseSpec(JsonNode config) {
        return FailedDocumentStreamSourceSpec.fromJson(config);
    }

    @Override
    public void validate(FailedDocumentStreamSourceSpec spec, SourceRuntime runtime) {
        if (!(RepoUri.parse(spec.streamUri()) instanceof RepoUri.S3RepoUri)) {
            throw new IllegalArgumentException(
                "A failure stream must be given as an s3:// URI, got: " + spec.streamUri());
        }
        parseFailureClasses(spec);
        try (var store = openStore(spec)) {
            var manifest = SessionSealer.readManifest(store, prefixOf(spec), spec.sessionId());
            if (manifest == null) {
                throw new IllegalArgumentException("Failure-stream session '" + spec.sessionId()
                    + "' under " + spec.streamUri() + " has not been sealed, so its contents can still"
                    + " change while it is being read. Seal it first:"
                    + " console failed-document-stream seal --session " + spec.sessionId());
            }
            // Integrity check only; the seal defines what is read.
            SessionSealer.verifyAgainstListing(store, prefixOf(spec), manifest);
            if (manifest.collections().isEmpty()) {
                throw new IllegalArgumentException("Failure-stream session '" + spec.sessionId()
                    + "' was sealed with no records in it; there is nothing to redrive.");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not close the failure-stream object store", e);
        }
    }

    /** A manifest GET and a verifying LIST, which a worker with no work left should not pay. */
    @Override
    public boolean deferUntilWorkAvailable() {
        return true;
    }

    /** Nothing is staged on disk. */
    @Override
    public boolean requiresScratchDirectory(FailedDocumentStreamSourceSpec spec) {
        return false;
    }

    @Override
    public boolean requiresWorkingDirectory(FailedDocumentStreamSourceSpec spec) {
        return false;
    }

    /** Records are not operations until this has run, so it is not the operator's to remember. */
    @Override
    public Optional<Supplier<IJsonTransformer>> requiredPreTransform(FailedDocumentStreamSourceSpec spec) {
        return Optional.of(FailedDocumentStreamRecordTransformer::new);
    }

    @Override
    public DocumentSource create(FailedDocumentStreamSourceSpec spec, SourceRuntime runtime) throws IOException {
        var store = openStore(spec);
        try {
            var manifest = SessionSealer.readManifest(store, prefixOf(spec), spec.sessionId());
            if (manifest == null) {
                throw new IllegalStateException("Failure-stream session '" + spec.sessionId()
                    + "' is no longer sealed; it was when the source was validated.");
            }
            logWhatWillBeWritten(spec, manifest);
            return new FailedDocumentStreamSource(
                store, manifest, spec.indexAllowlist(), parseFailureClasses(spec), true);
        } catch (RuntimeException | IOException e) {
            closeQuietly(store);
            throw e;
        }
    }

    /**
     * Name the indices before writing them. RFS cannot tell whether the original migration combined
     * several source indices into one target, so only the operator can judge the risk.
     */
    private void logWhatWillBeWritten(FailedDocumentStreamSourceSpec spec, SessionManifest manifest) {
        var indices = manifest.collectionNames().stream()
            .filter(name -> spec.indexAllowlist().isEmpty() || spec.indexAllowlist().contains(name))
            .collect(Collectors.joining(", "));
        log.atWarn()
            .setMessage("Redriving failure-stream session '{}' into: {}. Existing documents at these"
                + " document ids will be REPLACED.")
            .addArgument(spec.sessionId())
            .addArgument(indices)
            .log();
    }

    private static Set<FailureClass> parseFailureClasses(FailedDocumentStreamSourceSpec spec) {
        if (spec.failureClasses().isEmpty()) {
            return EnumSet.noneOf(FailureClass.class);
        }
        return spec.failureClasses().stream()
            .map(raw -> {
                try {
                    return FailureClass.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown failure class '" + raw + "'; expected one of "
                        + EnumSet.allOf(FailureClass.class), e);
                }
            })
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(FailureClass.class)));
    }

    /** Overridable so a test can hold the session in memory. */
    protected FailedDocumentStreamObjectStore openStore(FailedDocumentStreamSourceSpec spec) {
        var s3Uri = ((RepoUri.S3RepoUri) RepoUri.parse(spec.streamUri())).s3Uri();
        return S3FailedDocumentStreamObjectStore.create(s3Uri.bucketName, spec.s3Region(), spec.endpoint());
    }

    /** Empty when the stream sits at the bucket root. */
    private static String prefixOf(FailedDocumentStreamSourceSpec spec) {
        return ((RepoUri.S3RepoUri) RepoUri.parse(spec.streamUri())).s3Uri().key;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            log.atWarn().setCause(e).setMessage("Error closing the failure-stream object store").log();
        }
    }
}
