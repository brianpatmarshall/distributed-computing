package com.boeing.constcatalog.transformer;

import com.boeing.constcatalog.model.ConstantTarget;
import com.boeing.constcatalog.model.TransformationEntry;
import com.boeing.constcatalog.model.TransformationResult;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Removes unused {@code static final} constant declarations from Java source files.
 *
 * <p>Given a list of {@link ConstantTarget} entries (identified as unused via the Neo4j graph),
 * this class parses each source file with JavaParser and removes the matching field declarations.
 *
 * <p>Protected names (e.g., {@code serialVersionUID}, {@code LOG}, {@code LOGGER}) are never removed,
 * even if the graph reports them as unused.
 *
 * <p>Multi-variable declarations (e.g., {@code static final int A = 1, B = 2;}) are handled by
 * removing only the specific variable declarator, leaving the rest of the declaration intact.
 * If all variables in a declaration are targeted, the entire field is removed.
 *
 * <p>Fields are removed bottom-up (highest line number first) to avoid line-number drift.
 */
public class UnusedConstantRemover {

    private static final Logger log = LoggerFactory.getLogger(UnusedConstantRemover.class);

    /** Names that should never be removed, regardless of usage. */
    private static final Set<String> PROTECTED_NAMES = Set.of(
        "serialVersionUID", "VERSION", "LOG", "LOGGER", "logger", "_logger", "log", "_log"
    );

    /** Type names that indicate a logger — fields of these types are never removed. */
    private static final Set<String> LOGGER_TYPE_NAMES = Set.of(
        "Logger", "Log", "Slf4jLogger"
    );

    /** Tolerance when matching line numbers from Neo4j to AST positions. */
    private static final int LINE_TOLERANCE = 3;

    private final JavaParser javaParser;

    public UnusedConstantRemover() {
        ParserConfiguration config = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.javaParser = new JavaParser(config);
    }

    /**
     * Removes unused constant declarations from a single file, writing the result
     * to the backups directory.
     *
     * @param sourceFile the source file to read
     * @param targets    the unused constants to remove
     * @param backupsDir writable directory for backups and modified files
     * @return one entry per target describing what happened
     */
    public List<TransformationEntry> removeFromFile(Path sourceFile, List<ConstantTarget> targets, Path backupsDir) {
        String filePath = sourceFile.toString();

        if (targets.isEmpty()) {
            return List.of();
        }

        try {
            Path absSource = sourceFile.toAbsolutePath();
            Path relPath = absSource.getRoot().relativize(absSource);
            Path outputPath = backupsDir.resolve(relPath);
            Path backupOrigPath = Path.of(outputPath + ".orig");

            Files.createDirectories(outputPath.getParent());
            Files.copy(sourceFile, backupOrigPath, StandardCopyOption.REPLACE_EXISTING);

            ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceFile);

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                log.warn("Failed to parse file: {}", sourceFile);
                return targets.stream()
                    .map(t -> TransformationEntry.failed(t, filePath, "File could not be parsed"))
                    .toList();
            }

            CompilationUnit compilationUnit = parseResult.getResult().get();
            List<TransformationEntry> results = new ArrayList<>();
            boolean anyRemoved = false;

            // Build a lookup of target names for quick matching
            Map<String, ConstantTarget> targetsByName = targets.stream()
                .collect(Collectors.toMap(ConstantTarget::constantName, t -> t, (a, b) -> a));

            // Find all static final fields, collect removal candidates
            List<RemovalCandidate> candidates = new ArrayList<>();

            for (FieldDeclaration field : compilationUnit.findAll(FieldDeclaration.class)) {
                if (!isStaticFinal(field)) continue;
                if (!isPublic(field)) continue;

                for (VariableDeclarator var : field.getVariables()) {
                    String name = var.getNameAsString();
                    ConstantTarget target = targetsByName.get(name);
                    if (target == null) continue;

                    if (PROTECTED_NAMES.contains(name) || isLoggerType(field)) {
                        String reason = PROTECTED_NAMES.contains(name)
                            ? "Protected name '%s' — not removed".formatted(name)
                            : "Logger field '%s' (type %s) — not removed".formatted(name, field.getElementType().asString());
                        results.add(TransformationEntry.skipped(target, filePath, reason));
                        targetsByName.remove(name);
                        continue;
                    }

                    // Check line number tolerance
                    int astLine = var.getBegin().map(p -> p.line).orElse(-1);
                    if (target.lineNumber() > 0 && astLine > 0
                            && Math.abs(astLine - target.lineNumber()) > LINE_TOLERANCE) {
                        results.add(TransformationEntry.skipped(target, filePath,
                            "Line mismatch: expected ~%d, found %d".formatted(target.lineNumber(), astLine)));
                        targetsByName.remove(name);
                        continue;
                    }

                    candidates.add(new RemovalCandidate(field, var, target));
                    targetsByName.remove(name);
                }
            }

