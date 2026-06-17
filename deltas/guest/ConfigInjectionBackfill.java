package com.boeing.constcatalog.tools;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public final class ConfigInjectionBackfill {

    private static final String IMPORT_SERVICE  = "com.boeing.constcatalog.backend.service.SiteConfigurationService";
    private static final String IMPORT_PROVIDER = "com.boeing.constcatalog.backend.annotation.SiteConfigurationServiceProvider";
    private static final String FIELD_TYPE      = "SiteConfigurationService";
    private static final String FIELD_NAME      = "config";

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args[0]);

        ParserConfiguration config = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(config);

        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java"))
                 .filter(ConfigInjectionBackfill::looksLikeMigrationCandidate)
                 .forEach(p -> migrate(p, parser));
        }
    }

    private static void migrate(Path path, JavaParser parser) {
        try {
            String source = Files.readString(path);
            CompilationUnit cu = parser.parse(source).getResult().orElseThrow();
            LexicalPreservingPrinter.setup(cu);                    // (1) preserve formatting

            boolean modified = false;
            for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!shouldAddField(cls)) continue;                // (2) predicate
                if (hasField(cls, FIELD_NAME))     continue;       // (3) idempotency
                addStaticField(cls);
                modified = true;
            }

            if (modified) {
                ensureImport(cu, IMPORT_SERVICE);                  // (4) imports last
                ensureImport(cu, IMPORT_PROVIDER);
                Files.writeString(path, LexicalPreservingPrinter.print(cu));
            }
        } catch (Exception e) {
            System.err.println("Failed: " + path + " — " + e.getMessage());
        }
    }

    private static boolean shouldAddField(ClassOrInterfaceDeclaration cls) {
        return !cls.isInterface()
            && !cls.isInnerClass()                                  // skip inner — usually wrong target
            && !cls.isLocalClassDeclaration();
    }

    private static boolean hasField(ClassOrInterfaceDeclaration cls, String name) {
        return cls.getFields().stream()
            .flatMap(f -> f.getVariables().stream())
            .anyMatch(v -> v.getNameAsString().equals(name));
    }

    private static void addStaticField(ClassOrInterfaceDeclaration cls) {
        FieldDeclaration field = cls.addField(
            FIELD_TYPE, FIELD_NAME,
            Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);
        field.getVariable(0).setInitializer("SiteConfigurationServiceProvider.get()");
    }

    private static void ensureImport(CompilationUnit cu, String fqcn) {
        boolean already = cu.getImports().stream()
            .anyMatch(i -> !i.isAsterisk()
                        && !i.isStatic()
                        && i.getNameAsString().equals(fqcn));
        if (!already) cu.addImport(fqcn);
    }

    private static boolean looksLikeMigrationCandidate(Path path) {
        // YOUR criterion for which files get the injected field.
        // For a constants-catalog-driven migration, the typical answer is:
        // "every file that has at least one public static final constant the
        // catalog has flagged for replacement." Read that list from a CSV /
        // JSON manifest, or query Neo4j, and check membership here.
        // For a blanket inject-everywhere first pass, return true.
        return true;
    }
}
