package org.opensearch.migrations.bulkload.lucene.version_10;

import java.io.IOException;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Lucene 10 index reader (ES 9.x / OS 3.x snapshots).
 *
 * <p><b>SPIKE STUB:</b> the concrete Lucene 10 implementation is not yet ported.
 * The Lucene 10 classpath is isolated in a separate shadow jar compiled under JDK 21
 * (see {@code SearchSnapshotExtractor/build.gradle} {@code compileLucene10Java} task).
 * The main {@code src/main/java} sourceSet is compiled with JDK 17, which cannot
 * read the JDK 21 (class-file v65) bytecode produced by the Lucene 10 shadow jar.
 *
 * <p>Porting this class requires one of:
 * <ol>
 *   <li>Bumping the whole project toolchain to JDK 21 (cross-cutting change), or
 *   <li>Moving the concrete version_10 readers into their own JDK-21-compiled
 *       source set (mirror of {@code src/lucene10}) and loading them
 *       reflectively from the main module, or
 *   <li>Compiling the readers to a JDK 21 multi-release jar.
 * </ol>
 *
 * <p>Once one of the above is chosen, mechanically port the body of
 * {@code IndexReader9} / {@code DirectoryReader9} / {@code LeafReader9} to the
 * Lucene 10 API (the breaking changes are listed in the Lucene 10 migration
 * guide — chief offenders: {@code IndexSearcher} executor API,
 * {@code CheckIndex} return type, {@code Codec.forName} SPI,
 * KNN vectors format signatures).
 */
@AllArgsConstructor
@Slf4j
public class IndexReader10 implements LuceneIndexReader {

    protected final Path indexDirectoryPath;
    protected final boolean softDeletesPossible;
    protected final String softDeletesField;

    @Override
    public LuceneDirectoryReader getReader(String segmentsFileName) throws IOException {
        throw new UnsupportedOperationException(
            "Lucene 10 reader is not yet implemented. "
                + "This spike wires the capability end-to-end through the registry and "
                + "version matchers but defers the concrete Lucene 10 DirectoryReader port. "
                + "See IndexReader10 javadoc for the path forward."
        );
    }
}