            // Report targets that were not found in the AST
            for (ConstantTarget remaining : targetsByName.values()) {
                results.add(TransformationEntry.skipped(remaining, filePath,
                    "Declaration of '%s' not found in file".formatted(remaining.constantName())));
            }

            // Sort candidates bottom-up by line number to avoid drift
            candidates.sort(Comparator.comparingInt(
                (RemovalCandidate c) -> c.var.getBegin().map(p -> p.line).orElse(0)).reversed());

            // Remove candidates and validate that no dangling references remain
            String backupFilePathStr = backupOrigPath.toString();
            Set<String> removedNames = new LinkedHashSet<>();

            for (RemovalCandidate candidate : candidates) {
                FieldDeclaration field = candidate.field;
                VariableDeclarator variableDeclarator = candidate.var;

                if (field.getVariables().size() == 1) {
                    field.remove();
                } else {
                    variableDeclarator.remove();
                }
                removedNames.add(candidate.target.constantName());
            }

            // Validate: check if any removed name is still referenced in the AST
            Set<String> danglingRefs = findDanglingReferences(compilationUnit, removedNames);

            for (RemovalCandidate candidate : candidates) {
                String name = candidate.target.constantName();
                if (danglingRefs.contains(name)) {
                    results.add(TransformationEntry.failed(candidate.target, filePath,
                        "Removal would break compilation — '%s' is still referenced in this file".formatted(name)));
                } else {
                    results.add(new TransformationEntry(
                        filePath, candidate.target.className(), name,
                        candidate.target.propertyKey(), candidate.target.originalValue(),
                        candidate.target.originalType(), candidate.target.lineNumber(),
                        TransformationEntry.Status.TRANSFORMED,
                        "Removed unused constant '%s'".formatted(name),
                        candidate.target.sourceKind(),
                        backupFilePathStr
                    ));
                    anyRemoved = true;
                }
            }

            if (anyRemoved && danglingRefs.isEmpty()) {
                // All removals are safe — write the modified file
                Files.writeString(outputPath, compilationUnit.toString());
                log.info("Wrote modified source (removed {} constants): {}", candidates.size(), outputPath);
            } else if (anyRemoved) {
                // Some removals are safe but others have dangling refs.
                // Re-parse the original and only remove the safe ones.
                rewriteWithSafeRemovalsOnly(sourceFile, candidates, danglingRefs, outputPath, backupsDir);
            }

