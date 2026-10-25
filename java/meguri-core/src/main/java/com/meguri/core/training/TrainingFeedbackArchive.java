package com.meguri.core.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Local supervision storage, independent of the authoritative PostgreSQL Turn journal. */
final class TrainingFeedbackArchive {
    record Entry(TrainingFeedbackService.Comparison comparison, String tenantId,
                 String feedbackId, String requestId, String payloadDigest,
                 String selectedCandidateId, String status, Map<String, Object> submissionRow,
                 String revokeRequestId, Map<String, Object> revokeRow) { }

    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Path output;
    private final Path directory;

    TrainingFeedbackArchive(ObjectMapper mapper, Path output) {
        this.mapper = mapper;
        this.output = output;
        this.directory = output.resolveSibling(output.getFileName() + ".pending");
    }

    <T> T locked(Supplier<T> operation) {
        synchronized (LOCKS.computeIfAbsent(directory, ignored -> new Object())) {
            try {
                Files.createDirectories(directory);
                try (FileChannel channel = FileChannel.open(directory.resolve(".lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     var ignored = channel.lock()) {
                    return operation.get();
                }
            } catch (IOException error) {
                throw storageError(error);
            }
        }
    }

    Entry find(String turnId) {
        Path path = path(turnId);
        return Files.isRegularFile(path) ? read(path) : null;
    }

    List<Entry> entries() {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().map(this::read).toList();
        } catch (IOException error) { throw storageError(error); }
    }

    void save(Entry entry) {
        Path target = path(entry.comparison().turnId());
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, ".write-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                writeFully(channel, mapper.writeValueAsBytes(entry));
                channel.force(true);
            }
            // Do not silently fall back to a non-atomic replacement.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { throw storageError(error); }
        finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    /** The archive intent is written first; replay appends each logical event at most once. */
    void appendOnce(Map<String, Object> row) {
        try {
            if (Files.isRegularFile(output)) {
                byte[] bytes = Files.readAllBytes(output);
                int start = 0;
                boolean seen = false;
                for (int index = 0; index <= bytes.length; index++) {
                    if (index != bytes.length && bytes[index] != '\n') continue;
                    if (index > start) {
                        try {
                            var prior = mapper.readTree(new String(bytes, start, index - start, StandardCharsets.UTF_8));
                            if (String.valueOf(row.get("feedback_id")).equals(prior.path("feedback_id").asText())
                                    && String.valueOf(row.get("status")).equals(prior.path("status").asText())) seen = true;
                        } catch (IOException malformed) {
                            if (index != bytes.length) throw malformed;
                            // Only an unterminated trailing write may be repaired. Interior corruption fails closed.
                            try (FileChannel channel = FileChannel.open(output, StandardOpenOption.WRITE)) {
                                channel.truncate(start); channel.force(true);
                            }
                            break;
                        }
                    }
                    start = index + 1;
                }
                if (seen) return;
            }
            byte[] line = (mapper.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(output, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                long size = channel.size();
                if (size > 0) {
                    ByteBuffer last = ByteBuffer.allocate(1);
                    channel.position(size - 1); channel.read(last);
                    channel.position(size);
                    if (last.array()[0] != '\n') writeFully(channel, new byte[]{'\n'});
                }
                writeFully(channel, line);
                channel.force(true);
            }
        } catch (IOException error) { throw storageError(error); }
    }

    private Entry read(Path path) {
        try { return mapper.readValue(Files.readAllBytes(path), Entry.class); }
        catch (IOException error) { throw storageError(error); }
    }

    private Path path(String turnId) { return directory.resolve(digest(turnId) + ".json"); }

    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static IllegalStateException storageError(Exception error) {
        return new IllegalStateException("training_feedback_storage_unavailable", error);
    }
}
