package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.opensearch.migrations.bulkload.lucene.sidecar.SidecarBuilder;
import org.opensearch.migrations.bulkload.lucene.sidecar.SidecarReader;
import org.opensearch.migrations.bulkload.lucene.sidecar.TermEntry;

import lombok.extern.slf4j.Slf4j;
import shadow.lucene10.org.apache.lucene.util.IOUtils;

/**
 * Per-segment cache of per-field term indexes.
 *
 * <p>Two kinds of indexes are cached here, each lazily built on first access:
 *
 * <ol>
 *   <li><b>byField</b>: fieldName -&gt; {@link SidecarReader}.
 *       Used by {@link SourceReconstructor} to reconstruct analyzed-text fields
 *       (STRING type) when neither stored fields nor doc_values are available.
 *       Backed by {@link SidecarBuilder}: the per-field posting stream is walked
 *       exactly once, spilled to disk as fixed-width 12-byte scratch tuples,
 *       externally merge-sorted using primitive int[] arrays (no boxing) keyed
 *       on (docId, position, termId), and encoded into a compact varint sidecar
 *       with a flat long[maxDoc] doc index. The reader mmaps the sidecar and
 *       serves {@code get(docId)} with zero heap allocation beyond the returned
 *       ArrayList. Heap footprint is bounded regardless of segment size.</li>
 *
 *   <li><b>numericByField</b>: fieldName -&gt; docId -&gt; decoded Long.
 *       Used to reconstruct trie-encoded numeric fields
 *       (NUMERIC/IP/DATE/DATE_NANOS/SCALED_FLOAT/UNSIGNED_LONG) in Lucene 4/5
 *       segments. Stays in heap: 16 bytes per (docId, Long) entry times maxDoc
 *       is bounded and small (a 200k-doc segment is ~3 MB of numeric index).</li>
 * </ol>
 *
 * <p>Lifetime is scoped to a single call to
 * {@link LuceneReader#readDocsFromSegment}: a fresh instance is created there
 * with a per-segment {@code spillRoot}, and flows down to {@link SourceReconstructor}.
 * Once the segment's Flux terminates (success, error, or cancel), the Flux's
 * {@code doFinally} hook calls {@link #close()}, which closes all open sidecar
 * readers and deletes the spill root.
 *
 * <p>Thread-safety: Lucene TermsEnum / PostingsEnum instances are not safe for
 * concurrent access. The build phase for each field is serialized via a per-field
 * {@link java.util.concurrent.locks.ReentrantReadWriteLock} so only one thread
 * builds a given field's sidecar while others wait. Once a field's
 * {@link SidecarReader} has been placed into the {@link java.util.concurrent.ConcurrentHashMap},
 * subsequent reads are completely lock-free — just a CHM {@code get()} plus the
 * mmap-backed {@link SidecarReader#get(int)}.
 *
 * <p>The {@link #preloadFields} method builds all requested sidecars in parallel
 * before any documents stream, eliminating the repeated stalls that occur when
 * lazy builds are triggered one field at a time under concurrent document reads.
 */
@Slf4j
public class SegmentTermIndex implements AutoCloseable {

    private final Path spillRoot;
    private final long sortBufferBytes;
    private final ConcurrentHashMap<String, SidecarReader> byField = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, Long>> numericByField = new HashMap<>();
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> fieldLocks = new ConcurrentHashMap<>();
    private final AtomicInteger fieldSeq = new AtomicInteger();
    private volatile boolean closed;

    /**
     * Creates an index scoped to {@code spillRoot} for on-disk term spill files.
     * The directory is created lazily on first field build; callers can pass a
     * path that does not yet exist.
     *
     * @param spillRoot per-segment directory owned by this index. Deleted on {@link #close()}.
     * @param sortBufferBytes in-memory sort buffer budget for the external merge sort
     *                        inside {@link SidecarBuilder}. Values larger than
     *                        {@link Integer#MAX_VALUE} are clamped to fit the Builder's
     *                        int-sized scratch buffer.
     */
    public SegmentTermIndex(Path spillRoot, long sortBufferBytes) {
        this.spillRoot = spillRoot;
        this.sortBufferBytes = Math.min(sortBufferBytes, Integer.MAX_VALUE);
    }

