package com.boeing.constcatalog.transformer;

import com.boeing.constcatalog.model.ConstantTarget;
import com.boeing.constcatalog.model.TransformationEntry;
import com.boeing.constcatalog.model.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnusedConstantRemoverTest {

    private UnusedConstantRemover remover;

    @TempDir
    Path tempDir;

    private Path backupsDir;

    @BeforeEach
    void setUp() {
        remover = new UnusedConstantRemover();
        backupsDir = tempDir.resolve("backups");
    }

    private Path outputPath(Path sourceFile) {
        Path abs = sourceFile.toAbsolutePath();
        return backupsDir.resolve(abs.getRoot().relativize(abs));
    }

    @Test
    void testRemovesSingleUnusedConstant() throws IOException {
        String source = """
            package com.example;

            public class Config {
                public static final String DB_HOST = "localhost";
                public static final int DB_PORT = 5432;

                public void connect() {
                    int port = DB_PORT;
                }
            }
            """;

        Path javaFile = writeSource("Config.java", source);

        ConstantTarget target = new ConstantTarget(
            "DB_HOST", "db.host", "\"localhost\"", "String", "Config", 4
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, List.of(target), backupsDir);

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.TRANSFORMED, results.getFirst().status());
        assertTrue(results.getFirst().message().contains("Removed unused constant"));

        String modified = Files.readString(outputPath(javaFile));
        assertFalse(modified.contains("DB_HOST"), "DB_HOST should be removed");
        assertTrue(modified.contains("DB_PORT"), "DB_PORT should remain");
    }

    @Test
    void testRemovesMultipleConstants() throws IOException {
        String source = """
            package com.example;

            public class Unused {
                public static final String API_HOST = "api.example.com";
                public static final int API_PORT = 443;
                public static final String API_KEY = "secret";
            }
            """;

        Path javaFile = writeSource("Unused.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("API_HOST", "api.host", "\"api.example.com\"", "String", "Unused", 4),
            new ConstantTarget("API_PORT", "api.port", "443", "int", "Unused", 5),
            new ConstantTarget("API_KEY", "api.key", "\"secret\"", "String", "Unused", 6)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        long transformed = results.stream()
            .filter(e -> e.status() == TransformationEntry.Status.TRANSFORMED)
            .count();
        assertEquals(3, transformed);

        String modified = Files.readString(outputPath(javaFile));
        assertFalse(modified.contains("API_HOST"));
        assertFalse(modified.contains("API_PORT"));
        assertFalse(modified.contains("API_KEY"));
    }

    @Test
    void testProtectsSerialVersionUID() throws IOException {
        // serialVersionUID is typically private, so the isPublic filter skips it.
        // This test uses public to verify the protected-name check independently.
        String source = """
            package com.example;

            import java.io.Serializable;

            public class MySerializable implements Serializable {
                public static final long serialVersionUID = 1L;
                public static final String UNUSED_HOST = "localhost";
            }
            """;

        Path javaFile = writeSource("MySerializable.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("serialVersionUID", "serial.version.uid", "1", "long", "MySerializable", 6),
            new ConstantTarget("UNUSED_HOST", "unused.host", "\"localhost\"", "String", "MySerializable", 7)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        assertEquals(2, results.size());

        TransformationEntry serialEntry = results.stream()
            .filter(e -> e.constantName().equals("serialVersionUID"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.SKIPPED, serialEntry.status());
        assertTrue(serialEntry.message().contains("Protected name"));

        TransformationEntry hostEntry = results.stream()
            .filter(e -> e.constantName().equals("UNUSED_HOST"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.TRANSFORMED, hostEntry.status());
    }

    @Test
    void testProtectsLoggerField() throws IOException {
        // Uses public to test the protected-name check (private would be caught by isPublic first)
        String source = """
            package com.example;

            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;

            public class MyService {
                public static final Logger LOG = LoggerFactory.getLogger(MyService.class);
                public static final String DB_HOST = "localhost";
            }
            """;

        Path javaFile = writeSource("MyService.java", source);

        ConstantTarget target = new ConstantTarget(
            "LOG", "log", "LoggerFactory.getLogger(MyService.class)", "Logger", "MyService", 7
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, List.of(target), backupsDir);

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.SKIPPED, results.getFirst().status());
        assertTrue(results.getFirst().message().contains("Protected name"));
    }

    @Test
    void testDryRunDoesNotModifyFile() throws IOException {
        String source = """
            package com.example;

            public class DryRunTest {
                public static final String DB_HOST = "localhost";
            }
            """;

        Path javaFile = writeSource("DryRunTest.java", source);
        String originalContent = Files.readString(javaFile);

        ConstantTarget target = new ConstantTarget(
            "DB_HOST", "db.host", "\"localhost\"", "String", "DryRunTest", 4
        );

        List<TransformationEntry> results = remover.dryRun(javaFile, List.of(target));

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.TRANSFORMED, results.getFirst().status());
        assertTrue(results.getFirst().message().contains("Would remove"));

        String afterContent = Files.readString(javaFile);
        assertEquals(originalContent, afterContent, "Dry run should not modify the file");
    }

    @Test
    void testSkipsNonStaticFinalFields() throws IOException {
        String source = """
            package com.example;

            public class NonFinal {
                public static String MUTABLE_HOST = "localhost";
                public final String INSTANCE_FIELD = "value";
            }
            """;

        Path javaFile = writeSource("NonFinal.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("MUTABLE_HOST", "mutable.host", "\"localhost\"", "String", "NonFinal", 4),
            new ConstantTarget("INSTANCE_FIELD", "instance.field", "\"value\"", "String", "NonFinal", 5)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        // Both should be skipped since neither is static final
        long skipped = results.stream()
            .filter(e -> e.status() == TransformationEntry.Status.SKIPPED)
            .count();
        assertEquals(2, skipped);
    }

    @Test
    void testMultiVariableDeclarationRemovesSingleVar() throws IOException {
        // Note: JavaParser represents multi-variable declarations as one FieldDeclaration
        // with multiple VariableDeclarators
        String source = """
            package com.example;

            public class MultiVar {
                public static final int A = 1, B = 2, C = 3;

                public void use() {
                    int x = B;
                }
            }
            """;

        Path javaFile = writeSource("MultiVar.java", source);

        // Remove only A (unused), B is still used
        ConstantTarget target = new ConstantTarget(
            "A", "a", "1", "int", "MultiVar", 4
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, List.of(target), backupsDir);

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.TRANSFORMED, results.getFirst().status());

        String modified = Files.readString(outputPath(javaFile));
        assertFalse(modified.contains(" A =") || modified.contains(" A="), "A should be removed");
        assertTrue(modified.contains("B"), "B should remain");
        assertTrue(modified.contains("C"), "C should remain");
    }

    @Test
    void testPreservesBackupFile() throws IOException {
        String source = """
            package com.example;

            public class BackupTest {
                public static final String DB_HOST = "localhost";
            }
            """;

        Path javaFile = writeSource("BackupTest.java", source);
        String originalContent = Files.readString(javaFile);

        ConstantTarget target = new ConstantTarget(
            "DB_HOST", "db.host", "\"localhost\"", "String", "BackupTest", 4
        );

        remover.removeFromFile(javaFile, List.of(target), backupsDir);

        Path backupFile = Path.of(outputPath(javaFile) + ".orig");
        assertTrue(Files.exists(backupFile), "Backup file should exist");

        String backupContent = Files.readString(backupFile);
        assertEquals(originalContent, backupContent, "Backup should contain original content");
    }

    @Test
    void testSkipsWhenDeclarationNotFound() throws IOException {
        String source = """
            package com.example;

            public class Empty {
                public void doSomething() {}
            }
            """;

        Path javaFile = writeSource("Empty.java", source);

        ConstantTarget target = new ConstantTarget(
            "MISSING_CONST", "missing.const", "\"value\"", "String", "Empty", 4
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, List.of(target), backupsDir);

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.SKIPPED, results.getFirst().status());
        assertTrue(results.getFirst().message().contains("not found"));
    }

    @Test
    void testRemoveFromProject() throws IOException {
        String source1 = """
            package com.example;

            public class ServiceA {
                public static final String HOST_A = "localhost";
            }
            """;

        String source2 = """
            package com.example;

            public class ServiceB {
                public static final int PORT_B = 8080;
            }
            """;

        Path file1 = writeSource("ServiceA.java", source1);
        Path file2 = writeSource("ServiceB.java", source2);

        Map<Path, List<ConstantTarget>> targetsByFile = Map.of(
            file1, List.of(new ConstantTarget("HOST_A", "host.a", "\"localhost\"", "String", "ServiceA", 4)),
            file2, List.of(new ConstantTarget("PORT_B", "port.b", "8080", "int", "ServiceB", 4))
        );

        TransformationResult result = remover.removeFromProject(tempDir.toString(), targetsByFile);

        assertEquals(2, result.totalTransformed());
        assertEquals(0, result.totalSkipped());
        assertEquals(0, result.totalFailed());
    }

    @Test
    void testEmptyTargetListReturnsEmpty() {
        Path javaFile = tempDir.resolve("Empty.java");

        List<TransformationEntry> results = remover.removeFromFile(javaFile, List.of(), backupsDir);

        assertTrue(results.isEmpty());
    }

    // ========================================================================
    // Logger protection tests
    // ========================================================================

    @Test
    void testProtectsUnderscoreLoggerByName() throws IOException {
        // Uses public to test the protected-name check (private would be caught by isPublic first)
        String source = """
            package com.example;

            import org.apache.log4j.Logger;

            public class TestService {
                public static final Logger _logger = Logger.getLogger(TestService.class);
                public static final String UNUSED = "value";
            }
            """;

        Path javaFile = writeSource("TestService.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("_logger", "logger", "Logger.getLogger(TestService.class)", "Logger", "TestService", 6),
            new ConstantTarget("UNUSED", "unused", "\"value\"", "String", "TestService", 7)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        TransformationEntry loggerEntry = results.stream()
            .filter(e -> e.constantName().equals("_logger"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.SKIPPED, loggerEntry.status());
        assertTrue(loggerEntry.message().contains("Protected name"));

        TransformationEntry unusedEntry = results.stream()
            .filter(e -> e.constantName().equals("UNUSED"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.TRANSFORMED, unusedEntry.status());
    }

    @Test
    void testProtectsLoggerByType() throws IOException {
        // Uses public to test type-based detection (private would be caught by isPublic first)
        String source = """
            package com.example;

            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;

            public class AuditService {
                public static final Logger auditLog = LoggerFactory.getLogger("audit");
                public static final String UNUSED = "value";
            }
            """;

        Path javaFile = writeSource("AuditService.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("auditLog", "audit.log", "LoggerFactory.getLogger(\"audit\")", "Logger", "AuditService", 7),
            new ConstantTarget("UNUSED", "unused", "\"value\"", "String", "AuditService", 8)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        TransformationEntry loggerEntry = results.stream()
            .filter(e -> e.constantName().equals("auditLog"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.SKIPPED, loggerEntry.status());
        assertTrue(loggerEntry.message().contains("Logger field"), "Should mention logger type detection");

        TransformationEntry unusedEntry = results.stream()
            .filter(e -> e.constantName().equals("UNUSED"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.TRANSFORMED, unusedEntry.status());
    }

    @Test
    void testProtectsLoggerByTypeInDryRun() throws IOException {
        // Uses public to test type-based detection in dry run
        String source = """
            package com.example;

            import org.apache.log4j.Logger;

            public class DryRunLoggerTest {
                public static final Logger metricsLogger = Logger.getLogger("metrics");
            }
            """;

        Path javaFile = writeSource("DryRunLoggerTest.java", source);

        ConstantTarget target = new ConstantTarget(
            "metricsLogger", "metrics.logger", "Logger.getLogger(\"metrics\")", "Logger", "DryRunLoggerTest", 6
        );

        List<TransformationEntry> results = remover.dryRun(javaFile, List.of(target));

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.SKIPPED, results.getFirst().status());
        assertTrue(results.getFirst().message().contains("Logger field"));
    }

    @Test
    void testProtectsAllProtectedNameVariants() throws IOException {
        String source = """
            package com.example;

            public class AllProtected {
                public static final String serialVersionUID = "1";
                public static final String VERSION = "1.0";
                public static final String LOG = "log";
                public static final String LOGGER = "logger";
                public static final String logger = "logger";
                public static final String _logger = "logger";
                public static final String log = "log";
                public static final String _log = "log";
                public static final String REMOVABLE = "yes";
            }
            """;

        Path javaFile = writeSource("AllProtected.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("serialVersionUID", "serial", "\"1\"", "String", "AllProtected", 4),
            new ConstantTarget("VERSION", "version", "\"1.0\"", "String", "AllProtected", 5),
            new ConstantTarget("LOG", "log", "\"log\"", "String", "AllProtected", 6),
            new ConstantTarget("LOGGER", "logger", "\"logger\"", "String", "AllProtected", 7),
            new ConstantTarget("logger", "logger", "\"logger\"", "String", "AllProtected", 8),
            new ConstantTarget("_logger", "logger", "\"logger\"", "String", "AllProtected", 9),
            new ConstantTarget("log", "log", "\"log\"", "String", "AllProtected", 10),
            new ConstantTarget("_log", "log", "\"log\"", "String", "AllProtected", 11),
            new ConstantTarget("REMOVABLE", "removable", "\"yes\"", "String", "AllProtected", 12)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        long skipped = results.stream()
            .filter(e -> e.status() == TransformationEntry.Status.SKIPPED)
            .count();
        long transformed = results.stream()
            .filter(e -> e.status() == TransformationEntry.Status.TRANSFORMED)
            .count();

        assertEquals(8, skipped, "All 8 protected names should be skipped");
        assertEquals(1, transformed, "Only REMOVABLE should be transformed");
    }

    // ========================================================================
    // Line tolerance tests
    // ========================================================================

    @Test
    void testLineToleranceAllowsSmallDrift() throws IOException {
        String source = """
            package com.example;

            public class DriftTest {
                public static final String DB_HOST = "localhost";
            }
            """;

        Path javaFile = writeSource("DriftTest.java", source);

        // The constant is on line 4, but we say it's on line 6 (drift of 2, within tolerance of 3)
        ConstantTarget target = new ConstantTarget(
            "DB_HOST", "db.host", "\"localhost\"", "String", "DriftTest", 6
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, List.of(target), backupsDir);

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.TRANSFORMED, results.getFirst().status());
    }

    @Test
    void testLineToleranceRejectsLargeDrift() throws IOException {
        String source = """
            package com.example;

            public class LargeDriftTest {
                public static final String DB_HOST = "localhost";
            }
            """;

        Path javaFile = writeSource("LargeDriftTest.java", source);

        // The constant is on line 4, but we say it's on line 20 (drift of 16, exceeds tolerance of 3)
        ConstantTarget target = new ConstantTarget(
            "DB_HOST", "db.host", "\"localhost\"", "String", "LargeDriftTest", 20
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, List.of(target), backupsDir);

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.SKIPPED, results.getFirst().status());
        assertTrue(results.getFirst().message().contains("Line mismatch"));
    }

    @Test
    void testLineToleranceAcceptsZeroLineNumber() throws IOException {
        String source = """
            package com.example;

            public class ZeroLineTest {
                public static final String DB_HOST = "localhost";
            }
            """;

        Path javaFile = writeSource("ZeroLineTest.java", source);

        // lineNumber of 0 means "unknown" — tolerance check should be skipped
        ConstantTarget target = new ConstantTarget(
            "DB_HOST", "db.host", "\"localhost\"", "String", "ZeroLineTest", 0
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, List.of(target), backupsDir);

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.TRANSFORMED, results.getFirst().status());
    }

    // ========================================================================
    // Multi-variable declaration tests
    // ========================================================================

    @Test
    void testMultiVariableDeclarationRemovesAllVariables() throws IOException {
        String source = """
            package com.example;

            public class MultiVarAll {
                public static final int A = 1, B = 2, C = 3;
            }
            """;

        Path javaFile = writeSource("MultiVarAll.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("A", "a", "1", "int", "MultiVarAll", 4),
            new ConstantTarget("B", "b", "2", "int", "MultiVarAll", 4),
            new ConstantTarget("C", "c", "3", "int", "MultiVarAll", 4)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        long transformed = results.stream()
            .filter(e -> e.status() == TransformationEntry.Status.TRANSFORMED)
            .count();
        assertEquals(3, transformed);

        String modified = Files.readString(outputPath(javaFile));
        assertFalse(modified.contains("A =") || modified.contains("A="));
        assertFalse(modified.contains("B =") || modified.contains("B="));
        assertFalse(modified.contains("C =") || modified.contains("C="));
    }

    @Test
    void testMultiVariableDeclarationPartialRemoval() throws IOException {
        String source = """
            package com.example;

            public class MultiVarPartial {
                public static final int FIRST = 1, SECOND = 2, THIRD = 3;
            }
            """;

        Path javaFile = writeSource("MultiVarPartial.java", source);

        // Remove only FIRST and THIRD, keep SECOND
        List<ConstantTarget> targets = List.of(
            new ConstantTarget("FIRST", "first", "1", "int", "MultiVarPartial", 4),
            new ConstantTarget("THIRD", "third", "3", "int", "MultiVarPartial", 4)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(e -> e.status() == TransformationEntry.Status.TRANSFORMED));

        String modified = Files.readString(outputPath(javaFile));
        assertTrue(modified.contains("SECOND"), "SECOND should remain");
    }

    // ========================================================================
    // Annotation and complex source tests
    // ========================================================================

    @Test
    void testConstantUsedInAnnotationNotRemovedIfOnlyTargetedByRemover() throws IOException {
        // This tests the scenario discovered in Singer.java:
        // Constants used in annotations within the same class should still
        // compile after removal IF they aren't actually targeted.
        // The remover itself doesn't verify compilation — it trusts the target list.
        // This test verifies the remover correctly removes what it's told to remove.
        String source = """
            package com.example;

            public class NamedEntity {
                public static final String FIND_BY_ID = "Entity.findById";
                public static final String UNUSED_CONST = "unused";

                public void query() {
                    String q = FIND_BY_ID;
                }
            }
            """;

        Path javaFile = writeSource("NamedEntity.java", source);

        // Only target UNUSED_CONST, not FIND_BY_ID
        ConstantTarget target = new ConstantTarget(
            "UNUSED_CONST", "unused.const", "\"unused\"", "String", "NamedEntity", 5
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, List.of(target), backupsDir);

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.TRANSFORMED, results.getFirst().status());

        String modified = Files.readString(outputPath(javaFile));
        assertTrue(modified.contains("FIND_BY_ID"), "FIND_BY_ID should remain untouched");
        assertFalse(modified.contains("UNUSED_CONST"), "UNUSED_CONST should be removed");
    }

    @Test
    void testDoesNotRemoveEnumConstants() throws IOException {
        // Enum values are static final fields in bytecode, but the remover
        // should only process targets it's given. This verifies the AST filter
        // only matches explicit static final field declarations.
        String source = """
            package com.example;

            public class WithEnum {
                public enum Status { ACTIVE, INACTIVE }
                public static final String UNUSED = "value";
            }
            """;

        Path javaFile = writeSource("WithEnum.java", source);

        ConstantTarget target = new ConstantTarget(
            "UNUSED", "unused", "\"value\"", "String", "WithEnum", 5
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, List.of(target), backupsDir);

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.TRANSFORMED, results.getFirst().status());

        String modified = Files.readString(outputPath(javaFile));
        assertTrue(modified.contains("ACTIVE"), "Enum values should be untouched");
        assertTrue(modified.contains("INACTIVE"), "Enum values should be untouched");
        assertFalse(modified.contains("UNUSED"), "UNUSED should be removed");
    }

    // ========================================================================
    // Edge case tests
    // ========================================================================

    @Test
    void testHandlesFileWithSyntaxErrors() throws IOException {
        String source = """
            package com.example;

            public class Broken {
                public static final String VALUE = "test"  // missing semicolon
            }
            """;

        Path javaFile = writeSource("Broken.java", source);

        ConstantTarget target = new ConstantTarget(
            "VALUE", "value", "\"test\"", "String", "Broken", 4
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, List.of(target), backupsDir);

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.FAILED, results.getFirst().status());
        assertTrue(results.getFirst().message().contains("could not be parsed"));
    }

    @Test
    void testHandlesNonexistentFile() {
        Path javaFile = tempDir.resolve("DoesNotExist.java");

        ConstantTarget target = new ConstantTarget(
            "CONST", "const", "\"value\"", "String", "DoesNotExist", 1
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, List.of(target), backupsDir);

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.FAILED, results.getFirst().status());
    }

    @Test
    void testDuplicateTargetNamesKeepsFirst() throws IOException {
        String source = """
            package com.example;

            public class Duplicate {
                public static final String HOST = "localhost";
            }
            """;

        Path javaFile = writeSource("Duplicate.java", source);

        // Two targets with the same name but different line numbers
        List<ConstantTarget> targets = List.of(
            new ConstantTarget("HOST", "host.first", "\"localhost\"", "String", "Duplicate", 4),
            new ConstantTarget("HOST", "host.second", "\"localhost\"", "String", "Duplicate", 4)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        // The toMap merge function keeps the first, so only one result
        long transformed = results.stream()
            .filter(e -> e.status() == TransformationEntry.Status.TRANSFORMED)
            .count();
        assertEquals(1, transformed);
    }

    @Test
    void testRemovesConstantWithComplexInitializer() throws IOException {
        String source = """
            package com.example;

            public class ComplexInit {
                public static final String PATTERN =
                    "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\\\.[a-zA-Z]{2,6}$";
                public static final int TIMEOUT = 30 * 1000;
                public static final String COMBINED = "prefix" + "_" + "suffix";
            }
            """;

        Path javaFile = writeSource("ComplexInit.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("PATTERN", "pattern", "regex", "String", "ComplexInit", 4),
            new ConstantTarget("TIMEOUT", "timeout", "30000", "int", "ComplexInit", 6),
            new ConstantTarget("COMBINED", "combined", "prefix_suffix", "String", "ComplexInit", 7)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        long transformed = results.stream()
            .filter(e -> e.status() == TransformationEntry.Status.TRANSFORMED)
            .count();
        assertEquals(3, transformed);

        String modified = Files.readString(outputPath(javaFile));
        assertFalse(modified.contains("PATTERN"));
        assertFalse(modified.contains("TIMEOUT"));
        assertFalse(modified.contains("COMBINED"));
    }

    @Test
    void testPreservesClassStructureAfterRemoval() throws IOException {
        String source = """
            package com.example;

            import java.util.List;

            public class StructureTest {
                public static final String REMOVE_ME = "gone";

                private String name;

                public StructureTest(String name) {
                    this.name = name;
                }

                public String getName() {
                    return name;
                }
            }
            """;

        Path javaFile = writeSource("StructureTest.java", source);

        ConstantTarget target = new ConstantTarget(
            "REMOVE_ME", "remove.me", "\"gone\"", "String", "StructureTest", 6
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, List.of(target), backupsDir);

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.TRANSFORMED, results.getFirst().status());

        String modified = Files.readString(outputPath(javaFile));
        assertFalse(modified.contains("REMOVE_ME"));
        // Verify the rest of the class structure is intact
        assertTrue(modified.contains("package com.example"));
        assertTrue(modified.contains("import java.util.List"));
        assertTrue(modified.contains("class StructureTest"));
        assertTrue(modified.contains("private String name"));
        assertTrue(modified.contains("public StructureTest(String name)"));
        assertTrue(modified.contains("public String getName()"));
    }

    @Test
    void testDryRunLineToleranceRejection() throws IOException {
        String source = """
            package com.example;

            public class DryRunDrift {
                public static final String HOST = "localhost";
            }
            """;

        Path javaFile = writeSource("DryRunDrift.java", source);

        // Line 50 is far from actual line 4
        ConstantTarget target = new ConstantTarget(
            "HOST", "host", "\"localhost\"", "String", "DryRunDrift", 50
        );

        List<TransformationEntry> results = remover.dryRun(javaFile, List.of(target));

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.SKIPPED, results.getFirst().status());
        assertTrue(results.getFirst().message().contains("Line mismatch"));
    }

    @Test
    void testDryRunDeclarationNotFound() throws IOException {
        String source = """
            package com.example;

            public class DryRunMissing {
                public void noop() {}
            }
            """;

        Path javaFile = writeSource("DryRunMissing.java", source);

        ConstantTarget target = new ConstantTarget(
            "GHOST", "ghost", "\"boo\"", "String", "DryRunMissing", 4
        );

        List<TransformationEntry> results = remover.dryRun(javaFile, List.of(target));

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.SKIPPED, results.getFirst().status());
        assertTrue(results.getFirst().message().contains("not found"));
    }

    @Test
    void testDryRunEmptyTargets() {
        Path javaFile = tempDir.resolve("Empty.java");

        List<TransformationEntry> results = remover.dryRun(javaFile, List.of());

        assertTrue(results.isEmpty());
    }

    // ========================================================================
    // Visibility tests — private/protected/package-private fields are skipped
    // ========================================================================

    @Test
    void testSkipsPrivateStaticFinalFields() throws IOException {
        String source = """
            package com.example;

            public class PrivateConst {
                private static final String INTERNAL_KEY = "secret";
                public static final String PUBLIC_KEY = "visible";
            }
            """;

        Path javaFile = writeSource("PrivateConst.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("INTERNAL_KEY", "internal.key", "\"secret\"", "String", "PrivateConst", 4),
            new ConstantTarget("PUBLIC_KEY", "public.key", "\"visible\"", "String", "PrivateConst", 5)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        TransformationEntry publicEntry = results.stream()
            .filter(e -> e.constantName().equals("PUBLIC_KEY"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.TRANSFORMED, publicEntry.status());

        // INTERNAL_KEY should be skipped — reported as "not found" because the
        // remover skips non-public fields entirely (they don't match)
        TransformationEntry privateEntry = results.stream()
            .filter(e -> e.constantName().equals("INTERNAL_KEY"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.SKIPPED, privateEntry.status());
    }

    @Test
    void testSkipsProtectedStaticFinalFields() throws IOException {
        String source = """
            package com.example;

            public class ProtectedConst {
                protected static final String BASE_URL = "http://localhost";
                public static final String UNUSED = "remove";
            }
            """;

        Path javaFile = writeSource("ProtectedConst.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("BASE_URL", "base.url", "\"http://localhost\"", "String", "ProtectedConst", 4),
            new ConstantTarget("UNUSED", "unused", "\"remove\"", "String", "ProtectedConst", 5)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        TransformationEntry unusedEntry = results.stream()
            .filter(e -> e.constantName().equals("UNUSED"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.TRANSFORMED, unusedEntry.status());

        TransformationEntry protectedEntry = results.stream()
            .filter(e -> e.constantName().equals("BASE_URL"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.SKIPPED, protectedEntry.status());
    }

    @Test
    void testSkipsPackagePrivateStaticFinalFields() throws IOException {
        String source = """
            package com.example;

            public class PackageConst {
                static final int INTERNAL_TIMEOUT = 5000;
                public static final String UNUSED = "remove";
            }
            """;

        Path javaFile = writeSource("PackageConst.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("INTERNAL_TIMEOUT", "internal.timeout", "5000", "int", "PackageConst", 4),
            new ConstantTarget("UNUSED", "unused", "\"remove\"", "String", "PackageConst", 5)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        TransformationEntry unusedEntry = results.stream()
            .filter(e -> e.constantName().equals("UNUSED"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.TRANSFORMED, unusedEntry.status());

        TransformationEntry pkgEntry = results.stream()
            .filter(e -> e.constantName().equals("INTERNAL_TIMEOUT"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.SKIPPED, pkgEntry.status());
    }

    @Test
    void testDryRunSkipsPrivateFields() throws IOException {
        String source = """
            package com.example;

            public class DryRunPrivate {
                private static final String SECRET = "hidden";
                public static final String VISIBLE = "shown";
            }
            """;

        Path javaFile = writeSource("DryRunPrivate.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("SECRET", "secret", "\"hidden\"", "String", "DryRunPrivate", 4),
            new ConstantTarget("VISIBLE", "visible", "\"shown\"", "String", "DryRunPrivate", 5)
        );

        List<TransformationEntry> results = remover.dryRun(javaFile, targets);

        TransformationEntry visibleEntry = results.stream()
            .filter(e -> e.constantName().equals("VISIBLE"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.TRANSFORMED, visibleEntry.status());
        assertTrue(visibleEntry.message().contains("Would remove"));

        TransformationEntry secretEntry = results.stream()
            .filter(e -> e.constantName().equals("SECRET"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.SKIPPED, secretEntry.status());
    }

    // ========================================================================
    // Dangling reference detection tests
    // ========================================================================

    @Test
    void testRejectsRemovalWhenConstantUsedInAnnotation() throws IOException {
        // Reproduces the Singer.java bug: constants used in @NamedQuery annotations
        String source = """
            package com.example;

            public class Singer {
                public static final String FIND_BY_ID = "Singer.findById";
                public static final String FIND_ALL = "Singer.findAll";
                public static final String UNUSED = "remove_me";

                public void query() {
                    String q = FIND_BY_ID;
                    String q2 = Singer.FIND_ALL;
                }
            }
            """;

        Path javaFile = writeSource("Singer.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("FIND_BY_ID", "find.by.id", "\"Singer.findById\"", "String", "Singer", 4),
            new ConstantTarget("FIND_ALL", "find.all", "\"Singer.findAll\"", "String", "Singer", 5),
            new ConstantTarget("UNUSED", "unused", "\"remove_me\"", "String", "Singer", 6)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        // FIND_BY_ID and FIND_ALL are still referenced — should fail
        TransformationEntry findByIdEntry = results.stream()
            .filter(e -> e.constantName().equals("FIND_BY_ID"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.FAILED, findByIdEntry.status());
        assertTrue(findByIdEntry.message().contains("still referenced"));

        TransformationEntry findAllEntry = results.stream()
            .filter(e -> e.constantName().equals("FIND_ALL"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.FAILED, findAllEntry.status());

        // UNUSED has no references — should succeed
        TransformationEntry unusedEntry = results.stream()
            .filter(e -> e.constantName().equals("UNUSED"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.TRANSFORMED, unusedEntry.status());
    }

    @Test
    void testDryRunDetectsDanglingReferences() throws IOException {
        String source = """
            package com.example;

            public class DryRunDangling {
                public static final String USED_CONST = "value";

                public String get() {
                    return USED_CONST;
                }
            }
            """;

        Path javaFile = writeSource("DryRunDangling.java", source);

        ConstantTarget target = new ConstantTarget(
            "USED_CONST", "used.const", "\"value\"", "String", "DryRunDangling", 4
        );

        List<TransformationEntry> results = remover.dryRun(javaFile, List.of(target));

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.FAILED, results.getFirst().status());
        assertTrue(results.getFirst().message().contains("still referenced"));
    }

    @Test
    void testQualifiedReferenceDetected() throws IOException {
        // Tests ClassName.CONSTANT_NAME style references
        String source = """
            package com.example;

            public class QualifiedRef {
                public static final String CONFIG_KEY = "key";

                public String getKey() {
                    return QualifiedRef.CONFIG_KEY;
                }
            }
            """;

        Path javaFile = writeSource("QualifiedRef.java", source);

        ConstantTarget target = new ConstantTarget(
            "CONFIG_KEY", "config.key", "\"key\"", "String", "QualifiedRef", 4
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, List.of(target), backupsDir);

        assertEquals(1, results.size());
        assertEquals(TransformationEntry.Status.FAILED, results.getFirst().status());
        assertTrue(results.getFirst().message().contains("still referenced"));
    }

    @Test
    void testMixedSafeAndUnsafeRemovals() throws IOException {
        // One constant is referenced, another is truly unused — only the unused one is removed
        String source = """
            package com.example;

            public class MixedRemoval {
                public static final String REFERENCED = "used";
                public static final String TRULY_UNUSED = "not_used";

                public String get() {
                    return REFERENCED;
                }
            }
            """;

        Path javaFile = writeSource("MixedRemoval.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("REFERENCED", "referenced", "\"used\"", "String", "MixedRemoval", 4),
            new ConstantTarget("TRULY_UNUSED", "truly.unused", "\"not_used\"", "String", "MixedRemoval", 5)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        TransformationEntry refEntry = results.stream()
            .filter(e -> e.constantName().equals("REFERENCED"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.FAILED, refEntry.status());

        TransformationEntry unusedEntry = results.stream()
            .filter(e -> e.constantName().equals("TRULY_UNUSED"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.TRANSFORMED, unusedEntry.status());

        // Verify only TRULY_UNUSED was removed from the output file
        String modified = Files.readString(outputPath(javaFile));
        assertTrue(modified.contains("REFERENCED"), "REFERENCED should remain");
        assertFalse(modified.contains("TRULY_UNUSED"), "TRULY_UNUSED should be removed");
    }

    @Test
    void testTrulyUnusedConstantPassesDanglingCheck() throws IOException {
        String source = """
            package com.example;

            public class TrulyUnused {
                public static final String DEAD_CODE = "nobody uses this";
                public static final String ALSO_DEAD = "or this";
            }
            """;

        Path javaFile = writeSource("TrulyUnused.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("DEAD_CODE", "dead.code", "\"nobody uses this\"", "String", "TrulyUnused", 4),
            new ConstantTarget("ALSO_DEAD", "also.dead", "\"or this\"", "String", "TrulyUnused", 5)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        assertTrue(results.stream().allMatch(e -> e.status() == TransformationEntry.Status.TRANSFORMED));
        assertEquals(2, results.size());

        String modified = Files.readString(outputPath(javaFile));
        assertFalse(modified.contains("DEAD_CODE"));
        assertFalse(modified.contains("ALSO_DEAD"));
    }

    @Test
    void testProtectsVersionField() throws IOException {
        String source = """
            package com.example;

            public class Versioned {
                public static final String VERSION = "2.1.0";
                public static final String UNUSED = "remove";
            }
            """;

        Path javaFile = writeSource("Versioned.java", source);

        List<ConstantTarget> targets = List.of(
            new ConstantTarget("VERSION", "version", "\"2.1.0\"", "String", "Versioned", 4),
            new ConstantTarget("UNUSED", "unused", "\"remove\"", "String", "Versioned", 5)
        );

        List<TransformationEntry> results = remover.removeFromFile(javaFile, targets, backupsDir);

        TransformationEntry versionEntry = results.stream()
            .filter(e -> e.constantName().equals("VERSION"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.SKIPPED, versionEntry.status());

        TransformationEntry unusedEntry = results.stream()
            .filter(e -> e.constantName().equals("UNUSED"))
            .findFirst().orElseThrow();
        assertEquals(TransformationEntry.Status.TRANSFORMED, unusedEntry.status());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Path writeSource(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content);
        return file;
    }
}
