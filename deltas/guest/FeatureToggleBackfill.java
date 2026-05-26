package com.esc.provider;

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

public final class FeatureToggleBackfill {

    private static final String IMPORT_SERVICE  = "com.esc.service.FeatureToggleService";
    private static final String IMPORT_PROVIDER = "com.esc.provider.FeatureToggleServiceProvider";
    private static final String FIELD_NAME      = "featureService";

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args[0]);

        ParserConfiguration config = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(config);

        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java"))
                 .filter(FeatureToggleBackfill::looksLikeMigrationCandidate)
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
            "FeatureToggleService", FIELD_NAME,
            Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);
        field.getVariable(0).setInitializer("FeatureToggleServiceProvider.get()");
    }

    private static void ensureImport(CompilationUnit cu, String fqcn) {
        boolean already = cu.getImports().stream()
            .anyMatch(i -> !i.isAsterisk()
                        && !i.isStatic()
                        && i.getNameAsString().equals(fqcn));
        if (!already) cu.addImport(fqcn);
    }

    private static boolean looksLikeMigrationCandidate(Path path) {
        // YOUR criterion for the 1000 vs 7000 split.
        // E.g., contains a "FEATURE_" string constant, sits under a specific
        // package, matches an entry in a CSV file, has a particular interface, etc.
        return true;
    }
}