    /**
     * Returns the position-ordered list of indexed terms for {@code docId} in
     * {@code fieldName}, building the per-field index on first access.
     *
     * <p>The first call for a field walks the full terms dictionary once, spills
     * scratch tuples to {@code spillRoot/fieldName/}, external-sorts them with
     * primitive arrays, and writes the final varint-compact sidecar files.
     * Subsequent calls are {@code O(1)} mmap lookups into the long[] doc index
     * plus a short varint decode.
     */
    public List<String> getTermsForDocument(LuceneLeafReader reader, int docId, String fieldName)
            throws IOException {
        List<TermEntry> entries = getTermEntriesForDocument(reader, docId, fieldName);
        List<String> strings = new ArrayList<>(entries.size());
        for (TermEntry e : entries) strings.add(e.term());
        return strings;
    }

    /**
     * Returns the {@link TermEntry} list for {@code docId} in {@code fieldName},
     * including character start/end offsets when the field was indexed with
     * {@code index_options: offsets}. Building the per-field sidecar on first access.
     *
     * <p>Fast path: if the sidecar is already built (present in {@code byField}), the
     * lookup is completely lock-free — {@link ConcurrentHashMap#get} plus the mmap-backed
     * {@link SidecarReader#get(int)}. Only the first access for a field takes a write lock
     * to serialize the build phase.
     */
    public List<TermEntry> getTermEntriesForDocument(
            LuceneLeafReader reader, int docId, String fieldName) throws IOException {
        if (closed) {
            throw new IOException("SegmentTermIndex has been closed");
        }
        // Fast path: sidecar already built — completely lock-free
        SidecarReader existing = byField.get(fieldName);
        if (existing != null) {
            return existing.get(docId);
        }
        // Slow path: need to build the sidecar under write lock
        return getOrBuildSidecar(reader, fieldName).get(docId);
    }

