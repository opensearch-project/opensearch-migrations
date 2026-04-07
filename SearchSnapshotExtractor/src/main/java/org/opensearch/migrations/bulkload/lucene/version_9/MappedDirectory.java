package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import shadow.lucene9.org.apache.lucene.store.Directory;
import shadow.lucene9.org.apache.lucene.store.FilterDirectory;
import shadow.lucene9.org.apache.lucene.store.IOContext;
import shadow.lucene9.org.apache.lucene.store.IndexInput;

/**
 * A Lucene {@link Directory} wrapper that maps logical filenames to physical filenames.
 * Used for Solr backups where files are stored with UUID names and a separate metadata
 * file provides the mapping from real Lucene filenames to UUIDs.
 *
 * <p>Only read operations are supported (this is for reading backups, not writing).
 */
public class MappedDirectory extends FilterDirectory {

    // realName → physicalName (e.g. "segments_1" → "dcc59643-7bd0-44eb-a128-33a1cf093233")
    private final Map<String, String> nameMapping;

    /**
     * @param delegate    the underlying directory containing the physical files
     * @param nameMapping map from logical Lucene filename to physical filename on disk
     */
    public MappedDirectory(Directory delegate, Map<String, String> nameMapping) {
        super(delegate);
        this.nameMapping = nameMapping;
    }

    private String resolve(String name) {
        return nameMapping.getOrDefault(name, name);
    }

    @Override
    public String[] listAll() throws IOException {
        // Return the logical names (what Lucene expects), not the physical UUIDs
        Set<String> physicalFiles = Set.of(in.listAll());
        return nameMapping.entrySet().stream()
            .filter(e -> physicalFiles.contains(e.getValue()))
            .map(Map.Entry::getKey)
            .toArray(String[]::new);
    }

    @Override
    public long fileLength(String name) throws IOException {
        return in.fileLength(resolve(name));
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        return in.openInput(resolve(name), context);
    }

    @Override
    public void deleteFile(String name) throws IOException {
        throw new UnsupportedOperationException("MappedDirectory is read-only");
    }
}
