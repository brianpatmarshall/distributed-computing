package com.boeing.constcatalog.transformer;

import com.boeing.constcatalog.model.ConstantTarget;
import com.boeing.constcatalog.model.TransformationEntry;
import com.boeing.constcatalog.model.TransformationResult;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Injects a static accessor field for {@code SiteConfigurationService} into Java classes.
 *
 * <p>Sibling to {@link ConfigLookupTransformer} and {@link UnusedConstantRemover}. Where
 * {@code ConfigLookupTransformer} rewrites references to hardcoded constants into runtime
 * {@code config.lookup(...)} calls, this class adds the {@code config} field those calls
 * resolve against. It is the prep pass that lets a non-Spring host project use the
 * generated lookup calls without DI.
 *
 * <p>Each touched class gets:
 * <pre>{@code
 * public static final SiteConfigurationService config = SiteConfigurationServiceProvider.get();
 * }</pre>
 *
 * <p>The field is added to every top-level class in the file (interfaces, inner classes,
 * and local classes are skipped) that does not already have a {@code config} field.
 * The injector is invoked by {@code CodeTransformationService} on the set of files that
 * the rewrite has actually mutated, so over-injection into unrelated files is bounded
 * upstream — the caller controls scope, the injector handles every eligible class in
 * each file it sees. The supplied {@link ConstantTarget} list is used only for
 * representative metadata on emitted {@link TransformationEntry} records, not for the
 * predicate decision. Imports for the service and the provider are added once per file
 * when at least one class is mutated.
 *
 * <p>Formatting is preserved via {@link LexicalPreservingPrinter} so the resulting diff
 * shows only the inserted field plus the two imports.
 */
public class ConfigFieldInjector {

    private static final Logger log = LoggerFactory.getLogger(ConfigFieldInjector.class);

    /** Fully-qualified name of the service type whose accessor we inject. */
    private static final String IMPORT_SERVICE  = "com.boeing.constcatalog.backend.service.SiteConfigurationService";

    /** Fully-qualified name of the static provider that returns the singleton. */
    private static final String IMPORT_PROVIDER = "com.boeing.constcatalog.backend.annotation.SiteConfigurationServiceProvider";

    /** Simple name of the field type — used both in the field decl and the {@code hasField} check. */
    private static final String FIELD_TYPE = "SiteConfigurationService";

    /** Name of the injected field. Must match {@code ConfigLookupTransformer.CONFIG_FIELD_NAME}. */
    private static final String FIELD_NAME = "config";

    /** Initializer expression emitted as text and parsed by JavaParser. */
    private static final String FIELD_INITIALIZER = "SiteConfigurationServiceProvider.get()";

    private final JavaParser javaParser;

    public ConfigFieldInjector() {
        ParserConfiguration config = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.javaParser = new JavaParser(config);
    }

