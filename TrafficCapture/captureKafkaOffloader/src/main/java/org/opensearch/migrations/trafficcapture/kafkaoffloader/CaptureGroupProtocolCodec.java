package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.AdmissionPhase;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.AssignmentTable;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.Footprint;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.FootprintIdentity;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.MemberRow;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.Subscription;

/**
 * Deterministic binary encoding for capture membership subscription and assignment {@code userData}.
 */
final class CaptureGroupProtocolCodec {
    private static final int MAGIC = 0x43504750;
    private static final short VERSION = 2;
    private static final byte SUBSCRIPTION = 1;
    private static final byte ASSIGNMENT = 2;
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final int MAX_PARTITIONS = 100_000;
    private static final int MAX_MEMBERS = 10_000;

    private CaptureGroupProtocolCodec() {}

    static ByteBuffer encodeSubscription(Subscription subscription) {
        Objects.requireNonNull(subscription);
        return encode(SUBSCRIPTION, output -> writeSubscription(output, subscription));
    }

    static Subscription decodeSubscription(ByteBuffer encoded) {
        return decode(encoded, SUBSCRIPTION, CaptureGroupProtocolCodec::readSubscription);
    }

    static ByteBuffer encodeAssignment(AssignmentTable table) {
        Objects.requireNonNull(table);
        return encode(ASSIGNMENT, output -> {
            writeCount(output, table.members().size(), MAX_MEMBERS, "assignment members");
            for (var entry : table.members().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                writeMemberRow(output, entry.getValue());
            }
        });
    }

    static AssignmentTable decodeAssignment(ByteBuffer encoded) {
        return decode(encoded, ASSIGNMENT, input -> {
            int count = readCount(input, MAX_MEMBERS, "assignment members");
            var members = new LinkedHashMap<String, MemberRow>();
            for (int i = 0; i < count; ++i) {
                var row = readMemberRow(input);
                if (members.putIfAbsent(row.nodeId(), row) != null) {
                    throw new IllegalStateException("assignment metadata contains a duplicate member");
                }
            }
            return new AssignmentTable(members);
        });
    }

