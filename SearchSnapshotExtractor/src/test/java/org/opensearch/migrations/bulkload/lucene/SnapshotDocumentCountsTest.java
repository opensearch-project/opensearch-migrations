package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.common.SnapshotDocumentCounts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotDocumentCountsTest {
    @TempDir
    Path directory;

    @Test
    void metadataFailureClosesReaderWithoutPublishingPartialCounts() throws Exception {
        var index = mock(LuceneIndexReader.class);
        var reader = mock(LuceneDirectoryReader.class);
        var context = mock(LuceneLeafReaderContext.class);
        var leaf = mock(LuceneLeafReader.class);
        var failure = new IOException("Unreadable nested metadata");
        when(index.getReader("segments_1")).thenReturn(reader);
        when(reader.leaves()).thenAnswer(ignored -> List.of(context));
        when(context.reader()).thenReturn(leaf);
        when(leaf.countLiveDocumentsWithTermPrefix("_nested_path", "")).thenThrow(failure);

        var thrown = assertThrows(UncheckedIOException.class,
            () -> LuceneReader.streamDocumentChanges(index, "segments_1", 0, null, false,
                counts -> { throw new AssertionError("Must not publish partial counts"); }).blockLast());

        assertSame(failure, thrown.getCause());
        verify(reader).close();
    }

    @Test
    void countsLiveNestedAndHardDeletedDocumentsInLucene5() throws Exception {
        try (var storage = shadow.lucene5.org.apache.lucene.store.FSDirectory.open(directory);
             var writer = new shadow.lucene5.org.apache.lucene.index.IndexWriter(storage,
                 new shadow.lucene5.org.apache.lucene.index.IndexWriterConfig(null)
                     .setMergePolicy(shadow.lucene5.org.apache.lucene.index.NoMergePolicy.INSTANCE))) {
            writer.addDocuments(List.of(
                List.of(field5("_type", "_doc")),
                List.of(field5("_type", "__children")),
                List.of(field5("_nested_path", "children.grandchildren")),
                List.of(field5("_type", "__deleted")),
                List.of(field5("_type", "article"))));
            writer.commit();
            try (var reader = new org.opensearch.migrations.bulkload.lucene.version_5.DirectoryReader5(
                    shadow.lucene5.org.apache.lucene.index.DirectoryReader.open(writer), directory)) {
                assertEquals(new SnapshotDocumentCounts(5, 3, 0), LuceneReader.countSnapshotDocuments(reader));
            }
            writer.deleteDocuments(new shadow.lucene5.org.apache.lucene.index.Term("_type", "__deleted"));
            try (var reader = new org.opensearch.migrations.bulkload.lucene.version_5.DirectoryReader5(
                    shadow.lucene5.org.apache.lucene.index.DirectoryReader.open(writer), directory)) {
                assertEquals(new SnapshotDocumentCounts(4, 2, 1), LuceneReader.countSnapshotDocuments(reader));
            }
        }
    }

    private static shadow.lucene5.org.apache.lucene.document.StringField field5(String name, String value) {
        return new shadow.lucene5.org.apache.lucene.document.StringField(name, value, shadow.lucene5.org.apache.lucene.document.Field.Store.NO);
    }

    @Test
    void countsLiveNestedAndHardDeletedDocumentsInLucene6() throws Exception {
        try (var storage = shadow.lucene6.org.apache.lucene.store.FSDirectory.open(directory);
             var writer = new shadow.lucene6.org.apache.lucene.index.IndexWriter(storage,
                 new shadow.lucene6.org.apache.lucene.index.IndexWriterConfig(null)
                     .setMergePolicy(shadow.lucene6.org.apache.lucene.index.NoMergePolicy.INSTANCE))) {
            writer.addDocuments(List.of(
                List.of(field6("_type", "_doc")),
                List.of(field6("_type", "__children")),
                List.of(field6("_nested_path", "children.grandchildren")),
                List.of(field6("_type", "__deleted")),
                List.of(field6("_type", "article"))));
            writer.commit();
            try (var reader = new org.opensearch.migrations.bulkload.lucene.version_6.DirectoryReader6(
                    shadow.lucene6.org.apache.lucene.index.DirectoryReader.open(writer), directory)) {
                assertEquals(new SnapshotDocumentCounts(5, 3, 0), LuceneReader.countSnapshotDocuments(reader));
            }
            writer.deleteDocuments(new shadow.lucene6.org.apache.lucene.index.Term("_type", "__deleted"));
            try (var reader = new org.opensearch.migrations.bulkload.lucene.version_6.DirectoryReader6(
                    shadow.lucene6.org.apache.lucene.index.DirectoryReader.open(writer), directory)) {
                assertEquals(new SnapshotDocumentCounts(4, 2, 1), LuceneReader.countSnapshotDocuments(reader));
            }
        }
    }

    private static shadow.lucene6.org.apache.lucene.document.StringField field6(String name, String value) {
        return new shadow.lucene6.org.apache.lucene.document.StringField(name, value, shadow.lucene6.org.apache.lucene.document.Field.Store.NO);
    }

    @Test
    void countsLiveNestedAndHardDeletedDocumentsInLucene7() throws Exception {
        try (var storage = shadow.lucene7.org.apache.lucene.store.FSDirectory.open(directory);
             var writer = new shadow.lucene7.org.apache.lucene.index.IndexWriter(storage,
                 new shadow.lucene7.org.apache.lucene.index.IndexWriterConfig(null)
                     .setMergePolicy(shadow.lucene7.org.apache.lucene.index.NoMergePolicy.INSTANCE))) {
            writer.addDocuments(List.of(
                List.of(field7("_type", "_doc")),
                List.of(field7("_type", "__children")),
                List.of(field7("_nested_path", "children.grandchildren")),
                List.of(field7("_type", "__deleted")),
                List.of(field7("_type", "article"))));
            writer.commit();
            try (var reader = new org.opensearch.migrations.bulkload.lucene.version_7.DirectoryReader7(
                    shadow.lucene7.org.apache.lucene.index.DirectoryReader.open(writer), directory)) {
                assertEquals(new SnapshotDocumentCounts(5, 3, 0), LuceneReader.countSnapshotDocuments(reader));
            }
            writer.deleteDocuments(new shadow.lucene7.org.apache.lucene.index.Term("_type", "__deleted"));
            try (var reader = new org.opensearch.migrations.bulkload.lucene.version_7.DirectoryReader7(
                    shadow.lucene7.org.apache.lucene.index.DirectoryReader.open(writer), directory)) {
                assertEquals(new SnapshotDocumentCounts(4, 2, 1), LuceneReader.countSnapshotDocuments(reader));
            }
        }
    }

    private static shadow.lucene7.org.apache.lucene.document.StringField field7(String name, String value) {
        return new shadow.lucene7.org.apache.lucene.document.StringField(name, value, shadow.lucene7.org.apache.lucene.document.Field.Store.NO);
    }

    @Test
    void countsLiveNestedAndHardDeletedDocumentsInLucene9() throws Exception {
        try (var storage = shadow.lucene9.org.apache.lucene.store.FSDirectory.open(directory);
             var writer = new shadow.lucene9.org.apache.lucene.index.IndexWriter(storage,
                 new shadow.lucene9.org.apache.lucene.index.IndexWriterConfig(null)
                     .setMergePolicy(shadow.lucene9.org.apache.lucene.index.NoMergePolicy.INSTANCE))) {
            writer.addDocuments(List.of(
                List.of(field9("_type", "_doc")),
                List.of(field9("_type", "__children")),
                List.of(field9("_nested_path", "children.grandchildren")),
                List.of(field9("_type", "__deleted")),
                List.of(field9("_type", "article"))));
            writer.commit();
            try (var reader = new org.opensearch.migrations.bulkload.lucene.version_9.DirectoryReader9(
                    shadow.lucene9.org.apache.lucene.index.DirectoryReader.open(writer), directory)) {
                assertEquals(new SnapshotDocumentCounts(5, 3, 0), LuceneReader.countSnapshotDocuments(reader));
            }
            writer.deleteDocuments(new shadow.lucene9.org.apache.lucene.index.Term("_type", "__deleted"));
            try (var reader = new org.opensearch.migrations.bulkload.lucene.version_9.DirectoryReader9(
                    shadow.lucene9.org.apache.lucene.index.DirectoryReader.open(writer), directory)) {
                assertEquals(new SnapshotDocumentCounts(4, 2, 1), LuceneReader.countSnapshotDocuments(reader));
            }
        }
    }

    private static shadow.lucene9.org.apache.lucene.document.StringField field9(String name, String value) {
        return new shadow.lucene9.org.apache.lucene.document.StringField(name, value, shadow.lucene9.org.apache.lucene.document.Field.Store.NO);
    }

    @Test
    void countsLiveNestedAndHardDeletedDocumentsInLucene10() throws Exception {
        try (var storage = shadow.lucene10.org.apache.lucene.store.FSDirectory.open(directory);
             var writer = new shadow.lucene10.org.apache.lucene.index.IndexWriter(storage,
                 new shadow.lucene10.org.apache.lucene.index.IndexWriterConfig(null)
                     .setMergePolicy(shadow.lucene10.org.apache.lucene.index.NoMergePolicy.INSTANCE))) {
            writer.addDocuments(List.of(
                List.of(field10("_type", "_doc")),
                List.of(field10("_type", "__children")),
                List.of(field10("_nested_path", "children.grandchildren")),
                List.of(field10("_type", "__deleted")),
                List.of(field10("_type", "article"))));
            writer.commit();
            try (var reader = new org.opensearch.migrations.bulkload.lucene.version_10.DirectoryReader10(
                    shadow.lucene10.org.apache.lucene.index.DirectoryReader.open(writer), directory)) {
                assertEquals(new SnapshotDocumentCounts(5, 3, 0), LuceneReader.countSnapshotDocuments(reader));
            }
            writer.deleteDocuments(new shadow.lucene10.org.apache.lucene.index.Term("_type", "__deleted"));
            try (var reader = new org.opensearch.migrations.bulkload.lucene.version_10.DirectoryReader10(
                    shadow.lucene10.org.apache.lucene.index.DirectoryReader.open(writer), directory)) {
                assertEquals(new SnapshotDocumentCounts(4, 2, 1), LuceneReader.countSnapshotDocuments(reader));
            }
        }
    }

    private static shadow.lucene10.org.apache.lucene.document.StringField field10(String name, String value) {
        return new shadow.lucene10.org.apache.lucene.document.StringField(name, value, shadow.lucene10.org.apache.lucene.document.Field.Store.NO);
    }

    @Test
    void includesFullySoftDeletedSegmentsButNotTheirNestedDocuments() throws Exception {
        var config = new shadow.lucene9.org.apache.lucene.index.IndexWriterConfig(null)
            .setSoftDeletesField("_soft_deletes")
            .setMergePolicy(new shadow.lucene9.org.apache.lucene.index.SoftDeletesRetentionMergePolicy(
                "_soft_deletes", shadow.lucene9.org.apache.lucene.search.MatchAllDocsQuery::new,
                shadow.lucene9.org.apache.lucene.index.NoMergePolicy.INSTANCE));
        try (var storage = shadow.lucene9.org.apache.lucene.store.FSDirectory.open(directory);
             var writer = new shadow.lucene9.org.apache.lucene.index.IndexWriter(storage, config)) {
            writer.addDocument(List.of(field9("_nested_path", "children")));
            writer.commit();
            writer.softUpdateDocument(new shadow.lucene9.org.apache.lucene.index.Term("_nested_path", "children"),
                List.of(field9("_type", "_doc")),
                new shadow.lucene9.org.apache.lucene.document.NumericDocValuesField("_soft_deletes", 1));
            writer.commit();
            try (var reader = new org.opensearch.migrations.bulkload.lucene.version_9.DirectoryReader9(
                    new shadow.lucene9.org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper(
                        shadow.lucene9.org.apache.lucene.index.DirectoryReader.open(writer), "_soft_deletes"), directory)) {
                assertEquals(1, reader.maxDoc());
                assertEquals(new SnapshotDocumentCounts(1, 0, 1), LuceneReader.countSnapshotDocuments(reader));
            }
        }
    }
}
