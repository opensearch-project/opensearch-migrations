package org.opensearch.migrations.bulkload.common;

import java.io.EOFException;
import java.io.IOException;

import shadow.lucene9.org.apache.lucene.store.IndexInput;

/**
 * This class was originally in the Lucene project, but was removed in version 8.0.0.  The Elastic codebase has its own
 * copy of it, which I've made a copy of.
 * See: https://github.com/elastic/elasticsearch/blob/v7.10.2/server/src/main/java/org/elasticsearch/common/lucene/store/ByteArrayIndexInput.java
 */
public class ByteArrayIndexInput extends IndexInput {
    private final byte[] bytes;

    private int pos;

    private int offset;

    private int length;

    public ByteArrayIndexInput(String resourceDesc, byte[] bytes) {
        this(resourceDesc, bytes, 0, bytes.length);
    }

    public ByteArrayIndexInput(String resourceDesc, byte[] bytes, int offset, int length) {
        super(resourceDesc);
        this.bytes = bytes;
        this.offset = offset;
        this.length = length;
    }

    @Override
    public void close() throws IOException {
        // Empty in the original implementation, and seems to work
    }

    @Override
    public long getFilePointer() {
        return pos;
    }

    @Override
    public void seek(long l) throws IOException {
        if (l < 0) {
            throw new IllegalArgumentException("Seeking to negative position: " + pos);
        } else if (l > length) {
            throw new EOFException();
        }
        pos = (int) l;
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
        if (offset >= 0L && length >= 0L && offset + length <= this.length) {
            return new ByteArrayIndexInput(sliceDescription, bytes, this.offset + (int) offset, (int) length);
        } else {
            throw new IllegalArgumentException(
                "slice() "
                    + sliceDescription
                    + " out of bounds: offset="
                    + offset
                    + ",length="
                    + length
                    + ",fileLength="
                    + this.length
                    + ": "
                    + this
            );
        }
    }

    @Override
    public byte readByte() throws IOException {
        if (pos >= offset + length) {
            throw new EOFException();
        }
        return bytes[offset + pos++];
    }

    @Override
    public void readBytes(final byte[] b, final int offset, int len) throws IOException {
        if (pos + len > this.offset + length) {
            throw new EOFException();
        }
        System.arraycopy(bytes, this.offset + pos, b, offset, len);
        pos += len;
    }
}