    private static ByteBuffer encode(byte type, IoWriter writer) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeShort(VERSION);
                output.writeByte(type);
                writer.write(output);
            }
            var encoded = bytes.toByteArray();
            if (encoded.length > MAX_METADATA_BYTES) {
                throw new IllegalArgumentException("capture membership metadata exceeds one MiB");
            }
            return ByteBuffer.wrap(encoded).asReadOnlyBuffer();
        } catch (IOException e) {
            throw new IllegalStateException("unable to encode capture membership metadata", e);
        }
    }

    private static <T> T decode(ByteBuffer encoded, byte expectedType, IoReader<T> reader) {
        Objects.requireNonNull(encoded);
        var data = encoded.duplicate();
        if (data.remaining() > MAX_METADATA_BYTES) {
            throw new IllegalStateException("capture membership metadata exceeds one MiB");
        }
        var bytes = new byte[data.remaining()];
        data.get(bytes);
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                throw new IllegalStateException("capture membership metadata has invalid magic");
            }
            if (input.readShort() != VERSION) {
                throw new IllegalStateException("capture membership metadata has unsupported version");
            }
            if (input.readByte() != expectedType) {
                throw new IllegalStateException("capture membership metadata has the wrong record type");
            }
            var result = reader.read(input);
            if (input.read() != -1) {
                throw new IllegalStateException("capture membership metadata contains trailing bytes");
            }
            return result;
        } catch (EOFException e) {
            throw new IllegalStateException("capture membership metadata is truncated", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("capture membership metadata is invalid", e);
        }
    }

    private static void writeSubscription(DataOutputStream output, Subscription subscription) throws IOException {
        writeString(output, subscription.nodeId());
        writePhase(output, subscription.advertisedPhase());
        writeFootprint(output, subscription.footprint());
        writeIdentities(output, subscription.observedFootprints());
        writeIdentities(output, subscription.confirmedPeerVisibility());
    }

    private static Subscription readSubscription(DataInputStream input) throws IOException {
        return new Subscription(
            readString(input),
            readPhase(input),
            readFootprint(input),
            readIdentities(input),
            readIdentities(input)
        );
    }

    private static void writeMemberRow(DataOutputStream output, MemberRow row) throws IOException {
        writeString(output, row.nodeId());
        writePhase(output, row.advertisedPhase());
        writePhase(output, row.effectivePhase());
        writeFootprint(output, row.footprint());
        writeIdentities(output, row.observedFootprints());
        writeIdentities(output, row.confirmedPeerVisibility());
    }

    private static MemberRow readMemberRow(DataInputStream input) throws IOException {
        return new MemberRow(
            readString(input),
            readPhase(input),
            readPhase(input),
            readFootprint(input),
            readIdentities(input),
            readIdentities(input)
        );
    }

    private static void writeFootprint(DataOutputStream output, Footprint footprint) throws IOException {
        output.writeLong(footprint.revision());
        output.writeBoolean(footprint.unknown());
        writeString(output, footprint.digest());
        writeCount(output, footprint.partitions().size(), MAX_PARTITIONS, "footprint partitions");
        for (var partition : footprint.partitions()) {
            output.writeInt(partition);
        }
    }

    private static Footprint readFootprint(DataInputStream input) throws IOException {
        long revision = input.readLong();
        boolean unknown = input.readBoolean();
        String digest = readString(input);
        int count = readCount(input, MAX_PARTITIONS, "footprint partitions");
        var partitions = new java.util.ArrayList<Integer>(count);
        for (int i = 0; i < count; ++i) {
            partitions.add(input.readInt());
        }
        return new Footprint(revision, unknown, partitions, digest);
    }

    private static void writeIdentities(
        DataOutputStream output,
        Map<String, FootprintIdentity> identities
    ) throws IOException {
        writeCount(output, identities.size(), MAX_MEMBERS, "footprint identities");
        for (var entry : identities.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            writeString(output, entry.getKey());
            writeString(output, entry.getValue().nodeId());
            output.writeLong(entry.getValue().revision());
            writeString(output, entry.getValue().digest());
        }
    }

    private static Map<String, FootprintIdentity> readIdentities(DataInputStream input) throws IOException {
        int count = readCount(input, MAX_MEMBERS, "footprint identities");
        var identities = new LinkedHashMap<String, FootprintIdentity>();
        for (int i = 0; i < count; ++i) {
            String key = readString(input);
            var identity = new FootprintIdentity(readString(input), input.readLong(), readString(input));
            if (identities.putIfAbsent(key, identity) != null) {
                throw new IllegalStateException("capture membership metadata contains a duplicate identity");
            }
        }
        return Collections.unmodifiableMap(identities);
    }

    private static void writePhase(DataOutputStream output, AdmissionPhase phase) throws IOException {
        output.writeByte(phase.ordinal());
    }

    private static AdmissionPhase readPhase(DataInputStream input) throws IOException {
        int ordinal = input.readUnsignedByte();
        var phases = AdmissionPhase.values();
        if (ordinal >= phases.length) {
            throw new IllegalStateException("capture membership metadata contains an invalid phase");
        }
        return phases[ordinal];
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        writeCount(output, bytes.length, MAX_STRING_BYTES, "string bytes");
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = readCount(input, MAX_STRING_BYTES, "string bytes");
        var bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeCount(DataOutputStream output, int count, int maximum, String label)
        throws IOException {
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException(label + " exceeds protocol limit");
        }
        output.writeInt(count);
    }

    private static int readCount(DataInputStream input, int maximum, String label) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw new IllegalStateException(label + " exceeds protocol limit");
        }
        return count;
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    private interface IoReader<T> {
        T read(DataInputStream input) throws IOException;
    }
}
