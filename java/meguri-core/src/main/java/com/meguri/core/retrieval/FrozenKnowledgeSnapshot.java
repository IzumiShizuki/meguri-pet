package com.meguri.core.retrieval;

import com.meguri.core.knowledge.KnowledgeDocumentVersion;
import com.meguri.core.knowledge.KnowledgeStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Self-describing frozen version set. Its snapshot id is sufficient for recovery after restart.
 */
public record FrozenKnowledgeSnapshot(
        String snapshotId,
        Instant frozenAt,
        List<VersionRef> versions) {
    private static final String PREFIX = "ks1.";
    private static final int MAX_VERSIONS = 10_000;
    private static final int MAX_ID_LENGTH = 2_000_000;

    public FrozenKnowledgeSnapshot {
        if (snapshotId == null || snapshotId.isBlank() || frozenAt == null) {
            throw new IllegalArgumentException("snapshotId and frozenAt are required");
        }
        versions = versions == null ? List.of() : versions.stream()
                .sorted(Comparator.comparing(VersionRef::documentId)
                        .thenComparing(VersionRef::versionId))
                .toList();
        if (versions.size() > MAX_VERSIONS) {
            throw new IllegalArgumentException("knowledge snapshot exceeds version limit");
        }
        Set<String> documents = new LinkedHashSet<>();
        Set<String> ids = new LinkedHashSet<>();
        for (VersionRef version : versions) {
            if (!documents.add(version.documentId()) || !ids.add(version.versionId())) {
                throw new IllegalArgumentException("snapshot versions must be unique per document");
            }
        }
    }

    public Set<String> versionIds() {
        return versions.stream().map(VersionRef::versionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static FrozenKnowledgeSnapshot freeze(KnowledgeSearchProjectionPort projection, Instant at) {
        return freeze(projection, at, null);
    }

    public static FrozenKnowledgeSnapshot freeze(
            KnowledgeSearchProjectionPort projection, Instant at, String tenantId) {
        if (projection == null || at == null) {
            throw new IllegalArgumentException("projection and freeze time are required");
        }
        List<VersionRef> refs = projection.activeVersions().stream()
                .filter(version -> tenantId == null
                        || tenantId.equals(version.acl().tenantId()))
                .map(VersionRef::from)
                .toList();
        if (refs.stream().anyMatch(ref -> !ref.activeAt(at))) {
            throw new IllegalArgumentException("active version was not active at freeze time");
        }
        return create(at, refs);
    }

    public static FrozenKnowledgeSnapshot fromVersionIds(
            KnowledgeSearchProjectionPort projection, Set<String> versionIds) {
        if (projection == null || versionIds == null) {
            throw new IllegalArgumentException("projection and versionIds are required");
        }
        KnowledgeSearchProjectionPort.Projection loaded = projection.loadVersions(versionIds);
        if (loaded.versions().size() != versionIds.size()) {
            throw new IllegalArgumentException("explicit knowledge version is missing");
        }
        Instant at = loaded.versions().stream()
                .map(KnowledgeDocumentVersion::publishedAt)
                .peek(value -> {
                    if (value == null) throw new IllegalArgumentException("version was never published");
                })
                .max(Instant::compareTo)
                .orElse(Instant.EPOCH);
        List<VersionRef> refs = loaded.versions().stream().map(VersionRef::from).toList();
        FrozenKnowledgeSnapshot snapshot = create(at, refs);
        snapshot.validateProjection(loaded);
        return snapshot;
    }

    public static FrozenKnowledgeSnapshot restore(String snapshotId) {
        if (snapshotId == null || snapshotId.length() > MAX_ID_LENGTH
                || !snapshotId.startsWith(PREFIX)) {
            throw new IllegalArgumentException("unsupported knowledge snapshot id");
        }
        String[] parts = snapshotId.split("\\.", -1);
        if (parts.length != 3 || !"ks1".equals(parts[0])) {
            throw new IllegalArgumentException("malformed knowledge snapshot id");
        }
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("malformed knowledge snapshot payload", error);
        }
        if (!digest(payload).equals(parts[2])) {
            throw new IllegalArgumentException("knowledge snapshot digest mismatch");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            Instant frozenAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
            int size = input.readInt();
            if (size < 0 || size > MAX_VERSIONS) {
                throw new IllegalArgumentException("knowledge snapshot version count is invalid");
            }
            java.util.ArrayList<VersionRef> refs = new java.util.ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                refs.add(new VersionRef(
                        input.readUTF(), input.readUTF(), input.readLong(),
                        input.readUTF(), input.readUTF(),
                        readInstant(input), readNullableInstant(input)));
            }
            if (input.available() != 0) {
                throw new IllegalArgumentException("knowledge snapshot has trailing data");
            }
            return new FrozenKnowledgeSnapshot(snapshotId, frozenAt, refs);
        } catch (IOException error) {
            throw new IllegalArgumentException("malformed knowledge snapshot payload", error);
        }
    }

    public KnowledgeSearchProjectionPort.Projection loadAndValidate(
            KnowledgeSearchProjectionPort projection) {
        KnowledgeSearchProjectionPort.Projection loaded = projection.loadVersions(versionIds());
        validateProjection(loaded);
        return loaded;
    }

    private void validateProjection(KnowledgeSearchProjectionPort.Projection loaded) {
        if (loaded.versions().size() != versions.size()) {
            throw new IllegalStateException("frozen knowledge version is missing");
        }
        var actual = loaded.versions().stream().collect(java.util.stream.Collectors.toMap(
                KnowledgeDocumentVersion::id, version -> version));
        for (VersionRef expected : versions) {
            KnowledgeDocumentVersion version = actual.get(expected.versionId());
            if (version == null
                    || !expected.documentId().equals(version.documentId())
                    || expected.versionNumber() != version.versionNumber()
                    || !expected.contentHash().equals(version.contentHash())
                    || !expected.aclHash().equals(version.acl().hash())
                    || !expected.publishedAt().equals(version.publishedAt())
                    || !activeAt(version, frozenAt)) {
                throw new IllegalStateException("frozen knowledge version metadata mismatch");
            }
        }
    }

    private static boolean activeAt(KnowledgeDocumentVersion version, Instant at) {
        boolean publishedLifecycle = version.status() == KnowledgeStatus.ACTIVE
                || version.status() == KnowledgeStatus.SUPERSEDED
                || version.status() == KnowledgeStatus.DELETED;
        return publishedLifecycle && version.publishedAt() != null
                && !at.isBefore(version.publishedAt())
                && (version.supersededAt() == null || at.isBefore(version.supersededAt()));
    }

    private static FrozenKnowledgeSnapshot create(Instant at, List<VersionRef> refs) {
        List<VersionRef> ordered = refs.stream()
                .sorted(Comparator.comparing(VersionRef::documentId)
                        .thenComparing(VersionRef::versionId))
                .toList();
        byte[] payload = encode(at, ordered);
        String id = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "." + digest(payload);
        return new FrozenKnowledgeSnapshot(id, at, ordered);
    }

    private static byte[] encode(Instant at, List<VersionRef> refs) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeLong(at.getEpochSecond());
                output.writeInt(at.getNano());
                output.writeInt(refs.size());
                for (VersionRef ref : refs) {
                    output.writeUTF(ref.documentId());
                    output.writeUTF(ref.versionId());
                    output.writeLong(ref.versionNumber());
                    output.writeUTF(ref.contentHash());
                    output.writeUTF(ref.aclHash());
                    writeInstant(output, ref.publishedAt());
                    writeNullableInstant(output, ref.supersededAt());
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("failed to encode knowledge snapshot", impossible);
        }
    }

    private static String digest(byte[] value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeInstant(DataOutputStream output, Instant value) throws IOException {
        output.writeLong(value.getEpochSecond());
        output.writeInt(value.getNano());
    }

    private static Instant readInstant(DataInputStream input) throws IOException {
        return Instant.ofEpochSecond(input.readLong(), input.readInt());
    }

    private static void writeNullableInstant(DataOutputStream output, Instant value)
            throws IOException {
        output.writeBoolean(value != null);
        if (value != null) writeInstant(output, value);
    }

    private static Instant readNullableInstant(DataInputStream input) throws IOException {
        return input.readBoolean() ? readInstant(input) : null;
    }

    public record VersionRef(
            String documentId,
            String versionId,
            long versionNumber,
            String contentHash,
            String aclHash,
            Instant publishedAt,
            Instant supersededAt) {
        public VersionRef {
            if (blank(documentId) || blank(versionId) || versionNumber < 1
                    || blank(contentHash) || blank(aclHash) || publishedAt == null) {
                throw new IllegalArgumentException("invalid frozen knowledge version");
            }
        }

        static VersionRef from(KnowledgeDocumentVersion version) {
            if (version.publishedAt() == null) {
                throw new IllegalArgumentException("version was never published");
            }
            return new VersionRef(
                    version.documentId(), version.id(), version.versionNumber(),
                    version.contentHash(), version.acl().hash(),
                    version.publishedAt(), version.supersededAt());
        }

        boolean activeAt(Instant at) {
            return !at.isBefore(publishedAt)
                    && (supersededAt == null || at.isBefore(supersededAt));
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