            return List.copyOf(results);

        } catch (IOException e) {
            log.error("Error reading file: {}", sourceFile, e);
            return targets.stream()
                .map(t -> TransformationEntry.failed(t, filePath, "IO error: " + e.getMessage()))
                .toList();
        }
    }

    /**
     * Preview which constants would be removed without modifying files.
     */
    public List<TransformationEntry> dryRun(Path sourceFile, List<ConstantTarget> targets) {
        String filePath = sourceFile.toString();

        if (targets.isEmpty()) {
            return List.of();
        }

        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceFile);

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return targets.stream()
                    .map(t -> TransformationEntry.failed(t, filePath, "File could not be parsed"))
                    .toList();
            }

            CompilationUnit cu = parseResult.getResult().get();
            List<TransformationEntry> results = new ArrayList<>();
            Set<String> wouldRemoveNames = new LinkedHashSet<>();
            List<ConstantTarget> wouldRemoveTargets = new ArrayList<>();

            Map<String, ConstantTarget> targetsByName = targets.stream()
                .collect(Collectors.toMap(ConstantTarget::constantName, t -> t, (a, b) -> a));

            for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
                if (!isStaticFinal(field)) continue;
                if (!isPublic(field)) continue;

                for (VariableDeclarator var : field.getVariables()) {
                    String name = var.getNameAsString();
                    ConstantTarget target = targetsByName.get(name);
                    if (target == null) continue;

                    if (PROTECTED_NAMES.contains(name) || isLoggerType(field)) {
                        String reason = PROTECTED_NAMES.contains(name)
                            ? "Protected name '%s' — not removed".formatted(name)
                            : "Logger field '%s' (type %s) — not removed".formatted(name, field.getElementType().asString());
                        results.add(TransformationEntry.skipped(target, filePath, reason));
                    } else {
                        int astLine = var.getBegin().map(p -> p.line).orElse(-1);
                        if (target.lineNumber() > 0 && astLine > 0
                                && Math.abs(astLine - target.lineNumber()) > LINE_TOLERANCE) {
                            results.add(TransformationEntry.skipped(target, filePath,
                                "Line mismatch: expected ~%d, found %d".formatted(target.lineNumber(), astLine)));
                        } else {
                            wouldRemoveNames.add(name);
                            wouldRemoveTargets.add(target);
                        }
                    }
                    targetsByName.remove(name);
                }
            }

            // Check for dangling references on a clone of the AST
            if (!wouldRemoveNames.isEmpty()) {
                CompilationUnit clone = cu.clone();
                for (FieldDeclaration field : clone.findAll(FieldDeclaration.class)) {
                    if (!isStaticFinal(field) || !isPublic(field)) continue;
                    for (VariableDeclarator v : new ArrayList<>(field.getVariables())) {
                        if (wouldRemoveNames.contains(v.getNameAsString())) {
                            if (field.getVariables().size() == 1) field.remove();
                            else v.remove();
                        }
                    }
                }
                Set<String> danglingRefs = findDanglingReferences(clone, wouldRemoveNames);
                for (ConstantTarget target : wouldRemoveTargets) {
                    String name = target.constantName();
                    if (danglingRefs.contains(name)) {
                        results.add(TransformationEntry.failed(target, filePath,
                            "Removal would break compilation — '%s' is still referenced in this file".formatted(name)));
                    } else {
                        results.add(new TransformationEntry(
                            filePath, target.className(), name,
                            target.propertyKey(), target.originalValue(), target.originalType(),
                            target.lineNumber(), TransformationEntry.Status.TRANSFORMED,
                            "Would remove unused constant '%s'".formatted(name),
                            target.sourceKind()
                        ));
                    }
                }
            }

            for (ConstantTarget remaining : targetsByName.values()) {
                results.add(TransformationEntry.skipped(remaining, filePath,
                    "Declaration of '%s' not found in file".formatted(remaining.constantName())));
            }

            return List.copyOf(results);

        } catch (IOException e) {
            return targets.stream()
                .map(t -> TransformationEntry.failed(t, filePath, "IO error: " + e.getMessage()))
                .toList();
        }
    }

    /**
     * Removes unused constants from multiple files in a project.
     */
    public TransformationResult removeFromProject(
            String projectPath,
            Map<Path, List<ConstantTarget>> targetsByFile) {

        Path backupsDir = Path.of("backups").toAbsolutePath();
        try {
            Files.createDirectories(backupsDir);
        } catch (IOException e) {
            log.error("Failed to create backups directory: {}", backupsDir, e);
            throw new RuntimeException("Cannot create backups directory: " + backupsDir, e);
        }

        List<TransformationEntry> allEntries = targetsByFile.entrySet().stream()
            .flatMap(entry -> removeFromFile(entry.getKey(), entry.getValue(), backupsDir).stream())
            .toList();

        return new TransformationResult(projectPath, LocalDateTime.now(), allEntries);
    }

    /**
     * Removes unused constants from multiple files in-place. The source files are
     * modified directly. Intended for use when git branching provides the safety net.
     */
    public TransformationResult removeFromProjectInPlace(
            String projectPath,
            Map<Path, List<ConstantTarget>> targetsByFile) {

        List<TransformationEntry> allEntries = targetsByFile.entrySet().stream()
            .flatMap(entry -> removeFromFileInPlace(entry.getKey(), entry.getValue()).stream())
            .toList();

        return new TransformationResult(projectPath, LocalDateTime.now(), allEntries);
    }

    /**
     * Removes unused constants from a single file in-place — writes directly to the source file.
     * No backup is created; git branching is assumed to provide rollback capability.
     */
    public List<TransformationEntry> removeFromFileInPlace(Path sourceFile, List<ConstantTarget> targets) {
        String filePath = sourceFile.toString();

        if (targets.isEmpty()) {
            return List.of();
        }

        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceFile);

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                log.warn("Failed to parse file: {}", sourceFile);
                return targets.stream()
                    .map(t -> TransformationEntry.failed(t, filePath, "File could not be parsed"))
                    .toList();
            }

            CompilationUnit compilationUnit = parseResult.getResult().get();
            List<TransformationEntry> results = new ArrayList<>();
            boolean anyRemoved = false;

            Map<String, ConstantTarget> targetsByName = targets.stream()
                .collect(Collectors.toMap(ConstantTarget::constantName, t -> t, (a, b) -> a));

            List<RemovalCandidate> candidates = new ArrayList<>();

            for (FieldDeclaration field : compilationUnit.findAll(FieldDeclaration.class)) {
                if (!isStaticFinal(field)) continue;
                if (!isPublic(field)) continue;

                for (VariableDeclarator var : field.getVariables()) {
                    String name = var.getNameAsString();
                    ConstantTarget target = targetsByName.get(name);
                    if (target == null) continue;

                    if (PROTECTED_NAMES.contains(name) || isLoggerType(field)) {
                        String reason = PROTECTED_NAMES.contains(name)
                            ? "Protected name '%s' — not removed".formatted(name)
                            : "Logger field '%s' (type %s) — not removed".formatted(name, field.getElementType().asString());
                        results.add(TransformationEntry.skipped(target, filePath, reason));
                        targetsByName.remove(name);
                        continue;
                    }

                    int astLine = var.getBegin().map(p -> p.line).orElse(-1);
                    if (target.lineNumber() > 0 && astLine > 0
                            && Math.abs(astLine - target.lineNumber()) > LINE_TOLERANCE) {
                        results.add(TransformationEntry.skipped(target, filePath,
                            "Line mismatch: expected ~%d, found %d".formatted(target.lineNumber(), astLine)));
                        targetsByName.remove(name);
                        continue;
                    }

                    candidates.add(new RemovalCandidate(field, var, target));
                    targetsByName.remove(name);
                }
            }

            for (ConstantTarget remaining : targetsByName.values()) {
                results.add(TransformationEntry.skipped(remaining, filePath,
                    "Declaration of '%s' not found in file".formatted(remaining.constantName())));
            }

            candidates.sort(Comparator.comparingInt(
                (RemovalCandidate c) -> c.var.getBegin().map(p -> p.line).orElse(0)).reversed());

            Set<String> removedNames = new LinkedHashSet<>();
            for (RemovalCandidate candidate : candidates) {
                if (candidate.field.getVariables().size() == 1) {
                    candidate.field.remove();
                } else {
                    candidate.var.remove();
                }
                removedNames.add(candidate.target.constantName());
            }

            Set<String> danglingRefs = findDanglingReferences(compilationUnit, removedNames);

            for (RemovalCandidate candidate : candidates) {
                String name = candidate.target.constantName();
                if (danglingRefs.contains(name)) {
                    results.add(TransformationEntry.failed(candidate.target, filePath,
                        "Removal would break compilation — '%s' is still referenced in this file".formatted(name)));
                } else {
                    results.add(new TransformationEntry(
                        filePath, candidate.target.className(), name,
                        candidate.target.propertyKey(), candidate.target.originalValue(),
                        candidate.target.originalType(), candidate.target.lineNumber(),
                        TransformationEntry.Status.TRANSFORMED,
                        "Removed unused constant '%s'".formatted(name),
                        candidate.target.sourceKind()
                    ));
                    anyRemoved = true;
                }
            }

            if (anyRemoved && danglingRefs.isEmpty()) {
                Files.writeString(sourceFile, compilationUnit.toString());
                log.info("Wrote modified source in-place (removed {} constants): {}", candidates.size(), sourceFile);
            } else if (anyRemoved) {
                // Re-parse and apply only safe removals directly to source
                rewriteWithSafeRemovalsOnlyInPlace(sourceFile, candidates, danglingRefs);
            }

            return List.copyOf(results);

        } catch (IOException e) {
            log.error("Error reading file: {}", sourceFile, e);
            return targets.stream()
                .map(t -> TransformationEntry.failed(t, filePath, "IO error: " + e.getMessage()))
                .toList();
        }
    }

    /**
     * Re-parses the source file and removes only the safe candidates in-place.
     */
    private void rewriteWithSafeRemovalsOnlyInPlace(
            Path sourceFile,
            List<RemovalCandidate> allCandidates,
            Set<String> danglingRefs) {
        try {
            ParseResult<CompilationUnit> freshParse = javaParser.parse(sourceFile);
            if (!freshParse.isSuccessful() || freshParse.getResult().isEmpty()) return;

            CompilationUnit freshCu = freshParse.getResult().get();
            Set<String> safeNames = allCandidates.stream()
                .map(c -> c.target.constantName())
                .filter(name -> !danglingRefs.contains(name))
                .collect(Collectors.toSet());

            if (safeNames.isEmpty()) return;

            boolean removed = false;
            for (FieldDeclaration field : freshCu.findAll(FieldDeclaration.class)) {
                if (!isStaticFinal(field) || !isPublic(field)) continue;
                for (VariableDeclarator var : new ArrayList<>(field.getVariables())) {
                    if (safeNames.contains(var.getNameAsString())) {
                        if (field.getVariables().size() == 1) {
                            field.remove();
                        } else {
                            var.remove();
                        }
                        removed = true;
                    }
                }
            }

            if (removed) {
                Files.writeString(sourceFile, freshCu.toString());
                log.info("Wrote modified source in-place (safe removals only, skipped {}): {}",
                    danglingRefs.size(), sourceFile);
            }
        } catch (IOException e) {
            log.error("Failed to rewrite with safe removals: {}", sourceFile, e);
        }
    }

    private boolean isStaticFinal(FieldDeclaration field) {
        return field.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC)
            && field.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
    }

    /**
     * Scans the (already-modified) AST for references to names that were removed.
     * A reference is a {@link NameExpr} or the name part of a {@link FieldAccessExpr}
     * that matches a removed constant name. These would cause compilation failures.
     */
    private Set<String> findDanglingReferences(CompilationUnit cu, Set<String> removedNames) {
        Set<String> dangling = new LinkedHashSet<>();

        // Check bare name references: FIND_SINGER_BY_ID
        cu.findAll(NameExpr.class).forEach(nameExpr -> {
            if (removedNames.contains(nameExpr.getNameAsString())) {
                dangling.add(nameExpr.getNameAsString());
            }
        });

        // Check qualified references: Singer.FIND_SINGER_BY_ID
        cu.findAll(FieldAccessExpr.class).forEach(fieldAccess -> {
            if (removedNames.contains(fieldAccess.getNameAsString())) {
                dangling.add(fieldAccess.getNameAsString());
            }
        });

        return dangling;
    }

    /**
     * Re-parses the original file and removes only the candidates that don't have
     * dangling references. Used when some removals are safe but others aren't.
     */
    private void rewriteWithSafeRemovalsOnly(
            Path sourceFile,
            List<RemovalCandidate> allCandidates,
            Set<String> danglingRefs,
            Path outputPath,
            Path backupsDir) {
        try {
            ParseResult<CompilationUnit> freshParse = javaParser.parse(sourceFile);
            if (!freshParse.isSuccessful() || freshParse.getResult().isEmpty()) return;

            CompilationUnit freshCu = freshParse.getResult().get();
            Set<String> safeNames = allCandidates.stream()
                .map(c -> c.target.constantName())
                .filter(name -> !danglingRefs.contains(name))
                .collect(Collectors.toSet());

            if (safeNames.isEmpty()) return;

            boolean removed = false;
            for (FieldDeclaration field : freshCu.findAll(FieldDeclaration.class)) {
                if (!isStaticFinal(field) || !isPublic(field)) continue;
                for (VariableDeclarator var : new ArrayList<>(field.getVariables())) {
                    if (safeNames.contains(var.getNameAsString())) {
                        if (field.getVariables().size() == 1) {
                            field.remove();
                        } else {
                            var.remove();
                        }
                        removed = true;
                    }
                }
            }

            if (removed) {
                Files.writeString(outputPath, freshCu.toString());
                log.info("Wrote modified source (safe removals only, skipped {}): {}",
                    danglingRefs.size(), outputPath);
            }
        } catch (IOException e) {
            log.error("Failed to rewrite with safe removals: {}", sourceFile, e);
        }
    }

    /**
     * Checks whether the field has public access. Non-public static final fields
     * are internal to their class and should not be removed or transformed.
     */
    private boolean isPublic(FieldDeclaration field) {
        return field.getModifiers().stream()
            .anyMatch(m -> m.getKeyword() == Modifier.Keyword.PUBLIC);
    }

    /**
     * Checks whether the field's declared type is a known logger type.
     * This catches logger fields with non-standard names (e.g., _logger, myLog).
     */
    private boolean isLoggerType(FieldDeclaration field) {
        String typeName = field.getElementType().asString();
        return LOGGER_TYPE_NAMES.contains(typeName);
    }

    private record RemovalCandidate(FieldDeclaration field, VariableDeclarator var, ConstantTarget target) {}
}