    /**
     * Reports what would be injected without modifying any files. One entry per
     * eligible class in {@code sourceFile}; classes that already have the field
     * are emitted with status {@code SKIPPED}.
     */
    public List<TransformationEntry> dryRun(Path sourceFile, List<ConstantTarget> targets) {
        String filePath = sourceFile.toString();
        if (targets.isEmpty()) return List.of();

        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceFile);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return targets.stream()
                    .map(t -> TransformationEntry.failed(t, filePath, "File could not be parsed"))
                    .toList();
            }
            CompilationUnit cu = parseResult.getResult().get();
            return planInjection(cu, sourceFile, targets, /*apply*/ false);
        } catch (IOException e) {
            return targets.stream()
                .map(t -> TransformationEntry.failed(t, filePath, "IO error: " + e.getMessage()))
                .toList();
        }
    }

    /**
     * Injects the field across every file in {@code targetsByFile}, copying the
     * original to a sibling {@code backups/} tree first.
     */
    public TransformationResult injectIntoProject(
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
            .flatMap(entry -> injectIntoFile(entry.getKey(), entry.getValue(), backupsDir).stream())
            .toList();

        return new TransformationResult(projectPath, LocalDateTime.now(), allEntries);
    }

    /**
     * Injects the field across every file in-place. Intended for callers that
     * provide their own safety net (typically a git branch).
     */
    public TransformationResult injectIntoProjectInPlace(
            String projectPath,
            Map<Path, List<ConstantTarget>> targetsByFile) {

        List<TransformationEntry> allEntries = targetsByFile.entrySet().stream()
            .flatMap(entry -> injectIntoFileInPlace(entry.getKey(), entry.getValue()).stream())
            .toList();

        return new TransformationResult(projectPath, LocalDateTime.now(), allEntries);
    }

    /**
     * Injects directly into the source file. No backup is written.
     */
    public List<TransformationEntry> injectIntoFileInPlace(Path sourceFile, List<ConstantTarget> targets) {
        String filePath = sourceFile.toString();
        if (targets.isEmpty()) return List.of();

        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceFile);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                log.warn("Failed to parse file: {}", sourceFile);
                return targets.stream()
                    .map(t -> TransformationEntry.failed(t, filePath, "File could not be parsed"))
                    .toList();
            }
            CompilationUnit cu = parseResult.getResult().get();
            LexicalPreservingPrinter.setup(cu);

            List<TransformationEntry> entries = planInjection(cu, sourceFile, targets, /*apply*/ true);

            boolean mutated = entries.stream()
                .anyMatch(e -> e.status() == TransformationEntry.Status.TRANSFORMED);
            if (mutated) {
                Files.writeString(sourceFile, LexicalPreservingPrinter.print(cu));
            }
            return entries;
        } catch (IOException e) {
            log.error("Error reading file: {}", sourceFile, e);
            return targets.stream()
                .map(t -> TransformationEntry.failed(t, filePath, "IO error: " + e.getMessage()))
                .toList();
        }
    }

    /**
     * Backup-based variant: copies the original to {@code backupsDir/<source-path>.orig}
     * before writing.
     */
    private List<TransformationEntry> injectIntoFile(Path sourceFile, List<ConstantTarget> targets, Path backupsDir) {
        String filePath = sourceFile.toString();
        if (targets.isEmpty()) return List.of();

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
            CompilationUnit cu = parseResult.getResult().get();
            LexicalPreservingPrinter.setup(cu);

            List<TransformationEntry> entries = planInjection(cu, sourceFile, targets, /*apply*/ true);

            boolean mutated = entries.stream()
                .anyMatch(e -> e.status() == TransformationEntry.Status.TRANSFORMED);
            if (mutated) {
                Files.writeString(sourceFile, LexicalPreservingPrinter.print(cu));
            }
            return entries;
        } catch (IOException e) {
            log.error("Error processing file: {}", sourceFile, e);
            return targets.stream()
                .map(t -> TransformationEntry.failed(t, filePath, "IO error: " + e.getMessage()))
                .toList();
        }
    }

    /**
     * Core decision routine — emits one {@link TransformationEntry} per eligible
     * class in {@code cu}. When {@code apply} is true, mutates {@code cu}.
     *
     * <p>Every top-level non-inner, non-local class is a candidate. Classes that
     * already have a {@code config} field are emitted with status {@code SKIPPED}
     * (matches both the static-form this injector emits and the
     * {@code @Autowired private} form added by
     * {@link ConfigLookupTransformer#addConfigFieldIfMissing}). The
     * {@code targets} list is consulted only for entry metadata — the first
     * target becomes the file's representative; if the list is empty a synthetic
     * representative is used.
     */
    private List<TransformationEntry> planInjection(CompilationUnit cu, Path sourceFile,
                                                    List<ConstantTarget> targets, boolean apply) {
        String filePath = sourceFile.toString();

        ConstantTarget representative = targets.isEmpty() ? null : targets.get(0);
        ConstantTarget.SourceKind kind = representative != null
            ? representative.sourceKind() : ConstantTarget.SourceKind.CONSTANT;

        List<TransformationEntry> results = new ArrayList<>();
        int mutatedCount = 0;

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!shouldAddField(cls)) continue;

            int line = cls.getBegin().map(p -> p.line).orElse(-1);

            if (hasField(cls, FIELD_NAME)) {
                results.add(new TransformationEntry(
                    filePath,
                    cls.getNameAsString(),
                    FIELD_NAME,
                    /*propertyKey*/ "",
                    /*originalValue*/ "",
                    FIELD_TYPE,
                    line,
                    TransformationEntry.Status.SKIPPED,
                    "Class '%s' already has field '%s' — not injected"
                        .formatted(cls.getNameAsString(), FIELD_NAME),
                    kind
                ));
                continue;
            }

            if (apply) addStaticField(cls);
            mutatedCount++;

            results.add(new TransformationEntry(
                filePath,
                cls.getNameAsString(),
                FIELD_NAME,
                /*propertyKey*/ "",
                /*originalValue*/ "",
                FIELD_TYPE,
                line,
                TransformationEntry.Status.TRANSFORMED,
                apply
                    ? "Injected static field '%s' into class '%s'".formatted(FIELD_NAME, cls.getNameAsString())
                    : "Would inject static field '%s' into class '%s'".formatted(FIELD_NAME, cls.getNameAsString()),
                kind
            ));
        }

        // Imports go in once per file, after we know at least one class was mutated.
        if (apply && mutatedCount > 0) {
            ensureImport(cu, IMPORT_SERVICE);
            ensureImport(cu, IMPORT_PROVIDER);
        }

        return List.copyOf(results);
    }

    /* ---------- predicates and edits ---------- */

    private boolean shouldAddField(ClassOrInterfaceDeclaration cls) {
        return !cls.isInterface()
            && !cls.isInnerClass()
            && !cls.isLocalClassDeclaration();
    }

    private boolean hasField(ClassOrInterfaceDeclaration cls, String name) {
        return cls.getFields().stream()
            .flatMap(f -> f.getVariables().stream())
            .anyMatch(v -> v.getNameAsString().equals(name));
    }

    private void addStaticField(ClassOrInterfaceDeclaration cls) {
        FieldDeclaration field = cls.addField(
            FIELD_TYPE, FIELD_NAME,
            Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);
        field.getVariable(0).setInitializer(FIELD_INITIALIZER);
    }

    private void ensureImport(CompilationUnit cu, String fqcn) {
        String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk() || imp.isStatic()) continue;
            if (imp.getNameAsString().equals(fqcn)) return;
            if (imp.getName().getIdentifier().equals(simpleName)
                    && !imp.getNameAsString().equals(fqcn)) {
                // Name conflict: another class with the same simple name is already imported.
                // Bail loudly rather than silently producing a file that uses the wrong type.
                throw new IllegalStateException(
                    "Import conflict on '%s' in %s — already imports %s"
                        .formatted(simpleName, cu.getStorage().map(s -> s.getPath().toString()).orElse("?"),
                                   imp.getNameAsString()));
            }
        }
        cu.addImport(fqcn);
    }
}
