package com.boeing.constcatalog.transformer;

import com.boeing.constcatalog.model.ConstantTarget;
import com.boeing.constcatalog.model.TransformationEntry;
import com.boeing.constcatalog.model.TransformationResult;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.Name;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Transforms Java source files by replacing hardcoded static final constants
 * with Spring {@code @Value} annotation-driven property lookups.
 *
 * <p>Given a source file and a list of {@link ConstantTarget}s (pulled from Neo4j),
 * this transformer:
 * <ol>
 *   <li>Parses the file into an AST via JavaParser</li>
 *   <li>Locates each target constant by name and line number</li>
 *   <li>Removes {@code static final} modifiers and the initializer</li>
 *   <li>Adds a {@code @Value("${property.key}")} annotation</li>
 *   <li>Renames the field from SCREAMING_SNAKE to camelCase</li>
 *   <li>Annotates the class with {@code @Component} if not already a Spring bean</li>
 *   <li>Writes the modified source back to disk</li>
 * </ol>
 *
 * <p>Uses functional techniques throughout: streams for collection processing,
 * Optional for null safety, records for immutable results, and sealed
 * interfaces for strategy selection.
 */
public class JavaCodeTransformer {

    private static final Logger log = LoggerFactory.getLogger(JavaCodeTransformer.class);

    private static final Set<String> SPRING_BEAN_ANNOTATIONS = Set.of(
        "Component", "Service", "Repository", "Controller", "RestController",
        "Configuration", "Bean"
    );

    private static final Set<String> NON_INJECTABLE_TYPES = Set.of(
        "Logger", "Pattern", "long", "double", "float", "boolean", "byte", "short", "char"
    );

    private static final Predicate<String> IS_STRING_TYPE =
        type -> "String".equals(type) || "java.lang.String".equals(type);

    private final JavaParser javaParser;

    public JavaCodeTransformer() {
        ParserConfiguration config = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.javaParser = new JavaParser(config);
    }

