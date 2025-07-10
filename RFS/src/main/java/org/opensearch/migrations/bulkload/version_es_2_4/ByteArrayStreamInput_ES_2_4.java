package org.opensearch.migrations.bulkload.version_es_2_4;

import java.io.IOException;

import lombok.extern.slf4j.Slf4j;
import shadow.lucene6.org.apache.lucene.store.ByteArrayDataInput;

@Slf4j
public class ByteArrayStreamInput_ES_2_4 {
    private final ByteArrayDataInput in;

    public ByteArrayStreamInput_ES_2_4(byte[] bytes) {
        this.in = new ByteArrayDataInput(bytes);
    }

    private void log(String msg) {
        log.debug("pos={} {}", in.getPosition(), msg);
    }

    public String readString() throws IOException {
        log("Reading string - START");
        int charCount = readVInt();
        log("readString - charCount=" + charCount);
        char[] chars = new char[charCount];
        int charIndex = 0;

        while (charIndex < charCount) {
            int c = readByte() & 0xFF;
            log(String.format("readString - byte=%02X", c));

            switch (c >> 4) {
                case 0: case 1: case 2: case 3:
                case 4: case 5: case 6: case 7:
                    // Single byte
                    chars[charIndex++] = (char) c;
                    break;

                case 12: case 13:
                    // Two bytes
                    int c2 = readByte() & 0xFF;
                    log(String.format("readString - c2=%02X", c2));
                    chars[charIndex++] = (char) (((c & 0x1F) << 6) | (c2 & 0x3F));
                    break;

                case 14:
                    // Three bytes
                    int c2b = readByte() & 0xFF;
                    int c3 = readByte() & 0xFF;
                    log(String.format("readString - c2b=%02X c3=%02X", c2b, c3));
                    chars[charIndex++] = (char) (((c & 0x0F) << 12) | ((c2b & 0x3F) << 6) | (c3 & 0x3F));
                    break;

                // default:
                //     throw new IOException("Invalid modified UTF-8 in snapshot stream at byte: " + c);
            }
        }

        String result = new String(chars, 0, charCount);
        log("readString - END = " + result);
        return result;
    }

    public int readVInt() {
        int start = in.getPosition();
        byte b = in.readByte();
        int i = b & 0x7F;
        if ((b & 0x80) == 0) {
            log(String.format("readVInt (1-byte) @%d = %d", start, i));
            return i;
        }
        b = in.readByte();
        i |= (b & 0x7F) << 7;
        if ((b & 0x80) == 0) {
            log(String.format("readVInt (2-byte) @%d = %d", start, i));
            return i;
        }
        b = in.readByte();
        i |= (b & 0x7F) << 14;
        if ((b & 0x80) == 0) {
            log(String.format("readVInt (3-byte) @%d = %d", start, i));
            return i;
        }
        b = in.readByte();
        i |= (b & 0x7F) << 21;
        if ((b & 0x80) == 0) {
            log(String.format("readVInt (4-byte) @%d = %d", start, i));
            return i;
        }
        b = in.readByte();
        assert (b & 0x80) == 0;
        i |= (b & 0x7F) << 28;
        log(String.format("readVInt (5-byte) @%d = %d", start, i));
        return i;
    }

    public long readLong() {
        return (((long) readInt()) << 32) | (readInt() & 0xFFFFFFFFL);
    }

    public int readInt() {
        return ((in.readByte() & 0xFF) << 24) |
               ((in.readByte() & 0xFF) << 16) |
               ((in.readByte() & 0xFF) << 8) |
               (in.readByte() & 0xFF);
    }

    public byte readByte() {
        byte val = in.readByte();
        log(String.format("readByte = %02X", val));
        return val;
    }

    public byte[] readByteArray() {
        int length = readVInt();
        byte[] bytes = new byte[length];
        in.readBytes(bytes, 0, length);
        return bytes;
    }

    public long[] readVLongArray() {
        int length = readVInt();
        long[] arr = new long[length];
        for (int i = 0; i < length; i++) {
            arr[i] = readVLong();
        }
        return arr;
    }

    public long readVLong() {
        byte b = in.readByte();
        long i = b & 0x7FL;
        if ((b & 0x80) == 0) return i;
        b = in.readByte();
        i |= (b & 0x7FL) << 7;
        if ((b & 0x80) == 0) return i;
        b = in.readByte();
        i |= (b & 0x7FL) << 14;
        if ((b & 0x80) == 0) return i;
        b = in.readByte();
        i |= (b & 0x7FL) << 21;
        if ((b & 0x80) == 0) return i;
        b = in.readByte();
        i |= (b & 0x7FL) << 28;
        if ((b & 0x80) == 0) return i;
        b = in.readByte();
        i |= (b & 0x7FL) << 35;
        if ((b & 0x80) == 0) return i;
        b = in.readByte();
        i |= (b & 0x7FL) << 42;
        if ((b & 0x80) == 0) return i;
        b = in.readByte();
        i |= (b & 0x7FL) << 49;
        if ((b & 0x80) == 0) return i;
        b = in.readByte();
        i |= (b & 0x7FL) << 56;
        return i;
    }

    public String readOptionalString() {
        if (readBoolean()) {
            try {
                return readString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    public Boolean readOptionalBoolean() {
        byte val = readByte();
        if (val == 2) return null;
        return val == 1;
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    public int getPosition() {
        return in.getPosition();
    }
}
