package org.opensearch.migrations.bulkload.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import shadow.lucene9.org.apache.lucene.codecs.CodecUtil;

/**
 * Utility class for handling compression detection and decompression of snapshot metadata files.
 * Supports both standard Lucene codec format and OpenSearch DEFLATE compression with DFL\0 header.
 */
public class SnapshotMetadataLoader {
    private static final byte[] DFL_HEADER = { 'D', 'F', 'L', 0 };

    private SnapshotMetadataLoader() {
        // Utility class - prevent instantiation
    }

    /**
     * Processes snapshot metadata bytes, detecting and handling compression if present.
     * 
     * @param bytes The raw bytes from the snapshot metadata file
     * @param codecName The name of the codec (e.g., "metadata", "index-metadata")
     * @return An InputStream positioned at the start of the actual metadata content
     * @throws IOException if decompression or codec validation fails
     */
    public static InputStream processMetadataBytes(byte[] bytes, String codecName) throws IOException {
        ByteArrayIndexInput indexInput = new ByteArrayIndexInput(codecName, bytes);

        if (findDFLHeader(bytes) != -1) {
            // OpenSearch DEFLATE compression detected
            byte[] uncompressed = decompress(bytes);
            return new ByteArrayInputStream(uncompressed, 0, uncompressed.length);
        } else {
            // Standard Lucene codec format
            CodecUtil.checksumEntireFile(indexInput);
            CodecUtil.checkHeader(indexInput, codecName, 1, 1);
            int filePointer = (int) indexInput.getFilePointer();
            return new ByteArrayInputStream(bytes, filePointer, bytes.length - filePointer);
        }
    }

    /**
     * Returns the index where the DFL\0 header begins, or -1 if not found.
     * 
     * @param data The byte array to search
     * @return The starting index of the DFL\0 header, or -1 if not found
     */
    private static int findDFLHeader(byte[] data) {
        for (int i = 0; i <= data.length - DFL_HEADER.length; i++) {
            boolean headerMatch = true;
            for (int j = 0; j < DFL_HEADER.length; j++) {
                if (data[i + j] != DFL_HEADER[j]) {
                    headerMatch = false;
                    break;
                }
            }
            if (headerMatch) {
                return i; // header found
            }
        }
        return -1; // header not found
    }

    /**
     * Decompresses OpenSearch DEFLATE data, searching for the DFL\0 header.
     * 
     * @param in The compressed byte array
     * @return The decompressed byte array
     * @throws IOException if decompression fails
     * @throws IllegalArgumentException if DFL\0 header is not found
     */
    private static byte[] decompress(byte[] in) throws IOException {
        int pos = findDFLHeader(in);
        if (pos < 0) {
            throw new IllegalArgumentException("DFL\\0 header not found");
        }

        int start = pos + DFL_HEADER.length;
        Inflater inflater = new Inflater(true); // raw DEFLATE

        try (ByteArrayInputStream bais = new ByteArrayInputStream(in, start, in.length - start);
             InflaterInputStream iis = new InflaterInputStream(bais, inflater);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buf = new byte[4096];
            int n;
            while ((n = iis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } finally {
            inflater.end();
        }
    }
}
