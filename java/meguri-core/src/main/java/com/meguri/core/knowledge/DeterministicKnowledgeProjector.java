package com.meguri.core.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline parser/chunker/projector. CHILD paragraphs are retrieval units while the
 * complete page PARENT restores context.
 */
public final class DeterministicKnowledgeProjector implements KnowledgeProjector {
    private static final Pattern ENTITY =
            Pattern.compile("\\[\\[entity:([^|\\]]+)\\|([^\\]]+)]]", Pattern.CASE_INSENSITIVE);
    private static final Pattern RELATION =
            Pattern.compile("\\[\\[relation:([^|\\]]+)\\|([^|\\]]+)\\|([^\\]]+)]]",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern ENGLISH_RELATION = Pattern.compile(
            "(?iu)([\\p{L}\\p{N}][\\p{L}\\p{N}_ .-]{0,79}?)\\s+"
                    + "(works\\s+with|depends\\s+on|belongs\\s+to|calls|owns)\\s+"
                    + "([\\p{L}\\p{N}][\\p{L}\\p{N}_ .-]{0,79}?)"
                    + "(?:[.!?]|$)");
    private static final Pattern CHINESE_RELATION = Pattern.compile(
            "([\\p{IsHan}A-Za-z0-9_.-]{1,40})\\s*"
                    + "(依赖|调用|属于|负责)\\s*"
                    + "([\\p{IsHan}A-Za-z0-9_.-]{1,40})"
                    + "(?:[。！？]|$)");
    private static final Pattern CHINESE_COOPERATION = Pattern.compile(
            "([\\p{IsHan}A-Za-z0-9_.-]{1,40})\\s*(?:与|和)\\s*"
                    + "([\\p{IsHan}A-Za-z0-9_.-]{1,40})\\s*"
                    + "(合作|相关|连接)(?:[。！？]|$)");
    private static final Pattern HEADING =
            Pattern.compile("(?m)^\\s*(#{1,6})\\s+(.+?)\\s*$");

    @Override
    public KnowledgeBuild project(KnowledgeDocument document,
                                  KnowledgeDocumentVersion version,
                                  SourcePage page) {
        String normalized = page.content().replace("\r\n", "\n").trim();
        if (normalized.isBlank()) throw new IllegalArgumentException("knowledge page content is empty");

        ArrayList<KnowledgeChunk> chunks = new ArrayList<>();
        ArrayList<KnowledgeChunk> children = new ArrayList<>();
        int childOrdinal = 1;
        List<Section> sections = sections(normalized, page.title());
        for (int sectionOrdinal = 0;
             sectionOrdinal < sections.size(); sectionOrdinal++) {
            Section section = sections.get(sectionOrdinal);
            SourceParagraph first = section.paragraphs().getFirst();
            SourceParagraph last = section.paragraphs().getLast();
            String parentContent = normalized.substring(first.start(), last.end());
            String parentId = id(
                    version.id(), "parent", Integer.toString(sectionOrdinal));
            KnowledgeChunkMetadata parentMetadata = KnowledgeChunkMetadata.anchored(
                    parentContent, section.path(), section.heading(),
                    first.start(), last.end());
            chunks.add(new KnowledgeChunk(
                    parentId, document.id(), version.id(), ChunkRole.PARENT, null,
                    sectionOrdinal, parentContent, version.acl(), version.createdAt(),
                    KnowledgeChunk.END_OF_TIME, parentMetadata,
                    KnowledgeStatus.BUILDING));

            for (SourceParagraph paragraph : section.paragraphs()) {
                KnowledgeChunkMetadata metadata = KnowledgeChunkMetadata.anchored(
                        paragraph.content(), section.path(), section.heading(),
                        paragraph.start(), paragraph.end());
                KnowledgeChunk child = new KnowledgeChunk(
                        id(version.id(), "child", Integer.toString(childOrdinal)),
                        document.id(), version.id(), ChunkRole.CHILD, parentId,
                        childOrdinal, paragraph.content(), version.acl(),
                        version.createdAt(), KnowledgeChunk.END_OF_TIME, metadata,
                        KnowledgeStatus.BUILDING);
                chunks.add(child);
                children.add(child);
                childOrdinal++;
            }
        }

        ArrayList<KnowledgeEntity> entities = new ArrayList<>();
        Map<String, KnowledgeEntity> byName = new HashMap<>();
        for (KnowledgeChunk child : children) {
            Matcher matcher = ENTITY.matcher(child.content());
            while (matcher.find()) {
                String type = matcher.group(1).trim();
                String name = matcher.group(2).trim();
                KnowledgeEntity entity = byName.get(name);
                if (entity == null) {
                    entity = new KnowledgeEntity(
                            id(version.id(), "entity", type + "\n" + name),
                            document.id(), version.id(), type, name, List.of(name),
                            child.id(), version.acl(), child.validFrom(),
                            child.validUntil(), KnowledgeStatus.BUILDING,
                            version.createdAt());
                    entities.add(entity);
                    byName.put(name, entity);
                }
            }
        }

        ArrayList<KnowledgeRelation> relations = new ArrayList<>();
        Set<String> relationKeys = new HashSet<>();
        for (KnowledgeChunk child : children) {
            Matcher matcher = RELATION.matcher(child.content());
            while (matcher.find()) {
                String fromName = matcher.group(1).trim();
                String type = matcher.group(2).trim();
                String toName = matcher.group(3).trim();
                KnowledgeEntity from = byName.get(fromName);
                KnowledgeEntity to = byName.get(toName);
                if (from == null || to == null) {
                    throw new IllegalArgumentException("relation endpoints must be declared as entities");
                }
                addRelation(
                        relations, relationKeys, document, version, child,
                        from, type, to, 1.0);
            }

            String plain = stripAnnotations(child.content());
            Matcher english = ENGLISH_RELATION.matcher(plain);
            while (english.find()) {
                KnowledgeEntity from = entity(
                        entities, byName, document, version, child,
                        english.group(1), "concept");
                KnowledgeEntity to = entity(
                        entities, byName, document, version, child,
                        english.group(3), "concept");
                addRelation(
                        relations, relationKeys, document, version, child,
                        from, relationType(english.group(2)), to, 0.85);
            }
            Matcher chinese = CHINESE_RELATION.matcher(plain);
            while (chinese.find()) {
                KnowledgeEntity from = entity(
                        entities, byName, document, version, child,
                        chinese.group(1), "concept");
                KnowledgeEntity to = entity(
                        entities, byName, document, version, child,
                        chinese.group(3), "concept");
                addRelation(
                        relations, relationKeys, document, version, child,
                        from, relationType(chinese.group(2)), to, 0.85);
            }
            Matcher cooperation = CHINESE_COOPERATION.matcher(plain);
            while (cooperation.find()) {
                KnowledgeEntity from = entity(
                        entities, byName, document, version, child,
                        cooperation.group(1), "concept");
                KnowledgeEntity to = entity(
                        entities, byName, document, version, child,
                        cooperation.group(2), "concept");
                addRelation(
                        relations, relationKeys, document, version, child,
                        from, relationType(cooperation.group(3)), to, 0.8);
            }
        }
        List<KnowledgeTermProjection> termProjections = children.stream()
                .map(DeterministicSearchProjector::terms)
                .toList();
        List<KnowledgeVectorProjection> vectorProjections = children.stream()
                .map(DeterministicSearchProjector::vector)
                .toList();
        return new KnowledgeBuild(
                chunks, entities, relations, termProjections, vectorProjections);
    }

    private static KnowledgeEntity entity(
            List<KnowledgeEntity> entities,
            Map<String, KnowledgeEntity> byName,
            KnowledgeDocument document,
            KnowledgeDocumentVersion version,
            KnowledgeChunk child,
            String rawName,
            String type) {
        String name = rawName.trim();
        KnowledgeEntity existing = byName.get(name);
        if (existing != null) return existing;
        KnowledgeEntity created = new KnowledgeEntity(
                id(version.id(), "entity", type + "\n" + name),
                document.id(), version.id(), type, name, List.of(name),
                child.id(), version.acl(), child.validFrom(),
                child.validUntil(), KnowledgeStatus.BUILDING,
                version.createdAt());
        entities.add(created);
        byName.put(name, created);
        return created;
    }

    private static void addRelation(
            List<KnowledgeRelation> relations,
            Set<String> keys,
            KnowledgeDocument document,
            KnowledgeDocumentVersion version,
            KnowledgeChunk child,
            KnowledgeEntity from,
            String rawType,
            KnowledgeEntity to,
            double confidence) {
        String type = relationType(rawType);
        String key = from.id() + "\n" + type + "\n" + to.id();
        if (!keys.add(key)) return;
        relations.add(new KnowledgeRelation(
                id(version.id(), "relation", key),
                document.id(), version.id(), from.id(), type, to.id(),
                child.id(), version.acl(), confidence, child.validFrom(),
                child.validUntil(), KnowledgeStatus.BUILDING,
                version.createdAt()));
    }

    private static String stripAnnotations(String value) {
        String entities = ENTITY.matcher(value).replaceAll("$2");
        return RELATION.matcher(entities).replaceAll("$1 $2 $3");
    }

    private static String relationType(String value) {
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ")) {
            case "works with", "合作" -> "works_with";
            case "depends on", "依赖" -> "depends_on";
            case "belongs to", "属于" -> "belongs_to";
            case "calls", "调用" -> "calls";
            case "owns", "负责" -> "owns";
            case "相关" -> "related_to";
            case "连接" -> "connected_to";
            default -> value.trim().toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N}]+", "_");
        };
    }

    private static List<Section> sections(String normalized, String pageTitle) {
        ArrayList<Section> sections = new ArrayList<>();
        ArrayList<String> hierarchy = new ArrayList<>();
        ArrayList<SourceParagraph> current = null;
        List<String> currentPath = List.of(pageTitle);
        String currentHeading = pageTitle;
        int searchFrom = 0;
        for (String raw : normalized.split("\\n\\s*\\n")) {
            String content = raw.trim();
            if (content.isBlank()) continue;
            int start = normalized.indexOf(content, searchFrom);
            if (start < 0) {
                throw new IllegalStateException(
                        "chunk source anchor could not be resolved");
            }
            int end = start + content.length();
            searchFrom = end;

            Matcher matcher = HEADING.matcher(content);
            if (matcher.find()) {
                if (current != null && !current.isEmpty()) {
                    sections.add(new Section(
                            currentPath, currentHeading, current));
                }
                int level = matcher.group(1).length();
                String heading = matcher.group(2).strip();
                while (hierarchy.size() >= level) hierarchy.removeLast();
                hierarchy.add(heading);
                currentPath = List.copyOf(hierarchy);
                currentHeading = heading;
                current = new ArrayList<>();
            } else if (current == null) {
                current = new ArrayList<>();
            }
            current.add(new SourceParagraph(content, start, end));
        }
        if (current != null && !current.isEmpty()) {
            sections.add(new Section(currentPath, currentHeading, current));
        }
        return List.copyOf(sections);
    }

    private record SourceParagraph(String content, int start, int end) { }

    private record Section(
            List<String> path,
            String heading,
            List<SourceParagraph> paragraphs) {
        private Section {
            path = List.copyOf(path);
            paragraphs = List.copyOf(paragraphs);
        }
    }

    private static String id(String versionId, String kind, String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((versionId + "\n" + kind + "\n" + seed).getBytes(StandardCharsets.UTF_8));
            return "k" + kind.substring(0, Math.min(3, kind.length()))
                    + "_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