    /**
     * Ensures the sidecar for {@code fieldName} is built, using a per-field
     * {@link ReentrantReadWriteLock} so only one thread builds while others wait,
     * and concurrent reads after the build are lock-free.
     */
    private SidecarReader getOrBuildSidecar(LuceneLeafReader reader, String fieldName) throws IOException {
        ReentrantReadWriteLock rwLock = fieldLocks.computeIfAbsent(fieldName, k -> new ReentrantReadWriteLock());
        // Double-check under read lock first
        rwLock.readLock().lock();
        try {
            SidecarReader cached = byField.get(fieldName);
            if (cached != null) {
                return cached;
            }
        } finally {
            rwLock.readLock().unlock();
        }
        // Upgrade to write lock for the build
        rwLock.writeLock().lock();
        try {
            // Re-check after acquiring write lock — another thread may have built it
            SidecarReader cached = byField.get(fieldName);
            if (cached != null) {
                return cached;
            }
            if (closed) {
                throw new IOException("SegmentTermIndex has been closed");
            }
            SidecarReader built = buildFieldIndex(reader, fieldName);
            byField.put(fieldName, built);
            return built;
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Returns the decoded numeric value (as Long) for {@code docId} in {@code fieldName},
     * building the per-field numeric index on first access. Returns null if the field has no
     * trie-encoded numeric terms or the doc was not indexed with a value for the field.
     *
     * <p>The returned Long is the raw decoded value from the shift==0 term. Interpretation
     * (int vs long vs float vs double vs IP string) is applied downstream via
     * {@link FieldMappingInfo} in {@link SourceReconstructor}.
     */
    public synchronized Long getNumericForDocument(LuceneLeafReader reader, int docId, String fieldName)
            throws IOException {
        if (closed) {
            throw new IOException("SegmentTermIndex has been closed");
        }
        Map<Integer, Long> forField = numericByField.get(fieldName);
        if (forField == null) {
            forField = reader.buildNumericTermIndex(fieldName);
            numericByField.put(fieldName, forField);
        }
        return forField.get(docId);
    }

    /**
     * Pre-builds sidecar indexes for the given fields in parallel, so that subsequent
     * per-document reads are completely lock-free. This should be called once per segment
     * BEFORE any documents start streaming.
     *
     * <p>Fields that are already built are skipped. Building is performed in a dedicated
     * {@link ForkJoinPool} with the specified parallelism so the caller's thread is not
     * blocked beyond the aggregate wall-clock time.
     *
     * @param reader      the segment reader to stream postings from
     * @param fieldNames  field names to pre-build (typically the analyzed-text fields)
     * @param parallelism number of concurrent build threads
     */
    public void preloadFields(LuceneLeafReader reader, Collection<String> fieldNames, int parallelism) {
        if (fieldNames == null || fieldNames.isEmpty() || closed) {
            return;
        }
        // Filter out fields already built
        List<String> toBuild = new ArrayList<>();
        for (String f : fieldNames) {
            if (!byField.containsKey(f)) {
                toBuild.add(f);
            }
        }
        if (toBuild.isEmpty()) {
            return;
        }
        log.info("Pre-building {} field sidecars with parallelism={}", toBuild.size(), parallelism);
        long startNanos = System.nanoTime();
        AtomicInteger failures = new AtomicInteger();

        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            pool.submit(() -> toBuild.parallelStream().forEach(fieldName -> {
                try {
                    getOrBuildSidecar(reader, fieldName);
                } catch (IOException e) {
                    failures.incrementAndGet();
                    log.warn("Failed to pre-build sidecar for field '{}': {}", fieldName, e.toString());
                }
            })).join();
        } finally {
            pool.shutdown();
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (failures.get() > 0) {
            log.warn("Pre-built {} field sidecars in {}ms ({} failures)",
                toBuild.size() - failures.get(), elapsedMs, failures.get());
        } else {
            log.info("Pre-built {} field sidecars in {}ms", toBuild.size(), elapsedMs);
        }
    }

    /**
     * Builds the sidecar for {@code fieldName} by streaming the terms dict once into a
     * {@link SidecarBuilder}.
     *
     * <p>If the field has no terms (reader emits no tuples), returns a sidecar with an
     * empty doc-index — lookups return {@link Collections#emptyList()} uniformly.
     */
    private SidecarReader buildFieldIndex(LuceneLeafReader reader, String fieldName) throws IOException {
        Path fieldSpillDir = spillRoot.resolve(sanitizeFieldName(fieldName));
        int maxDoc = Math.max(1, reader.maxDoc());
        // try-with-resources guarantees cleanup of partial spill files if
        // streamFieldPostings or buildAndOpenReader throws. On the happy path
        // buildAndOpenReader flips the builder's closed flag, so the subsequent
        // close() is a no-op and does not touch the files SidecarReader now owns.
        try (SidecarBuilder builder = new SidecarBuilder(fieldSpillDir, sortBufferBytes, maxDoc)) {
            reader.streamFieldPostings(fieldName, builder);
            return builder.buildAndOpenReader();
        }
    }

    /**
     * Strips path-unsafe characters from the field name so the spill subdirectory name
     * is filesystem-portable. Collisions are harmless here because {@code spillRoot} itself
     * is per-segment and field names within a segment are already unique — but two fields
     * with names differing only in path-unsafe characters would collide. The sanitized name
     * is therefore suffixed with an index to avoid that edge case.
     */
    private String sanitizeFieldName(String fieldName) {
        StringBuilder sb = new StringBuilder(fieldName.length() + 8);
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (c == '.' || c == '_' || Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        // append a unique sequence so two sanitized-equal names don't collide
        sb.append('-').append(fieldSeq.getAndIncrement());
        return sb.toString();
    }

    /**
     * Closes every sidecar reader and recursively deletes the spill root.
     * Safe to call multiple times. Exceptions from individual closes are logged
     * and swallowed so one failing field doesn't leak the rest.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Map.Entry<String, SidecarReader> e : byField.entrySet()) {
            try {
                e.getValue().close();
            } catch (Exception ex) {
                log.warn("Failed to close sidecar for field {}: {}", e.getKey(), ex.toString());
            }
        }
        byField.clear();
        numericByField.clear();
        fieldLocks.clear();
        try {
            IOUtils.rm(spillRoot);
        } catch (IOException e) {
            log.warn("Failed to delete spill root {}: {}", spillRoot, e.toString());
        }
    }
}
