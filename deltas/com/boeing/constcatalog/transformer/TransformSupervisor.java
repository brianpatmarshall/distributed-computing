package com.boeing.constcatalog.transformer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Advisory post-transformation checker. Examines a transformed CompilationUnit
 * (or source file) and reports potential issues without blocking the transformation.
 *
 * <p>Checks performed:
 * <ul>
 *   <li><b>Syntax</b> — Does the transformed source parse without errors?</li>
 *   <li><b>Config field</b> — Does the class have a {@code config} field of type
 *       {@code SiteConfigurationService}?</li>
 *   <li><b>Imports</b> — Are the required imports for {@code @Autowired} and
 *       {@code SiteConfigurationService} present?</li>
 * </ul>
 *
 * <p>Results are advisory — they are included in the Musketeer Log but do not
 * prevent the transformation from completing.
 */
public class TransformSupervisor {

    private static final Logger log = LoggerFactory.getLogger(TransformSupervisor.class);

    private static final String CONFIG_FIELD_NAME = "config";
    private static final String CONFIG_SERVICE_TYPE = "SiteConfigurationService";
    private static final String AUTOWIRED_IMPORT = "org.springframework.beans.factory.annotation.Autowired";
    private static final String CONFIG_SERVICE_IMPORT = "com.boeing.constcatalog.backend.service.SiteConfigurationService";

    /**
     * Result of a single supervisor check.
     */
    public record CheckResult(String checkName, Status status, String detail) {

        public enum Status {
            PASS,
            WARN,
            FAIL
        }

        public static CheckResult pass(String checkName) {
            return new CheckResult(checkName, Status.PASS, null);
        }

        public static CheckResult warn(String checkName, String detail) {
            return new CheckResult(checkName, Status.WARN, detail);
        }

        public static CheckResult fail(String checkName, String detail) {
            return new CheckResult(checkName, Status.FAIL, detail);
        }
    }

    /**
     * Aggregated result for a single file.
     */
    public record FileCheckResult(String filePath, List<CheckResult> checks) {

        public boolean hasWarnings() {
            return checks.stream().anyMatch(c -> c.status() == CheckResult.Status.WARN);
        }

        public boolean hasFailures() {
            return checks.stream().anyMatch(c -> c.status() == CheckResult.Status.FAIL);
        }

        public boolean allPassed() {
            return checks.stream().allMatch(c -> c.status() == CheckResult.Status.PASS);
        }
    }

    /**
     * Check a CompilationUnit that has already been transformed.
     *
     * @param cu       the transformed AST
     * @param filePath the file path (for reporting)
     * @return check results for this file
     */
    public FileCheckResult check(CompilationUnit cu, String filePath) {
        List<CheckResult> checks = new ArrayList<>();

        checks.add(checkSyntax(cu));
        checks.add(checkConfigField(cu));
        checks.add(checkAutowiredImport(cu));
        checks.add(checkConfigServiceImport(cu));

        FileCheckResult result = new FileCheckResult(filePath, checks);

        if (result.hasFailures()) {
            log.warn("Supervisor FAIL for {}: {}", filePath,
                checks.stream()
                    .filter(c -> c.status() == CheckResult.Status.FAIL)
                    .map(c -> c.checkName() + ": " + c.detail())
                    .toList());
        } else if (result.hasWarnings()) {
            log.info("Supervisor WARN for {}: {}", filePath,
                checks.stream()
                    .filter(c -> c.status() == CheckResult.Status.WARN)
                    .map(c -> c.checkName() + ": " + c.detail())
                    .toList());
        } else {
            log.debug("Supervisor PASS for {}", filePath);
        }

        return result;
    }

    /**
     * Check a transformed source file by parsing it fresh.
     * This validates that the written file is syntactically valid.
     *
     * @param sourceFile the transformed file on disk
     * @return check results for this file
     */
    public FileCheckResult checkFile(Path sourceFile) {
        String filePath = sourceFile.toString();
        List<CheckResult> checks = new ArrayList<>();

        try {
            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> parseResult = parser.parse(sourceFile);

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                checks.add(CheckResult.fail("Syntax",
                    "File does not parse: " + parseResult.getProblems()));
                return new FileCheckResult(filePath, checks);
            }

            CompilationUnit cu = parseResult.getResult().get();
            checks.add(CheckResult.pass("Syntax"));
            checks.add(checkConfigField(cu));
            checks.add(checkAutowiredImport(cu));
            checks.add(checkConfigServiceImport(cu));

        } catch (Exception e) {
            checks.add(CheckResult.fail("Syntax", "Exception during parse: " + e.getMessage()));
        }

        return new FileCheckResult(filePath, checks);
    }

    // ========================================================================
    // Individual checks
    // ========================================================================

    private CheckResult checkSyntax(CompilationUnit cu) {
        // If we have a CU, parsing already succeeded. Verify it can round-trip
        // by converting back to source and re-parsing.
        try {
            String source = cu.toString();
            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> reparse = parser.parse(source);
            if (reparse.isSuccessful()) {
                return CheckResult.pass("Syntax");
            } else {
                return CheckResult.fail("Syntax",
                    "Re-parsed source has errors: " + reparse.getProblems());
            }
        } catch (Exception e) {
            return CheckResult.fail("Syntax", "Round-trip parse failed: " + e.getMessage());
        }
    }

    private CheckResult checkConfigField(CompilationUnit cu) {
        return cu.findFirst(ClassOrInterfaceDeclaration.class)
            .map(cls -> {
                for (FieldDeclaration field : cls.getFields()) {
                    for (VariableDeclarator var : field.getVariables()) {
                        if (CONFIG_FIELD_NAME.equals(var.getNameAsString())) {
                            String type = var.getTypeAsString();
                            if (type.equals(CONFIG_SERVICE_TYPE)) {
                                // Check for @Autowired annotation on the field
                                boolean hasAutowired = field.getAnnotations().stream()
                                    .anyMatch(a -> "Autowired".equals(a.getNameAsString()));
                                if (hasAutowired) {
                                    return CheckResult.pass("Config Field");
                                } else {
                                    return CheckResult.warn("Config Field",
                                        "Field 'config' exists but missing @Autowired annotation");
                                }
                            } else {
                                return CheckResult.warn("Config Field",
                                    "Field 'config' exists but type is '" + type
                                        + "', expected '" + CONFIG_SERVICE_TYPE + "'");
                            }
                        }
                    }
                }
                return CheckResult.warn("Config Field",
                    "No 'config' field found in class '" + cls.getNameAsString() + "'");
            })
            .orElse(CheckResult.warn("Config Field", "No class declaration found in file"));
    }

    private CheckResult checkAutowiredImport(CompilationUnit cu) {
        boolean found = cu.getImports().stream()
            .map(ImportDeclaration::getNameAsString)
            .anyMatch(AUTOWIRED_IMPORT::equals);

        return found
            ? CheckResult.pass("Autowired Import")
            : CheckResult.warn("Autowired Import",
                "Missing import: " + AUTOWIRED_IMPORT);
    }

    private CheckResult checkConfigServiceImport(CompilationUnit cu) {
        boolean found = cu.getImports().stream()
            .map(ImportDeclaration::getNameAsString)
            .anyMatch(CONFIG_SERVICE_IMPORT::equals);

        return found
            ? CheckResult.pass("ConfigService Import")
            : CheckResult.warn("ConfigService Import",
                "Missing import: " + CONFIG_SERVICE_IMPORT);
    }
}
