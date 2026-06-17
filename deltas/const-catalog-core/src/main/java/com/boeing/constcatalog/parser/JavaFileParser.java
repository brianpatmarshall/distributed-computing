package com.boeing.constcatalog.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.boeing.constcatalog.model.Constant;
import com.boeing.constcatalog.model.ConstantUsage;
import com.boeing.constcatalog.model.ParameterUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses Java files to extract constants (public static final fields and enum constants)
 * and detect parameter usages (property lookups).
 */
public class JavaFileParser {
    private static final Logger logger = LoggerFactory.getLogger(JavaFileParser.class);
    private final JavaParser javaParser;

    // Common method names used to retrieve properties
    private static final Set<String> PROPERTY_GETTER_METHODS = Set.of(
        "getProperty", "get", "getString", "getInt", "getInteger", "getLong",
        "getBoolean", "getDouble", "getFloat", "getValue", "getConfig",
        "getenv", "getOrDefault", "getPropertyValue"
    );

    // Pattern to extract parameter names from @Value("${param.name}") or @Value("${param.name:default}")
    private static final Pattern VALUE_ANNOTATION_PATTERN = Pattern.compile("\\$\\{([^:}]+)");

    public JavaFileParser() {
        this.javaParser = new JavaParser();
    }

    /**
     * Parse a Java file and extract all constants.
     * Only public static final fields are cataloged. Private/protected/package-private are skipped.
     * Enum constants are also cataloged.
     */
    public List<Constant> parseConstants(Path filePath) {
        List<Constant> constants = new ArrayList<>();
        String fileName = filePath.toString();

        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(filePath);

            if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                CompilationUnit compilationUnit = parseResult.getResult().get();

                compilationUnit.accept(new VoidVisitorAdapter<Void>() {
                    private String currentClass = "";

                    @Override
                    public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                        String previousClass = currentClass;
                        currentClass = n.getNameAsString();
                        super.visit(n, arg);
                        currentClass = previousClass;
                    }

                    @Override
                    public void visit(EnumDeclaration n, Void arg) {
                        String previousClass = currentClass;
                        currentClass = n.getNameAsString();
                        // Enum values are not static final fields; only the explicit
                        // static final field declarations inside the enum body are cataloged
                        // (handled by visit(FieldDeclaration) via super.visit below).
                        super.visit(n, arg);
                        currentClass = previousClass;
                    }

                    @Override
                    public void visit(FieldDeclaration field, Void arg) {
                        boolean isStatic = field.getModifiers().stream()
                            .anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
                        boolean isFinal = field.getModifiers().stream()
                            .anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);

                        // Catalog all static final fields regardless of access modifier
                        if (isStatic && isFinal) {
                            String accessModifier = field.getModifiers().stream()
                                .map(m -> m.getKeyword())
                                .filter(k -> k == Modifier.Keyword.PUBLIC
                                          || k == Modifier.Keyword.PROTECTED
                                          || k == Modifier.Keyword.PRIVATE)
                                .findFirst()
                                .map(k -> k.asString())
                                .orElse("package-private");

                            for (VariableDeclarator variable : field.getVariables()) {
                                String name = variable.getNameAsString();
                                String type = variable.getTypeAsString();
                                String value = variable.getInitializer()
                                    .map(Object::toString)
                                    .orElse("");
                                int lineNumber = field.getBegin()
                                    .map(pos -> pos.line)
                                    .orElse(0);

                                constants.add(new Constant(
                                    name, type, value, fileName, currentClass,
                                    lineNumber, accessModifier, false, null
                                ));
                            }
                        }
                        super.visit(field, arg);
                    }
                }, null);
            } else {
                logger.warn("Failed to parse Java file: {}", filePath);
                parseResult.getProblems().forEach(p ->
                    logger.debug("  Problem: {}", p.getMessage()));
            }
        } catch (IOException e) {
            logger.error("Error reading file: {}", filePath, e);
        }

        return constants;
    }

    /**
     * Parse a Java file and find usages of properties/parameters.
     */
    public List<ParameterUsage> parseParameterUsages(Path filePath, Set<String> knownParameters) {
        List<ParameterUsage> usages = new ArrayList<>();
        String fileName = filePath.toString();

        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(filePath);

            if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                CompilationUnit cu = parseResult.getResult().get();

                cu.accept(new VoidVisitorAdapter<Void>() {
                    private String currentClass = "";

                    @Override
                    public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                        String previousClass = currentClass;
                        currentClass = n.getNameAsString();
                        super.visit(n, arg);
                        currentClass = previousClass;
                    }

                    @Override
                    public void visit(FieldDeclaration field, Void arg) {
                        // Check for @Value annotations on fields
                        for (AnnotationExpr annotation : field.getAnnotations()) {
                            String annotationName = annotation.getNameAsString();
                            if ("Value".equals(annotationName)) {
                                String annotationValue = annotation.toString();
                                Matcher matcher = VALUE_ANNOTATION_PATTERN.matcher(annotationValue);
                                while (matcher.find()) {
                                    String propertyName = matcher.group(1);
                                    if (knownParameters.contains(propertyName)) {
                                        int lineNumber = field.getBegin()
                                            .map(pos -> pos.line)
                                            .orElse(0);

                                        usages.add(new ParameterUsage(
                                            propertyName,
                                            fileName,
                                            currentClass,
                                            lineNumber,
                                            annotationValue
                                        ));
                                    }
                                }
                            }
                        }
                        super.visit(field, arg);
                    }

                    @Override
                    public void visit(MethodCallExpr methodCall, Void arg) {
                        String methodName = methodCall.getNameAsString();

                        // Check if this is a property getter method
                        if (PROPERTY_GETTER_METHODS.contains(methodName)) {
                            // Look for string literal arguments (property names)
                            methodCall.getArguments().forEach(argExpr -> {
                                if (argExpr instanceof StringLiteralExpr stringArg) {
                                    String propertyName = stringArg.getValue();

                                    // Check if this matches a known parameter
                                    if (knownParameters.contains(propertyName)) {
                                        int lineNumber = methodCall.getBegin()
                                            .map(pos -> pos.line)
                                            .orElse(0);

                                        usages.add(new ParameterUsage(
                                            propertyName,
                                            fileName,
                                            currentClass,
                                            lineNumber,
                                            methodCall.toString()
                                        ));
                                    }
                                }
                            });
                        }
                        super.visit(methodCall, arg);
                    }
                }, null);
            }
        } catch (IOException e) {
            logger.error("Error reading file: {}", filePath, e);
        }

        return usages;
    }

    /**
     * Parse a Java file and find usages of constants defined in other classes.
     */
    public List<ConstantUsage> parseConstantUsages(Path filePath, Map<String, Set<String>> constantsByName) {
        List<ConstantUsage> usages = new ArrayList<>();
        String fileName = filePath.toString();

        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(filePath);

            if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                CompilationUnit cu = parseResult.getResult().get();

                // Build static import info
                Set<String> staticImportedConstants = new HashSet<>();
                Set<String> wildcardStaticImportedClasses = new HashSet<>();

                for (ImportDeclaration imp : cu.getImports()) {
                    if (imp.isStatic()) {
                        String imported = imp.getNameAsString();
                        if (imp.isAsterisk()) {
                            // import static com.example.ClassName.*
                            int lastDot = imported.lastIndexOf('.');
                            if (lastDot >= 0) {
                                wildcardStaticImportedClasses.add(imported.substring(lastDot + 1));
                            } else {
                                wildcardStaticImportedClasses.add(imported);
                            }
                        } else {
                            // import static com.example.ClassName.CONST_NAME
                            int lastDot = imported.lastIndexOf('.');
                            if (lastDot >= 0) {
                                staticImportedConstants.add(imported.substring(lastDot + 1));
                            }
                        }
                    }
                }

                cu.accept(new VoidVisitorAdapter<Void>() {
                    private String currentClass = "";

                    @Override
                    public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                        String previousClass = currentClass;
                        currentClass = n.getNameAsString();
                        super.visit(n, arg);
                        currentClass = previousClass;
                    }

                    @Override
                    public void visit(FieldAccessExpr fieldAccess, Void arg) {
                        // e.g., AppConfig.DB_HOST or Singer.FIND_SINGER_BY_ID (same-class)
                        String fieldName = fieldAccess.getNameAsString();
                        if (fieldAccess.getScope() instanceof NameExpr scopeExpr) {
                            String scopeName = scopeExpr.getNameAsString();
                            Set<String> definingClasses = constantsByName.get(fieldName);
                            if (definingClasses != null && definingClasses.contains(scopeName)) {
                                int lineNumber = fieldAccess.getBegin()
                                    .map(pos -> pos.line)
                                    .orElse(0);

                                usages.add(new ConstantUsage(
                                    fieldName,
                                    scopeName,
                                    fileName,
                                    currentClass,
                                    lineNumber,
                                    fieldAccess.toString()
                                ));
                            }
                        }
                        super.visit(fieldAccess, arg);
                    }

                    @Override
                    public void visit(NameExpr nameExpr, Void arg) {
                        // e.g., DB_HOST via static import or same-class bare reference
                        String name = nameExpr.getNameAsString();
                        Set<String> definingClasses = constantsByName.get(name);
                        if (definingClasses != null) {
                            // Check if this bare name is a same-class constant reference
                            boolean isSameClassRef = definingClasses.contains(currentClass);

                            if (isSameClassRef) {
                                int lineNumber = nameExpr.getBegin()
                                    .map(pos -> pos.line)
                                    .orElse(0);

                                usages.add(new ConstantUsage(
                                    name,
                                    currentClass,
                                    fileName,
                                    currentClass,
                                    lineNumber,
                                    name
                                ));
                            } else if (staticImportedConstants.contains(name)) {
                                for (String definingClass : definingClasses) {
                                    int lineNumber = nameExpr.getBegin()
                                        .map(pos -> pos.line)
                                        .orElse(0);

                                    usages.add(new ConstantUsage(
                                        name,
                                        definingClass,
                                        fileName,
                                        currentClass,
                                        lineNumber,
                                        name
                                    ));
                                }
                            } else {
                                for (String definingClass : definingClasses) {
                                    if (wildcardStaticImportedClasses.contains(definingClass)) {
                                        int lineNumber = nameExpr.getBegin()
                                            .map(pos -> pos.line)
                                            .orElse(0);

                                        usages.add(new ConstantUsage(
                                            name,
                                            definingClass,
                                            fileName,
                                            currentClass,
                                            lineNumber,
                                            name
                                        ));
                                    }
                                }
                            }
                        }
                        super.visit(nameExpr, arg);
                    }
                }, null);
            }
        } catch (IOException e) {
            logger.error("Error reading file: {}", filePath, e);
        }

        return usages;
    }

    /**
     * Extract the fully qualified name (FQN) from a Java file.
     * Returns null if the FQN cannot be determined.
     */
    public String extractFqn(Path filePath) {
        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(filePath);

            if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                CompilationUnit cu = parseResult.getResult().get();

                String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

                String typeName = cu.getPrimaryTypeName()
                    .orElseGet(() -> cu.getTypes().isEmpty() ? null :
                        cu.getTypes().get(0).getNameAsString());

                if (typeName == null) {
                    return null;
                }

                return packageName.isEmpty() ? typeName : packageName + "." + typeName;
            }
        } catch (IOException e) {
            logger.error("Error reading file for FQN extraction: {}", filePath, e);
        }

        return null;
    }
}
