package com.meguri.core.skill;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Strict, non-executing validation of an external Skill directory. */
public final class SkillPackageValidator {
    public static final long MAX_FILE_BYTES = 1_048_576L;
    public static final long MAX_PACKAGE_BYTES = 8_388_608L;
    public static final int MAX_FILES = 256;
    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
            "exe", "dll", "com", "msi", "scr", "sys", "class", "jar", "so", "dylib");
    private final Predicate<String> capabilityAvailable;
    private final Predicate<String> environmentConfigured;

    public SkillPackageValidator(Set<String> capabilityIds, Predicate<String> environmentConfigured) {
        Set<String> frozenIds = capabilityIds == null ? Set.of() : Set.copyOf(capabilityIds);
        this.capabilityAvailable = frozenIds::contains;
        this.environmentConfigured = environmentConfigured == null ? ignored -> false : environmentConfigured;
    }

    public SkillPackageValidator(Predicate<String> capabilityAvailable, Predicate<String> environmentConfigured) {
        this.capabilityAvailable = capabilityAvailable == null ? ignored -> false : capabilityAvailable;
        this.environmentConfigured = environmentConfigured == null ? ignored -> false : environmentConfigured;
    }

    public ValidationResult validate(Path stagingRoot) {
        Path root = stagingRoot.toAbsolutePath().normalize();
        ArrayList<SkillValidationReport.Issue> issues = new ArrayList<>();
        ArrayList<Path> files = new ArrayList<>();
        Set<String> canonicalPaths = new HashSet<>();
        long[] total = {0L};
        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                error(issues, "SKILL_ROOT_MISSING", "package root is unavailable", null);
            } else {
                Files.walkFileTree(root, java.util.Set.of(), 32, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        inspectEntry(root, dir, attrs, canonicalPaths, issues); return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        inspectEntry(root, file, attrs, canonicalPaths, issues);
                        if (attrs.isRegularFile()) {
                            files.add(file); total[0] += attrs.size();
                            String relative = relative(root, file);
                            if (attrs.size() > MAX_FILE_BYTES) error(issues, "SKILL_FILE_TOO_LARGE", "file exceeds limit", relative);
                            if (isDangerousBinary(file, relative)) error(issues, "SKILL_DANGEROUS_BINARY", "dangerous binary is not allowed", relative);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        error(issues, "SKILL_FILE_UNREADABLE", "package entry is unreadable", relative(root, file));
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException error) {
            error(issues, "SKILL_PACKAGE_WALK_FAILED", "package cannot be inspected", null);
        }
        if (files.size() > MAX_FILES) error(issues, "SKILL_FILE_COUNT_EXCEEDED", "package has too many files", null);
        if (total[0] > MAX_PACKAGE_BYTES) error(issues, "SKILL_PACKAGE_TOO_LARGE", "package exceeds size limit", null);

        List<Path> skillFiles = files.stream()
                .filter(file -> file.getFileName().toString().equalsIgnoreCase("SKILL.md")).toList();
        Path rootSkill = root.resolve("SKILL.md");
        if (skillFiles.size() != 1) {
            error(issues, skillFiles.isEmpty() ? "SKILL_MANIFEST_MISSING" : "SKILL_MANIFEST_DUPLICATE",
                    "package must contain exactly one SKILL.md", null);
        } else if (!skillFiles.getFirst().normalize().equals(rootSkill)) {
            error(issues, "SKILL_MANIFEST_NOT_ROOT", "SKILL.md must be at package root", relative(root, skillFiles.getFirst()));
        }

        Frontmatter frontmatter = null;
        if (Files.isRegularFile(rootSkill, LinkOption.NOFOLLOW_LINKS)) {
            frontmatter = parseFrontmatter(rootSkill, issues);
            if (frontmatter != null) checkCompatibility(frontmatter, issues);
        }
        boolean invalid = issues.stream().anyMatch(v -> v.severity() == SkillValidationReport.Severity.ERROR);
        boolean compatible = !invalid && issues.stream().noneMatch(v -> v.code().startsWith("SKILL_REQUIREMENT_"));
        boolean redistributable = frontmatter != null && frontmatter.license() != null;
        SkillValidationReport report = new SkillValidationReport(
                invalid ? SkillValidationReport.ValidationState.INVALID : SkillValidationReport.ValidationState.VALID,
                compatible, redistributable, issues, Instant.now());
        return new ValidationResult(frontmatter, report, files.size(), total[0]);
    }

    private void checkCompatibility(Frontmatter value, List<SkillValidationReport.Issue> issues) {
        if (value.license() == null) warning(issues, "SKILL_LICENSE_MISSING", "license is missing; redistribution is prohibited", "SKILL.md");
        if (value.always()) warning(issues, "SKILL_ALWAYS_IGNORED", "always is ignored; selection remains query-driven", "SKILL.md");
        for (String tool : value.requires().tools()) {
            String mapped = "web_search".equals(tool) ? "legacy.web.search" : tool;
            if (Set.of("terminal", "shell", "code_executor").contains(tool)) {
                warning(issues, "SKILL_REQUIREMENT_TOOL_UNSUPPORTED", "unsupported tool: " + tool, "SKILL.md");
            } else if (!capabilityAvailable.test(mapped)) {
                warning(issues, "SKILL_REQUIREMENT_TOOL_MISSING", "missing capability: " + mapped, "SKILL.md");
            }
        }
        for (String name : value.requires().env()) {
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
                warning(issues, "SKILL_REQUIREMENT_ENV_INVALID", "invalid environment name", "SKILL.md");
            } else if (!environmentConfigured.test(name)) {
                warning(issues, "SKILL_REQUIREMENT_ENV_MISSING", "environment is not configured: " + name, "SKILL.md");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Frontmatter parseFrontmatter(Path skillFile, List<SkillValidationReport.Issue> issues) {
        try {
            String text = Files.readString(skillFile, StandardCharsets.UTF_8);
            if (!text.startsWith("---\n") && !text.startsWith("---\r\n")) {
                error(issues, "SKILL_FRONTMATTER_MISSING", "SKILL.md requires YAML frontmatter", "SKILL.md");
                return null;
            }
            int contentStart = text.indexOf('\n') + 1;
            int end = text.indexOf("\n---", contentStart);
            if (end < 0 || end > 65_536) {
                error(issues, "SKILL_FRONTMATTER_INVALID", "frontmatter boundary is invalid", "SKILL.md");
                return null;
            }
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(8);
            Object parsed = new Yaml(new SafeConstructor(options)).load(text.substring(contentStart, end));
            if (!(parsed instanceof Map<?, ?> raw)) {
                error(issues, "SKILL_FRONTMATTER_INVALID", "frontmatter must be an object", "SKILL.md");
                return null;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            raw.forEach((key, value) -> map.put(String.valueOf(key), value));
            String name = string(map.get("name"));
            if (name == null) {
                error(issues, "SKILL_NAME_MISSING", "frontmatter name is required", "SKILL.md");
                name = "Unnamed Skill";
            }
            Map<String, Object> requires = map.get("requires") instanceof Map<?, ?> req
                    ? req.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()), Map.Entry::getValue,
                            (left, right) -> left, LinkedHashMap::new)) : Map.of();
            return new Frontmatter(name, string(map.get("description")), string(map.get("license")),
                    strings(map.get("tags")),
                    new SkillRequirement(strings(requires.get("tools")), strings(requires.get("env"))),
                    Boolean.TRUE.equals(map.get("always")));
        } catch (Exception error) {
            error(issues, "SKILL_FRONTMATTER_INVALID", "frontmatter could not be parsed", "SKILL.md");
            return null;
        }
    }

    private static void inspectEntry(Path root, Path value, BasicFileAttributes attrs,
                                     Set<String> canonicalPaths,
                                     List<SkillValidationReport.Issue> issues) {
        String relative = relative(root, value);
        if (attrs.isSymbolicLink() || Files.isSymbolicLink(value)) {
            error(issues, "SKILL_SYMLINK_REJECTED", "symbolic links are not allowed", relative);
        }
        if (!attrs.isDirectory() && !attrs.isRegularFile() && !attrs.isSymbolicLink()) {
            error(issues, "SKILL_SPECIAL_FILE_REJECTED", "special files are not allowed", relative);
        }
        if (value.isAbsolute() && !value.normalize().startsWith(root)) {
            error(issues, "SKILL_ABSOLUTE_PATH_REJECTED", "absolute package path escaped root", relative);
        }
        Path normalized = Path.of(relative.isBlank() ? "." : relative).normalize();
        if (normalized.startsWith("..")) error(issues, "SKILL_PATH_TRAVERSAL", "path traversal is not allowed", relative);
        if (!relative.isBlank()) {
            if (isDevicePath(relative)) {
                error(issues, "SKILL_DEVICE_PATH_REJECTED", "device paths are not allowed", relative);
            }
            if (!canonicalPaths.add(canonicalPath(relative))) {
                error(issues, "SKILL_PATH_DUPLICATE", "canonical package path is duplicated", relative);
            }
        }
    }

    static String canonicalPath(String relative) {
        return Normalizer.normalize(relative.replace('\\', '/'), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    static boolean isDevicePath(String relative) {
        for (String segment : relative.replace('\\', '/').split("/")) {
            String trimmed = segment.replaceFirst("[ .]+$", "");
            String base = trimmed.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (Set.of("CON", "PRN", "AUX", "NUL").contains(base)
                    || base.matches("COM[1-9]") || base.matches("LPT[1-9]")) return true;
        }
        return false;
    }

    private static boolean isDangerousBinary(Path file, String relative) {
        String name = relative.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && DANGEROUS_EXTENSIONS.contains(name.substring(dot + 1))) return true;
        try {
            byte[] prefix = new byte[4];
            int count;
            try (var input = Files.newInputStream(file)) {
                count = input.read(prefix);
            }
            if (count >= 2 && prefix[0] == 'M' && prefix[1] == 'Z') return true;
            return count >= 4 && prefix[0] == 0x7f && prefix[1] == 'E' && prefix[2] == 'L' && prefix[3] == 'F';
        } catch (IOException error) {
            return true;
        }
    }

    private static String relative(Path root, Path value) {
        try { return root.relativize(value.toAbsolutePath().normalize()).toString().replace('\\', '/'); }
        catch (IllegalArgumentException error) { return "[outside-root]"; }
    }
    private static String string(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
    private static List<String> strings(Object value) {
        if (value instanceof Iterable<?> iterable) {
            ArrayList<String> values = new ArrayList<>();
            for (Object item : iterable) { String text = string(item); if (text != null) values.add(text); }
            return List.copyOf(values);
        }
        String scalar = string(value);
        return scalar == null ? List.of() : List.of(scalar);
    }
    private static void error(List<SkillValidationReport.Issue> issues, String code, String message, String path) {
        issues.add(new SkillValidationReport.Issue(code, SkillValidationReport.Severity.ERROR, message, path));
    }
    private static void warning(List<SkillValidationReport.Issue> issues, String code, String message, String path) {
        issues.add(new SkillValidationReport.Issue(code, SkillValidationReport.Severity.WARNING, message, path));
    }

    public record Frontmatter(String name, String description, String license, List<String> tags,
                              SkillRequirement requires, boolean always) { }
    public record ValidationResult(Frontmatter frontmatter, SkillValidationReport report,
                                   int fileCount, long packageBytes) { }
}
