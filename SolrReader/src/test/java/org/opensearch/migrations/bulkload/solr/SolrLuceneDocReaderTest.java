package org.opensearch.migrations.bulkload.solr;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;
import org.opensearch.migrations.bulkload.lucene.version_9.IndexReader9;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import shadow.lucene9.org.apache.lucene.analysis.core.KeywordAnalyzer;
import shadow.lucene9.org.apache.lucene.document.Document;
import shadow.lucene9.org.apache.lucene.document.StoredField;
import shadow.lucene9.org.apache.lucene.document.StringField;
import shadow.lucene9.org.apache.lucene.document.TextField;
import shadow.lucene9.org.apache.lucene.index.DirectoryReader;
import shadow.lucene9.org.apache.lucene.index.IndexWriter;
import shadow.lucene9.org.apache.lucene.index.IndexWriterConfig;
import shadow.lucene9.org.apache.lucene.store.FSDirectory;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SolrLuceneDocReader} backed by a real on-disk Lucene 9 index built
 * with {@link IndexWriter}. No mocking — every test case constructs an {@link FSDirectory}
 * over a {@link TempDir}, writes a single Lucene segment, then opens it via
 * {@link IndexReader9} so the wrapper classes (Document9, Field9, LeafReader9) exercise the
 * same code paths used in production reads.
 *
 * <p>Branch coverage targets (per the patch in PR #2982):
 * <ul>
 *   <li>{@code !isLive} short-circuit returns null (line 32-34).</li>
 *   <li>Solr "id" stored field present → docId comes from {@code extractSolrId} (line 39-42).</li>
 *   <li>Solr "id" missing → fallback synthetic id {@code "solr_doc_" + (segmentDocBase + luceneDocId)}
 *       (line 40-42).</li>
 *   <li>{@code stringValue() != null} branch in {@code extractSolrId} (line 73).</li>
 *   <li>{@code stringValue() == null, utf8ToStringValue() != null} branch (line 74).</li>
 *   <li>Multi-valued ID handling — first matching "id" field wins (line 71-72 partials).</li>
 *   <li>Empty / no-fields document → reconstructor returns null/empty → reader returns null
 *       (line 46-52).</li>
 *   <li>I/O failure surfaces as RfsException carrying the doc id (line 63-66).</li>
 *   <li>Operation type (INDEX / DELETE) propagated through to {@code LuceneDocumentChange}
 *       (line 54-56).</li>
 * </ul>
 */
class SolrLuceneDocReaderTest {

    @TempDir
    Path tempDir;

    private org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader openedDirReader;

    @AfterEach
    void tearDown() throws Exception {
        if (openedDirReader != null) {
            openedDirReader.close();
            openedDirReader = null;
        }
    }

    /**
     * Builds a single-segment Lucene 9 index containing the supplied docs and returns a
     * leaf reader wrapped in {@code LeafReader9} so callers can drive
     * {@link SolrLuceneDocReader#getDocument} directly. Each test gets its own
     * {@link TempDir}, so this method is called only once per test.
     */
    private LuceneLeafReader writeAndOpen(Document... docs) throws Exception {
        try (var writeDirectory = FSDirectory.open(tempDir)) {
            var config = new IndexWriterConfig(new KeywordAnalyzer());
            try (var writer = new IndexWriter(writeDirectory, config)) {
                for (Document doc : docs) {
                    writer.addDocument(doc);
                }
                writer.commit();
            }
        }
        // Find the segments file name and use the project's IndexReader9 wrapper so the test
        // exercises the production wrapper classes.
        String segmentsFileName;
        try (var listDirectory = FSDirectory.open(tempDir)) {
            var commits = DirectoryReader.listCommits(listDirectory);
            segmentsFileName = commits.get(commits.size() - 1).getSegmentsFileName();
        }
        var indexReader = new IndexReader9(tempDir, false, null);
        openedDirReader = indexReader.getReader(segmentsFileName);
        return openedDirReader.leaves().get(0).reader();
    }

    /** Branch: !isLive at line 32 → returns null without reading any fields. */
    @Test
    void deletedDocumentReturnsNull() throws Exception {
        Document doc = new Document();
        doc.add(new StringField("id", "ignored", StringField.Store.YES));
        var reader = writeAndOpen(doc);

        var change = SolrLuceneDocReader.getDocument(
            reader, 0, /* isLive= */ false, 0, DocumentChangeType.INDEX, null);

        assertNull(change, "deleted/non-live docs short-circuit");
    }

    /** Branch: id stored as plain String → {@code stringValue() != null} path at line 73. */
    @Test
    void extractsStoredStringIdFromFirstField() throws Exception {
        Document doc = new Document();
        doc.add(new StringField("id", "solr-id-42", StringField.Store.YES));
        // Need at least one reconstructable non-_-prefixed field for reconstructSourceFlat to
        // return a non-empty source. "id" itself is also preserved as a normal stored field.
        doc.add(new StoredField("title", "hello"));
        var reader = writeAndOpen(doc);

        var change = SolrLuceneDocReader.getDocument(
            reader, 0, true, 100, DocumentChangeType.INDEX, null);

        assertNotNull(change);
        assertEquals("solr-id-42", change.getId(), "id should come from stored 'id' field");
        // segmentDocBase + luceneDocId is the absolute Lucene doc number.
        assertEquals(100, change.getLuceneDocNumber());
        assertEquals(DocumentChangeType.INDEX, change.getOperation());
        assertNull(change.getRouting());
        assertNull(change.getType());
        // Source bytes should be valid UTF-8 JSON containing the "title" field.
        String sourceJson = new String(change.getSource(), StandardCharsets.UTF_8);
        assertTrue(sourceJson.contains("\"title\""), "source should contain title: " + sourceJson);
    }

    /** Branch: id is a binary stored field (BytesRef) — covers
     *  {@code stringValue()==null, utf8ToStringValue()!=null} at line 74.
     *  StoredField with a BytesRef value sets {@code binaryValue()} but leaves
     *  {@code stringValue()} null, so {@code Field9.stringValue()} returns null and the code
     *  falls through to {@code Field9.utf8ToStringValue()} which decodes the bytes as UTF-8. */
    @Test
    void extractsBinaryIdViaUtf8ToStringFallback() throws Exception {
        Document doc = new Document();
        // StoredField(name, BytesRef) stores binary bytes; the resulting IndexableField has
        // stringValue()==null and binaryValue() returning the BytesRef.
        doc.add(new StoredField("id", new BytesRef("binary-id-7")));
        // Provide another stored field so reconstruction yields non-empty source.
        doc.add(new StoredField("payload", "abc"));
        var reader = writeAndOpen(doc);

        var change = SolrLuceneDocReader.getDocument(
            reader, 0, true, 0, DocumentChangeType.INDEX, null);

        assertNotNull(change);
        assertEquals("binary-id-7", change.getId(),
            "id should fall back to utf8ToStringValue when stringValue is null");
    }

    /** Branch: no "id" stored field at all → fallback synthetic id at line 41. */
    @Test
    void synthesizesIdWhenSolrIdAbsent() throws Exception {
        Document doc = new Document();
        // No "id" field — SourceReconstructor will still produce a non-empty source from the
        // other stored fields, so the reader must invent an id from segmentDocBase+luceneDocId.
        doc.add(new StoredField("title", "needs-fallback-id"));
        doc.add(new StoredField("body", "more text"));
        var reader = writeAndOpen(doc);

        var change = SolrLuceneDocReader.getDocument(
            reader, 0, true, 50, DocumentChangeType.INDEX, null);

        assertNotNull(change);
        assertEquals("solr_doc_50", change.getId(),
            "synthetic id should be solr_doc_<segmentDocBase + luceneDocId>");
        assertEquals(50, change.getLuceneDocNumber());
    }

    /** Branch: multi-valued id — extractSolrId returns the FIRST "id" field encountered.
     *  Exercises the loop continuation in {@code extractSolrId} at line 71-72 (multiple
     *  iterations before the first match). */
    @Test
    void firstIdValueWinsForMultiValuedIdField() throws Exception {
        Document doc = new Document();
        // Two stored "id" entries simulate Solr's multi-valued field oddity. Solr schemas
        // usually mark uniqueKey as single-valued, but the underlying Lucene API allows
        // multiple — and extractSolrId iterates document.getFields() returning the first
        // match it finds.
        doc.add(new StringField("id", "first-id", StringField.Store.YES));
        doc.add(new StringField("id", "second-id", StringField.Store.YES));
        doc.add(new StoredField("note", "n"));
        var reader = writeAndOpen(doc);

        var change = SolrLuceneDocReader.getDocument(
            reader, 0, true, 0, DocumentChangeType.INDEX, null);

        assertNotNull(change);
        assertEquals("first-id", change.getId(), "first 'id' field should win");
    }

    /** Branch: SourceReconstructor returns null/empty → reader returns null at line 46-52.
     *  Doc has no stored fields except a {@code _}-prefixed one (filtered) and a
     *  Store.NO-only TextField (not a stored field). */
    @Test
    void returnsNullWhenNoReconstructableFields() throws Exception {
        Document doc = new Document();
        // TextField with Store.NO is indexed but not stored, so it never appears in
        // document.getFields(). The "_internal" stored field starts with underscore and is
        // filtered out by populateFromSegmentFlat. With no other stored fields, the
        // reconstructed map is empty → reconstructSourceFlat returns null → reader skips.
        doc.add(new TextField("not_stored_text", "tokens here", TextField.Store.NO));
        doc.add(new StoredField("_internal", "skipped"));
        var reader = writeAndOpen(doc);

        var change = SolrLuceneDocReader.getDocument(
            reader, 0, true, 0, DocumentChangeType.INDEX, null);

        assertNull(change,
            "doc with no reconstructable fields should be skipped (line 46-52)");
    }

    /** Branch: id is the only stored field — the reconstructor still emits a one-key source
     *  ({@code {"id":...}}) since "id" doesn't start with underscore. The id is propagated
     *  and the source is non-empty, so the reader returns a valid LuceneDocumentChange. */
    @Test
    void handlesIdOnlyDocument() throws Exception {
        Document doc = new Document();
        doc.add(new StringField("id", "lonely", StringField.Store.YES));
        var reader = writeAndOpen(doc);

        var change = SolrLuceneDocReader.getDocument(
            reader, 0, true, 0, DocumentChangeType.INDEX, null);

        assertNotNull(change, "id-only doc still produces a valid reconstructed source");
        assertEquals("lonely", change.getId());
        String sourceJson = new String(change.getSource(), StandardCharsets.UTF_8);
        assertTrue(sourceJson.contains("\"id\""),
            "source should include the id field as the sole reconstructed value: " + sourceJson);
    }

    /** Branch: dotted Solr field names are kept literal because reconstruction goes through
     *  {@code reconstructSourceFlat} (line 45) rather than the nested-aware path. Pins the
     *  flat-mode call-site that distinguishes Solr from ES. */
    @Test
    void reconstructsDottedFieldNamesLiterallyInFlatMode() throws Exception {
        Document doc = new Document();
        doc.add(new StringField("id", "flat-id", StringField.Store.YES));
        // Dotted Solr field names are literal (e.g. "address.city" is a flat field name in Solr,
        // not an object subfield). reconstructSourceFlat preserves this literal form.
        doc.add(new StoredField("address.city", "Seattle"));
        doc.add(new StoredField("address.zip", "98101"));
        var reader = writeAndOpen(doc);

        var change = SolrLuceneDocReader.getDocument(
            reader, 0, true, 0, DocumentChangeType.INDEX, null);

        assertNotNull(change);
        assertEquals("flat-id", change.getId());
        String sourceJson = new String(change.getSource(), StandardCharsets.UTF_8);
        // Flat mode preserves dotted names verbatim; the nested overload would rewrite them as
        // {"address":{"city":"Seattle"}}. Asserting the literal dotted key pins the
        // reconstructSourceFlat call site.
        assertTrue(sourceJson.contains("\"address.city\""),
            "flat reconstruction should preserve literal dotted key: " + sourceJson);
        assertTrue(sourceJson.contains("\"address.zip\""),
            "flat reconstruction should preserve all literal dotted keys: " + sourceJson);
    }

    /** Branch: I/O failure path at line 63-66. Wrap a reader whose {@code document()}
     *  throws {@link java.io.IOException} and verify the wrapping {@code RfsException} carries
     *  the failing luceneDocId in the message and preserves the cause. */
    @Test
    void ioExceptionWrapsAsRfsException() throws Exception {
        // Build a real index first so we have a real LuceneLeafReader to delegate non-document()
        // methods to; then layer an exception-throwing facade over it.
        Document doc = new Document();
        doc.add(new StringField("id", "x", StringField.Store.YES));
        doc.add(new StoredField("v", "v"));
        var realReader = writeAndOpen(doc);

        // Anonymous LuceneLeafReader: forwards everything except document(), which throws.
        var throwingReader = new LuceneLeafReader() {
            @Override
            public org.opensearch.migrations.bulkload.lucene.LuceneDocument document(int luceneDocId)
                    throws java.io.IOException {
                throw new java.io.IOException("simulated read failure");
            }
            @Override
            public org.opensearch.migrations.bulkload.lucene.BitSetConverter.FixedLengthBitSet getLiveDocs() {
                return realReader.getLiveDocs();
            }
            @Override
            public LuceneLeafReader newView() {
                return this;
            }
            @Override
            public int maxDoc() { return realReader.maxDoc(); }
            @Override
            public String getContextString() { return realReader.getContextString(); }
            @Override
            public String getSegmentName() { return realReader.getSegmentName(); }
            @Override
            public String getSegmentInfoString() { return realReader.getSegmentInfoString(); }
        };

        var ex = assertThrows(
            org.opensearch.migrations.bulkload.common.RfsException.class,
            () -> SolrLuceneDocReader.getDocument(throwingReader, 7, true, 1000,
                DocumentChangeType.INDEX, null));

        assertTrue(ex.getMessage().contains("7"),
            "RfsException should carry the failing luceneDocId in the message: " + ex.getMessage());
        assertNotNull(ex.getCause(), "underlying IOException should be preserved as cause");
        assertEquals("simulated read failure", ex.getCause().getMessage());
    }

    /** Branch: DELETE operation type is propagated through the change record at line 56. */
    @Test
    void operationTypeIsPropagated() throws Exception {
        Document doc = new Document();
        doc.add(new StringField("id", "del-1", StringField.Store.YES));
        doc.add(new StoredField("v", "v"));
        var reader = writeAndOpen(doc);

        var change = SolrLuceneDocReader.getDocument(
            reader, 0, true, 0, DocumentChangeType.DELETE, null);

        assertNotNull(change);
        assertEquals(DocumentChangeType.DELETE, change.getOperation());
    }
}
