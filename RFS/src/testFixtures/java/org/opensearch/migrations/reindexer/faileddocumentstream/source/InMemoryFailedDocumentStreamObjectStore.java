package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.zip.GZIPOutputStream;

/**
 * An in-memory {@link FailedDocumentStreamObjectStore}, so the source and sealer need no S3.
 *
 * <p>Keys are sorted, matching S3 listing order, which the manifest depends on.
 */
public class InMemoryFailedDocumentStreamObjectStore implements FailedDocumentStreamObjectStore {

    private final ConcurrentSkipListMap<String, byte[]> objects = new ConcurrentSkipListMap<>();

    @Override
    public List<String> listKeys(String prefix) {
        return objects.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
    }

    @Override
    public InputStream open(String key) throws IOException {
        var body = objects.get(key);
        if (body == null) {
            throw new IOException("No such object: " + key);
        }
        return new ByteArrayInputStream(body);
    }

    @Override
    public Optional<byte[]> read(String key) {
        return Optional.ofNullable(objects.get(key));
    }

    @Override
    public boolean putIfAbsent(String key, byte[] body) {
        return objects.putIfAbsent(key, body.clone()) == null;
    }

    /** Unconditional; for arranging fixtures, not for exercising a seal. */
    public void put(String key, byte[] body) {
        objects.put(key, body.clone());
    }

    public void remove(String key) {
        objects.remove(key);
    }

    public Map<String, byte[]> contents() {
        return Map.copyOf(objects);
    }

    /** One gzipped record object, the way the sink rotates them out. */
    public void putRecordObject(String key, List<String> ndjsonLines) {
        var buffer = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(buffer)) {
            for (var line : ndjsonLines) {
                gzip.write(line.getBytes(StandardCharsets.UTF_8));
                gzip.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        put(key, buffer.toByteArray());
    }
}
