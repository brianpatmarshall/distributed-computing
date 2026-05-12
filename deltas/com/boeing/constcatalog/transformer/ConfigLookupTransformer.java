    package com.boeing.constcatalog.transformer;

    import com.boeing.constcatalog.model.ConstantTarget;
    import com.boeing.constcatalog.model.TransformationEntry;
    import com.boeing.constcatalog.model.TransformationResult;
    import com.github.javaparser.JavaParser;
    import com.github.javaparser.ParserConfiguration;
    import com.github.javaparser.ParseResult;
    import com.github.javaparser.ast.CompilationUnit;
    import com.github.javaparser.ast.Modifier;
    import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
    import com.github.javaparser.ast.body.FieldDeclaration;
    import com.github.javaparser.ast.body.VariableDeclarator;
    import com.github.javaparser.ast.expr.FieldAccessExpr;
    import com.github.javaparser.ast.expr.MethodCallExpr;
    import com.github.javaparser.ast.expr.NameExpr;
    import com.github.javaparser.ast.expr.StringLiteralExpr;
    import com.github.javaparser.ast.expr.IntegerLiteralExpr;
    import com.github.javaparser.ast.expr.LongLiteralExpr;
    import com.github.javaparser.ast.expr.DoubleLiteralExpr;
    import com.github.javaparser.ast.expr.BooleanLiteralExpr;
    import com.github.javaparser.ast.expr.Expression;
    import com.github.javaparser.ast.type.ClassOrInterfaceType;
    import com.github.javaparser.ast.visitor.ModifierVisitor;
    import com.github.javaparser.ast.visitor.Visitable;
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
     * Transforms Java source files by replacing constant USAGES and parameter getter calls
     * with {@code config.lookupType()} calls.
     *
     * <p>This transformer handles two kinds of targets:
     * <ul>
     *   <li><b>Constants</b> ({@code NameExpr}/{@code FieldAccessExpr}): e.g., {@code SSH_PORT}
     *       replaced with {@code config.lookupInt("ssh.port", 22)}</li>
     *   <li><b>Parameters</b> ({@code MethodCallExpr}): e.g., {@code getProperty("db.host")}
     *       replaced with {@code config.lookup("db.host")}</li>
     * </ul>
     */
    public class ConfigLookupTransformer {

        private static final Logger log = LoggerFactory.getLogger(ConfigLookupTransformer.class);

        private static final String CONFIG_SERVICE_CLASS = "SiteConfigurationService";
        private static final String CONFIG_FIELD_NAME = "config";

        /** Property getter method names that should be replaced for PARAMETER targets. */
        private static final Set<String> PROPERTY_GETTER_METHODS = Set.of(
            "getProperty", "get", "getString", "getInt", "getInteger", "getLong",
            "getBoolean", "getDouble", "getFloat", "getValue", "getConfig",
            "getenv", "getOrDefault", "getPropertyValue"
        );

        /** Maps getter method names to config.lookupXxx method names. */
        private static final Map<String, String> METHOD_TO_LOOKUP = Map.ofEntries(
            Map.entry("getInt", "lookupInt"),
            Map.entry("getInteger", "lookupInt"),
            Map.entry("getLong", "lookupLong"),
            Map.entry("getBoolean", "lookupBoolean"),
            Map.entry("getDouble", "lookupDouble"),
            Map.entry("getFloat", "lookupDouble")
        );

        private final JavaParser javaParser;
        private final TransformSupervisor supervisor = new TransformSupervisor();

        /** Supervisor check results from the most recent transformation, keyed by file path. */
        private final Map<String, TransformSupervisor.FileCheckResult> lastSupervisorResults = new LinkedHashMap<>();

        public ConfigLookupTransformer() {
            ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
            this.javaParser = new JavaParser(config);
        }

        /**
         * Returns supervisor check results from the most recent transformation run.
         * Keyed by file path.
         */
        public Map<String, TransformSupervisor.FileCheckResult> getLastSupervisorResults() {
            return Collections.unmodifiableMap(lastSupervisorResults);
        }

        /**
         * Transforms usages in a single file, replacing constant references and
         * parameter getter calls with config.lookupType() calls.
         *
         * @param sourceFile the source file to read from (may be read-only)
         * @param targets    the constants/parameters to transform
         * @param backupsDir writable directory where backups and transformed files are written
         */
        public List<TransformationEntry> transformFile(Path sourceFile, List<ConstantTarget> targets, Path backupsDir) {
            String filePath = sourceFile.toString();

            if (targets.isEmpty()) {
                return List.of();
            }

            try {
                // Compute output paths in writable backups directory, preserving source structure
                Path absSource = sourceFile.toAbsolutePath();
                Path relPath = absSource.getRoot().relativize(absSource);
                Path outputPath = backupsDir.resolve(relPath);
                Path backupOrigPath = Path.of(outputPath + ".orig");

                Files.createDirectories(outputPath.getParent());

                // Preserve original file as backup
                Files.copy(sourceFile, backupOrigPath, StandardCopyOption.REPLACE_EXISTING);

                // Read original source lines for before/after capture
                List<String> originalSourceLines = Files.readAllLines(sourceFile);

                ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceFile);

                if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                    log.warn("Failed to parse file: {}", sourceFile);
                    return targets.stream()
                        .map(t -> TransformationEntry.failed(t, filePath, "File could not be parsed"))
                        .toList();
                }

                CompilationUnit cu = parseResult.getResult().get();

                MutationOutcome outcome = applyMutations(cu, targets);

                if (outcome.anyTransformed()) {
                    writeModifiedSource(outputPath, cu);
                }

                List<String> transformedSourceLines = outcome.anyTransformed()
                    ? Files.readAllLines(outputPath) : originalSourceLines;

                List<TransformationEntry> results = buildEntries(
                    outcome, filePath, backupOrigPath.toString(),
                    originalSourceLines, transformedSourceLines);

                if (outcome.anyTransformed()) {
                    TransformSupervisor.FileCheckResult checkResult = supervisor.check(cu, filePath);
                    lastSupervisorResults.put(filePath, checkResult);
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
         * Preview transformation without modifying files.
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

                // Split targets by source kind
                Map<String, ConstantTarget> constantTargets = targets.stream()
                    .filter(t -> t.sourceKind() == ConstantTarget.SourceKind.CONSTANT)
                    .collect(Collectors.toMap(ConstantTarget::constantName, t -> t, (a, b) -> a));

                Map<String, ConstantTarget> paramTargets = targets.stream()
                    .filter(t -> t.sourceKind() == ConstantTarget.SourceKind.PARAMETER)
                    .collect(Collectors.toMap(ConstantTarget::constantName, t -> t, (a, b) -> a));

                // Scan for constant usages without modifying
                Set<String> foundUsages = new HashSet<>();
                cu.findAll(NameExpr.class).forEach(n -> {
                    if (constantTargets.containsKey(n.getNameAsString()) && !isDeclaration(n)) {
                        foundUsages.add(n.getNameAsString());
                    }
                });
                cu.findAll(FieldAccessExpr.class).forEach(n -> {
                    if (constantTargets.containsKey(n.getNameAsString())) {
                        foundUsages.add(n.getNameAsString());
                    }
                });

                // Scan for parameter getter usages without modifying
                Set<String> foundParamUsages = new HashSet<>();
                cu.findAll(MethodCallExpr.class).forEach(mce -> {
                    if (PROPERTY_GETTER_METHODS.contains(mce.getNameAsString())) {
                        for (Expression argExpr : mce.getArguments()) {
                            if (argExpr instanceof StringLiteralExpr strLit) {
                                String paramName = strLit.getValue();
                                if (paramTargets.containsKey(paramName)) {
                                    foundParamUsages.add(paramName);
                                }
                            }
                        }
                    }
                });

                List<TransformationEntry> results = new ArrayList<>();

                // Results for constants
                for (ConstantTarget target : constantTargets.values()) {
                    if (foundUsages.contains(target.constantName())) {
                        results.add(TransformationEntry.transformed(target, filePath));
                    } else {
                        results.add(TransformationEntry.skipped(target, filePath,
                            "No usages of constant '%s' found in file".formatted(target.constantName())));
                    }
                }

                // Results for parameters
                for (ConstantTarget target : paramTargets.values()) {
                    if (foundParamUsages.contains(target.constantName())) {
                        results.add(TransformationEntry.transformed(target, filePath));
                    } else {
                        results.add(TransformationEntry.skipped(target, filePath,
                            "No usages of parameter '%s' found in file".formatted(target.constantName())));
                    }
                }

                return List.copyOf(results);

            } catch (IOException e) {
                return targets.stream()
                    .map(t -> TransformationEntry.failed(t, filePath, "IO error: " + e.getMessage()))
                    .toList();
            }
        }

        /**
         * In-memory preview that performs the same AST mutation as
         * {@link #transformFile} but writes nothing to disk. Returns the
         * original source and the would-be-transformed source as strings,
         * plus the per-target {@link TransformationEntry} results.
         *
         * <p>Used by the {@code /compare} endpoint to render dry-run diffs:
         * dry runs leave no on-disk artifact, so the diff content has to be
         * synthesized at compare time.
         */
        public PreviewResult previewFile(Path sourceFile, List<ConstantTarget> targets) {
            String filePath = sourceFile.toString();

            try {
                String originalSource = Files.readString(sourceFile);
                if (targets.isEmpty()) {
                    return new PreviewResult(originalSource, originalSource, List.of());
                }

                ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceFile);
                if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                    return new PreviewResult(originalSource, originalSource,
                        targets.stream()
                            .map(t -> TransformationEntry.failed(t, filePath, "File could not be parsed"))
                            .toList());
                }

                CompilationUnit cu = parseResult.getResult().get();
                MutationOutcome outcome = applyMutations(cu, targets);

                String transformedSource = outcome.anyTransformed() ? cu.toString() : originalSource;
                List<String> originalLines = originalSource.lines().toList();
                List<String> transformedLines = transformedSource.lines().toList();

                return new PreviewResult(originalSource, transformedSource,
                    buildEntries(outcome, filePath, null, originalLines, transformedLines));

            } catch (IOException e) {
                return new PreviewResult("", "",
                    targets.stream()
                        .map(t -> TransformationEntry.failed(t, filePath, "IO error: " + e.getMessage()))
                        .toList());
            }
        }

        /**
         * Result of an in-memory preview: the original source, the
         * would-be-transformed source, and the per-target outcomes.
         */
        public record PreviewResult(
            String originalSource,
            String transformedSource,
            List<TransformationEntry> entries) {}

        /**
         * Builds the per-target {@link TransformationEntry} list from a
         * {@link MutationOutcome} and the original/transformed line lists.
         * Shared by {@link #transformFile} and {@link #previewFile}.
         */
        private List<TransformationEntry> buildEntries(
                MutationOutcome outcome,
                String filePath,
                String backupFilePath,
                List<String> originalLines,
                List<String> transformedLines) {

            List<TransformationEntry> results = new ArrayList<>();

            for (ConstantTarget target : outcome.constantTargets().values()) {
                if (outcome.replacedConstants().contains(target.constantName())) {
                    results.add(TransformationEntry.transformed(target, filePath, backupFilePath,
                        getSourceLine(originalLines, target.lineNumber()),
                        getSourceLine(transformedLines, target.lineNumber())));
                } else {
                    results.add(TransformationEntry.skipped(target, filePath,
                        "No usages of constant '%s' found in file".formatted(target.constantName())));
                }
            }
            for (ConstantTarget target : outcome.paramTargets().values()) {
                if (outcome.replacedParams().contains(target.constantName())) {
                    results.add(TransformationEntry.transformed(target, filePath, backupFilePath,
                        getSourceLine(originalLines, target.lineNumber()),
                        getSourceLine(transformedLines, target.lineNumber())));
                } else {
                    results.add(TransformationEntry.skipped(target, filePath,
                        "No usages of parameter '%s' found in file".formatted(target.constantName())));
                }
            }
            return results;
        }

        /**
         * Transforms multiple files in a project.
         * Backups and transformed files are written to a {@code backups/} directory
         * under the current working directory, preserving the source directory structure.
         */
        public TransformationResult transformProject(
                String projectPath,
                Map<Path, List<ConstantTarget>> targetsByFile) {

            lastSupervisorResults.clear();
            Path backupsDir = Path.of("backups").toAbsolutePath();
            try {
                Files.createDirectories(backupsDir);
            } catch (IOException e) {
                log.error("Failed to create backups directory: {}", backupsDir, e);
                throw new RuntimeException("Cannot create backups directory: " + backupsDir, e);
            }

            List<TransformationEntry> allEntries = targetsByFile.entrySet().stream()
                .flatMap(entry -> transformFile(entry.getKey(), entry.getValue(), backupsDir).stream())
                .toList();

            return new TransformationResult(projectPath, LocalDateTime.now(), allEntries);
        }

        /**
         * Transforms multiple files in-place. The source files are modified directly.
         * Intended for use when git branching provides the safety net (original code
         * is preserved on the previous branch).
         */
        public TransformationResult transformProjectInPlace(
                String projectPath,
                Map<Path, List<ConstantTarget>> targetsByFile) {

            lastSupervisorResults.clear();
            List<TransformationEntry> allEntries = targetsByFile.entrySet().stream()
                .flatMap(entry -> transformFileInPlace(entry.getKey(), entry.getValue()).stream())
                .toList();

            return new TransformationResult(projectPath, LocalDateTime.now(), allEntries);
        }

        /**
         * Transforms a single file in-place — writes the modified AST back to the source file.
         * No backup is created; git branching is assumed to provide rollback capability.
         */
        public List<TransformationEntry> transformFileInPlace(Path sourceFile, List<ConstantTarget> targets) {
            String filePath = sourceFile.toString();

            if (targets.isEmpty()) {
                return List.of();
            }

            try {
                // Read original source lines for before/after capture
                List<String> originalSourceLines = Files.readAllLines(sourceFile);

                ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceFile);

                if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                    log.warn("Failed to parse file: {}", sourceFile);
                    return targets.stream()
                        .map(t -> TransformationEntry.failed(t, filePath, "File could not be parsed"))
                        .toList();
                }

                CompilationUnit cu = parseResult.getResult().get();
                List<TransformationEntry> results = new ArrayList<>();

                // Split targets by source kind
                Map<String, ConstantTarget> constantTargets = targets.stream()
                    .filter(t -> t.sourceKind() == ConstantTarget.SourceKind.CONSTANT)
                    .collect(Collectors.toMap(ConstantTarget::constantName, t -> t, (a, b) -> a));

                Map<String, ConstantTarget> paramTargets = targets.stream()
                    .filter(t -> t.sourceKind() == ConstantTarget.SourceKind.PARAMETER)
                    .collect(Collectors.toMap(ConstantTarget::constantName, t -> t, (a, b) -> a));

                Set<String> replacedConstants = new HashSet<>();
                Set<String> replacedParams = new HashSet<>();

                // Visit AST to replace usages (same visitor as transformFile)
                cu.accept(new ModifierVisitor<Void>() {
                    @Override
                    public Visitable visit(NameExpr n, Void arg) {
                        String name = n.getNameAsString();
                        ConstantTarget target = constantTargets.get(name);
                        if (target != null && !isDeclaration(n)) {
                            replacedConstants.add(name);
                            return createConfigLookupCall(target);
                        }
                        return super.visit(n, arg);
                    }

                    @Override
                    public Visitable visit(FieldAccessExpr n, Void arg) {
                        String name = n.getNameAsString();
                        ConstantTarget target = constantTargets.get(name);
                        if (target != null) {
                            replacedConstants.add(name);
                            return createConfigLookupCall(target);
                        }
                        return super.visit(n, arg);
                    }

                    @Override
                    public Visitable visit(MethodCallExpr n, Void arg) {
                        super.visit(n, arg);
                        String methodName = n.getNameAsString();
                        if (PROPERTY_GETTER_METHODS.contains(methodName)) {
                            for (Expression argExpr : n.getArguments()) {
                                if (argExpr instanceof StringLiteralExpr strLit) {
                                    String paramName = strLit.getValue();
                                    ConstantTarget target = paramTargets.get(paramName);
                                    if (target != null) {
                                        replacedParams.add(paramName);
                                        return createParameterLookupCall(target, methodName);
                                    }
                                }
                            }
                        }
                        return n;
                    }
                }, null);

                boolean anyTransformed = !replacedConstants.isEmpty() || !replacedParams.isEmpty();
                if (anyTransformed) {
                    addConfigFieldIfMissing(cu);
                    addRequiredImports(cu);
                    writeModifiedSource(sourceFile, cu);  // Write directly to source
                }

                // Capture transformed source lines for before/after comparison
                List<String> transformedSourceLines = anyTransformed
                    ? Files.readAllLines(sourceFile) : originalSourceLines;

                // Build results with actual before/after lines
                for (ConstantTarget target : constantTargets.values()) {
                    if (replacedConstants.contains(target.constantName())) {
                        String beforeLine = getSourceLine(originalSourceLines, target.lineNumber());
                        String afterLine = getSourceLine(transformedSourceLines, target.lineNumber());
                        results.add(TransformationEntry.transformed(target, filePath,
                            null, beforeLine, afterLine));
                    } else {
                        results.add(TransformationEntry.skipped(target, filePath,
                            "No usages of constant '%s' found in file".formatted(target.constantName())));
                    }
                }
                for (ConstantTarget target : paramTargets.values()) {
                    if (replacedParams.contains(target.constantName())) {
                        String beforeLine = getSourceLine(originalSourceLines, target.lineNumber());
                        String afterLine = getSourceLine(transformedSourceLines, target.lineNumber());
                        results.add(TransformationEntry.transformed(target, filePath,
                            null, beforeLine, afterLine));
                    } else {
                        results.add(TransformationEntry.skipped(target, filePath,
                            "No usages of parameter '%s' found in file".formatted(target.constantName())));
                    }
                }

                // Run supervisor checks on the transformed AST
                if (anyTransformed) {
                    TransformSupervisor.FileCheckResult checkResult = supervisor.check(cu, filePath);
                    lastSupervisorResults.put(filePath, checkResult);
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
         * Derives the appropriate config.lookupType() call expression for a constant.
         */
        public String deriveConfigLookupCall(ConstantTarget target) {
            String method = chooseLookupMethod(target.originalType());
            String key = target.propertyKey();

            if ("lookup".equals(method)) {
                return "config.lookup(\"%s\")".formatted(key);
            } else {
                return "config.%s(\"%s\", %s)".formatted(method, key, target.originalValue());
            }
        }

        /**
         * Maps a Java type to the appropriate lookup method name.
         */
        public static String chooseLookupMethod(String javaType) {
            return switch (javaType) {
                case "String", "java.lang.String" -> "lookup";
                case "int", "Integer", "java.lang.Integer" -> "lookupInt";
                case "long", "Long", "java.lang.Long" -> "lookupLong";
                case "boolean", "Boolean", "java.lang.Boolean" -> "lookupBoolean";
                case "double", "Double", "java.lang.Double" -> "lookupDouble";
                default -> "lookup";
            };
        }

        // ========================================================================
        // Private Helpers
        // ========================================================================

        private MethodCallExpr createConfigLookupCall(ConstantTarget target) {
            String method = chooseLookupMethod(target.originalType());
            /*
             * config.lookup*
             */
            MethodCallExpr call = new MethodCallExpr(
                new NameExpr(CONFIG_FIELD_NAME),
                method
            );

            // First argument: the property key
            /*
             * config.lookup*("db.host")
             */
            call.addArgument(new StringLiteralExpr(target.propertyKey()));

            // For typed lookups, add the default value as second argument
            if (!"lookup".equals(method)) {
                call.addArgument(parseDefaultValue(target.originalValue(), target.originalType()));
            }

            return call;
        }

        /**
         * Creates a config.lookupXxx() call for a parameter usage, choosing the lookup method
         * based on the original getter method name (e.g., getInt -> lookupInt).
         */
        private MethodCallExpr createParameterLookupCall(ConstantTarget target, String originalMethodName) {
            String lookupMethod = METHOD_TO_LOOKUP.getOrDefault(originalMethodName, "lookup");
            MethodCallExpr call = new MethodCallExpr(
                new NameExpr(CONFIG_FIELD_NAME),
                lookupMethod
            );
            call.addArgument(new StringLiteralExpr(target.propertyKey()));
            return call;
        }

        private Expression parseDefaultValue(String value, String type) {
            try {
                return switch (type) {
                    case "int", "Integer", "java.lang.Integer" -> new IntegerLiteralExpr(value.replaceAll("[^\\d-]", ""));
                    case "long", "Long", "java.lang.Long" -> new LongLiteralExpr(value.replaceAll("[^\\d-]", "") + "L");
                    case "boolean", "Boolean", "java.lang.Boolean" -> new BooleanLiteralExpr(Boolean.parseBoolean(value));
                    case "double", "Double", "java.lang.Double" -> new DoubleLiteralExpr(value.replaceAll("[^\\d.eE+-]", ""));
                    default -> new StringLiteralExpr(value);
                };
            } catch (Exception e) {
                // Fallback: use string literal
                return new StringLiteralExpr(value);
            }
        }

        /**
         * Safely retrieves a source line by 1-based line number.
         * Returns null if the line number is out of range.
         */
        private static String getSourceLine(List<String> lines, int lineNumber) {
            if (lineNumber < 1 || lineNumber > lines.size()) {
                return null;
            }
            return lines.get(lineNumber - 1).strip();
        }

        private boolean isDeclaration(NameExpr nameExpr) {
            // If parent is a VariableDeclarator, check if this is the name being declared
            return nameExpr.getParentNode()
                .filter(parent -> parent instanceof VariableDeclarator)
                .map(parent -> ((VariableDeclarator) parent).getNameAsString().equals(nameExpr.getNameAsString()))
                .orElse(false);
        }

        private void addConfigFieldIfMissing(CompilationUnit cu) {
            cu.findFirst(ClassOrInterfaceDeclaration.class)
                .filter(cls -> !cls.isInnerClass())
                .ifPresent(cls -> {
                    // Check if config field already exists
                    boolean hasConfigField = cls.getFields().stream()
                        .anyMatch(f -> f.getVariables().stream()
                            .anyMatch(v -> v.getNameAsString().equals(CONFIG_FIELD_NAME)));

                    if (!hasConfigField) {
                        FieldDeclaration configField = cls.addField(
                            CONFIG_SERVICE_CLASS,
                            CONFIG_FIELD_NAME,
                            Modifier.Keyword.PRIVATE
                        );
                        configField.addMarkerAnnotation("Autowired");
                        log.debug("Added config field to class: {}", cls.getNameAsString());
                    }
                });
        }

        private void addRequiredImports(CompilationUnit cu) {
            addImportIfAbsent(cu, "org.springframework.beans.factory.annotation.Autowired");
            addImportIfAbsent(cu, "com.boeing.constcatalog.backend.service.SiteConfigurationService");
        }

        private void addImportIfAbsent(CompilationUnit cu, String importName) {
            boolean alreadyImported = cu.getImports().stream()
                .anyMatch(imp -> imp.getNameAsString().equals(importName));

            if (!alreadyImported) {
                cu.addImport(importName);
            }
        }

        private void writeModifiedSource(Path sourceFile, CompilationUnit cu) throws IOException {
            String modifiedSource = cu.toString();
            Files.writeString(sourceFile, modifiedSource);
            log.info("Wrote transformed source: {}", sourceFile);
        }

        /**
         * The result of running the in-memory mutation pass on a single
         * compilation unit. Contains both the sets of names that were replaced
         * and the dedup'd target maps the visitor consulted, so callers can
         * iterate over the unique targets without re-deriving them.
         */
        private record MutationOutcome(
            Set<String> replacedConstants,
            Set<String> replacedParams,
            Map<String, ConstantTarget> constantTargets,
            Map<String, ConstantTarget> paramTargets) {
            boolean anyTransformed() {
                return !replacedConstants.isEmpty() || !replacedParams.isEmpty();
            }
        }

        /**
         * Applies AST mutations for a single file in memory:
         * <ol>
         *   <li>Splits targets by source kind (CONSTANT vs PARAMETER)</li>
         *   <li>Visits the AST and replaces matching usages with {@code config.lookupXxx(...)}</li>
         *   <li>Removes now-unused single-member static imports for replaced constants</li>
         *   <li>Adds the {@code config} field and required imports</li>
         * </ol>
         *
         * <p>Performs no I/O. Shared by {@link #transformFile} (which then writes
         * to disk) and {@link #previewFile} (which serializes to a String).
         */
        private MutationOutcome applyMutations(CompilationUnit cu, List<ConstantTarget> targets) {
            StaticImportResolver staticImports = new StaticImportResolver(cu);

            Map<String, ConstantTarget> constantTargets = targets.stream()
                .filter(t -> t.sourceKind() == ConstantTarget.SourceKind.CONSTANT)
                .collect(Collectors.toMap(ConstantTarget::constantName, t -> t, (a, b) -> a));

            Map<String, ConstantTarget> paramTargets = targets.stream()
                .filter(t -> t.sourceKind() == ConstantTarget.SourceKind.PARAMETER)
                .collect(Collectors.toMap(ConstantTarget::constantName, t -> t, (a, b) -> a));

            Set<String> replacedConstants = new HashSet<>();
            Set<String> replacedParams = new HashSet<>();

            cu.accept(new ModifierVisitor<Void>() {
                @Override
                public Visitable visit(NameExpr n, Void arg) {
                    String name = n.getNameAsString();
                    ConstantTarget target = constantTargets.get(name);
                    if (target != null && !isDeclaration(n)) {
                        if (staticImports.isStaticallyImported(name)) {
                            log.debug("Replacing statically imported constant {} (owner={})",
                                name, staticImports.findOwner(name).orElse("?"));
                        }
                        replacedConstants.add(name);
                        return createConfigLookupCall(target);
                    }
                    return super.visit(n, arg);
                }

                @Override
                public Visitable visit(FieldAccessExpr n, Void arg) {
                    String name = n.getNameAsString();
                    ConstantTarget target = constantTargets.get(name);
                    if (target != null) {
                        replacedConstants.add(name);
                        return createConfigLookupCall(target);
                    }
                    return super.visit(n, arg);
                }

                @Override
                public Visitable visit(MethodCallExpr n, Void arg) {
                    super.visit(n, arg);
                    String methodName = n.getNameAsString();
                    if (PROPERTY_GETTER_METHODS.contains(methodName)) {
                        for (Expression argExpr : n.getArguments()) {
                            if (argExpr instanceof StringLiteralExpr strLit) {
                                String paramName = strLit.getValue();
                                ConstantTarget target = paramTargets.get(paramName);
                                if (target != null) {
                                    replacedParams.add(paramName);
                                    return createParameterLookupCall(target, methodName);
                                }
                            }
                        }
                    }
                    return n;
                }
            }, null);

            if (!replacedConstants.isEmpty() || !replacedParams.isEmpty()) {
                cu.getImports().removeIf(imp -> {
                    if (!imp.isStatic() || imp.isAsterisk()) return false;
                    String full = imp.getNameAsString();
                    int dot = full.lastIndexOf('.');
                    String member = dot >= 0 ? full.substring(dot + 1) : full;
                    return replacedConstants.contains(member);
                });
                addConfigFieldIfMissing(cu);
                addRequiredImports(cu);
            }

            return new MutationOutcome(replacedConstants, replacedParams, constantTargets, paramTargets);
        }
    }