    // ========================================================================
    // Public API
    // ========================================================================
    /**
     * Transforms a single Java source file, replacing the specified constants
     * with {@code @Value} property lookups.
     *
     * @param sourceFile the Java source file to transform
     * @param targets    constants to replace (from Neo4j)
     * @return entries describing what happened to each target
     */
    public List<TransformationEntry> transformFile(Path sourceFile, List<ConstantTarget> targets) {
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

            CompilationUnit cu = parseResult.getResult().get();
            List<TransformationEntry> results = new ArrayList<>();

            // Process each target constant
            targets.forEach(target -> results.add(
                transformConstant(cu, target, filePath)
            ));

            // If any transformations succeeded, add imports and class annotations
            boolean anyTransformed = results.stream()
                .anyMatch(e -> e.status() == TransformationEntry.Status.TRANSFORMED);

            if (anyTransformed) {
                addRequiredImports(cu);
                ensureSpringBeanAnnotation(cu);
                writeModifiedSource(sourceFile, cu);
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
     * Transforms multiple files in a project, processing targets grouped by file path.
     *
     * @param projectPath    the project root (for reporting)
     * @param targetsByFile  constants grouped by their source file path
     * @return aggregated transformation result
     */
    public TransformationResult transformProject(
            String projectPath,
            Map<Path, List<ConstantTarget>> targetsByFile) {

        List<TransformationEntry> allEntries = targetsByFile.entrySet().stream()
            .flatMap(entry -> transformFile(entry.getKey(), entry.getValue()).stream())
            .toList();

        return new TransformationResult(projectPath, LocalDateTime.now(), allEntries);
    }

    /**
     * Performs a dry-run transformation, returning what would change without modifying files.
     *
     * @param sourceFile the Java source file to analyze
     * @param targets    constants to evaluate
     * @return entries describing what would happen to each target
     */
    public List<TransformationEntry> dryRun(Path sourceFile, List<ConstantTarget> targets) {
        String filePath = sourceFile.toString();

        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceFile);

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return targets.stream()
                    .map(t -> TransformationEntry.failed(t, filePath, "File could not be parsed"))
                    .toList();
            }

            CompilationUnit cu = parseResult.getResult().get();

            return targets.stream()
                .map(target -> evaluateConstant(cu, target, filePath))
                .toList();

        } catch (IOException e) {
            return targets.stream()
                .map(t -> TransformationEntry.failed(t, filePath, "IO error: " + e.getMessage()))
                .toList();
        }
    }

    // ========================================================================
    // Transformation Logic
    // ========================================================================

    private TransformationEntry transformConstant(
            CompilationUnit cu, ConstantTarget target, String filePath) {

        return findTargetField(cu, target)
            .map(field -> applyTransformation(cu, field, target, filePath))
            .orElseGet(() -> TransformationEntry.failed(
                target, filePath,
                "Constant '%s' not found at line %d".formatted(target.constantName(), target.lineNumber())
            ));
    }

    private TransformationEntry evaluateConstant(
            CompilationUnit cu, ConstantTarget target, String filePath) {

        return findTargetField(cu, target)
            .map(field -> {
                var skipReason = getSkipReason(field, target);
                return skipReason
                    .map(reason -> TransformationEntry.skipped(target, filePath, reason))
                    .orElseGet(() -> TransformationEntry.transformed(target, filePath));
            })
            .orElseGet(() -> TransformationEntry.failed(
                target, filePath,
                "Constant '%s' not found at line %d".formatted(target.constantName(), target.lineNumber())
            ));
    }

    private TransformationEntry applyTransformation(
            CompilationUnit cu, FieldDeclaration field, ConstantTarget target, String filePath) {

        // Check if this constant should be skipped
        Optional<String> skipReason = getSkipReason(field, target);
        if (skipReason.isPresent()) {
            return TransformationEntry.skipped(target, filePath, skipReason.get());
        }

        try {
            VariableDeclarator variable = findVariable(field, target.constantName())
                .orElseThrow();

            // 1. Remove static and final modifiers
            field.getModifiers().removeIf(m ->
                m.getKeyword() == Modifier.Keyword.STATIC ||
                m.getKeyword() == Modifier.Keyword.FINAL);

            // 2. Rename from SCREAMING_SNAKE to camelCase
            String camelName = ConstantTarget.deriveCamelCaseName(target.constantName());
            variable.setName(camelName);

            // 3. Remove the initializer (the hardcoded value)
            variable.removeInitializer();

            // 4. Add @Value("${property.key}") annotation
            String valueExpression = "${%s}".formatted(target.propertyKey());
            field.addAnnotation(new SingleMemberAnnotationExpr(
                new Name("Value"),
                new StringLiteralExpr(valueExpression)
            ));

            log.info("Transformed: {} -> @Value(\"${}\") {} {} [{}:{}]",
                target.constantName(), target.propertyKey(),
                target.originalType(), camelName, filePath, target.lineNumber());

            return TransformationEntry.transformed(target, filePath);

        } catch (Exception e) {
            log.error("Failed to transform constant '{}' in {}", target.constantName(), filePath, e);
            return TransformationEntry.failed(target, filePath, "Transform error: " + e.getMessage());
        }
    }

    // ========================================================================
    // Field Location
    // ========================================================================

    /**
     * Finds the target field declaration in the AST by matching constant name
     * and approximate line number.
     */
    private Optional<FieldDeclaration> findTargetField(CompilationUnit cu, ConstantTarget target) {
        return cu.findAll(FieldDeclaration.class).stream()
            .filter(field -> isStaticFinal(field))
            .filter(field -> hasVariable(field, target.constantName()))
            .filter(field -> isNearLine(field, target.lineNumber()))
            .findFirst();
    }

    private Optional<VariableDeclarator> findVariable(FieldDeclaration field, String name) {
        return field.getVariables().stream()
            .filter(v -> v.getNameAsString().equals(name))
            .findFirst();
    }

    private boolean isStaticFinal(FieldDeclaration field) {
        boolean isStatic = field.getModifiers().stream()
            .anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
        boolean isFinal = field.getModifiers().stream()
            .anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
        return isStatic && isFinal;
    }

    private boolean hasVariable(FieldDeclaration field, String name) {
        /*
         * The declaration : int x, y, z;
         * is a FieldDeclaration, hence the getVariables() method on field
         */
        return field.getVariables().stream()
            .anyMatch(v -> v.getNameAsString().equals(name));
    }

    /**
     * Allows a tolerance of +/- 3 lines to account for minor file edits
     * since the last scan.
     */
    private boolean isNearLine(FieldDeclaration field, int targetLine) {
        return field.getBegin()
            .map(pos -> Math.abs(pos.line - targetLine) <= 3)
            .orElse(false);
    }

    // ========================================================================
    // Skip Logic
    // ========================================================================

    private Optional<String> getSkipReason(FieldDeclaration field, ConstantTarget target) {
        String type = field.getVariables().getFirst()
            .map(VariableDeclarator::getTypeAsString)
            .orElse("");

        // Skip non-injectable types (Logger, Pattern, etc.)
        if (NON_INJECTABLE_TYPES.contains(type)) {
            return Optional.of("Type '%s' is not suitable for @Value injection".formatted(type));
        }

        // Skip if already annotated with @Value
        boolean alreadyAnnotated = field.getAnnotations().stream()
            .anyMatch(a -> "Value".equals(a.getNameAsString()));
        if (alreadyAnnotated) {
            return Optional.of("Already has @Value annotation");
        }

        // Skip constants with complex initializers (method calls, new expressions)
        Optional<String> value = field.getVariables().getFirst()
            .flatMap(VariableDeclarator::getInitializer)
            .map(Object::toString);

        if (value.isPresent()) {
            String v = value.get();
            if (v.contains("new ") || v.contains("Arrays.") || v.contains("List.of")
                    || v.contains("Map.of") || v.contains("Set.of") || v.contains("Pattern.")) {
                return Optional.of("Complex initializer not suitable for property lookup");
            }
        }

        return Optional.empty();
    }

    // ========================================================================
    // Import and Annotation Management
    // ========================================================================

    private void addRequiredImports(CompilationUnit cu) {
        addImportIfAbsent(cu, "org.springframework.beans.factory.annotation.Value");
        addImportIfAbsent(cu, "org.springframework.stereotype.Component");
    }

    private void addImportIfAbsent(CompilationUnit cu, String importName) {
        boolean alreadyImported = cu.getImports().stream()
            .anyMatch(imp -> imp.getNameAsString().equals(importName));

        if (!alreadyImported) {
            cu.addImport(importName);
        }
    }

    /**
     * Ensures the top-level class has a Spring bean annotation.
     * If none is present, adds {@code @Component}.
     */
    private void ensureSpringBeanAnnotation(CompilationUnit cu) {
        cu.findFirst(ClassOrInterfaceDeclaration.class)
            .filter(cls -> !cls.isInnerClass())
            .filter(cls -> !hasSpringBeanAnnotation(cls))
            .ifPresent(cls -> {
                cls.addAnnotation("Component");
                log.debug("Added @Component annotation to class: {}", cls.getNameAsString());
            });
    }

    private boolean hasSpringBeanAnnotation(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotations().stream()
            .anyMatch(a -> SPRING_BEAN_ANNOTATIONS.contains(a.getNameAsString()));
    }

    // ========================================================================
    // File I/O
    // ========================================================================

    private void writeModifiedSource(Path sourceFile, CompilationUnit cu) throws IOException {
        String modifiedSource = cu.toString();
        Files.writeString(sourceFile, modifiedSource);
        log.info("Wrote transformed source: {}", sourceFile);
    }

    // ========================================================================
    // Static Utility: Configuration Candidate Detection
    // ========================================================================

    private static final Set<String> CONFIG_PATTERNS = Set.of(
        "HOST", "PORT", "URL", "URI", "PATH", "KEY", "SECRET",
        "PASSWORD", "TIMEOUT", "ENDPOINT", "DATABASE", "CONNECTION",
        "SERVER", "CLIENT"
    );

    /**
     * Determines if a constant name suggests it should be externalized.
     */
    public static boolean looksLikeConfiguration(String constantName) {
        String upper = constantName.toUpperCase();
        return CONFIG_PATTERNS.stream().anyMatch(upper::contains);
    }
}
