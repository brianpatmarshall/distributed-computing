package com.boeing.constcatalog.transformer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;

import java.util.List;
import java.util.Optional;

/**
 * Resolves whether a bare name in a compilation unit refers to a statically
 * imported member, and — when possible — returns the fully qualified owner class.
 *
 * <p>Without a symbol solver, wildcard static imports ({@code import static Foo.*})
 * are ambiguous: we can report the owner class but cannot prove the name came from it.
 */
public final class StaticImportResolver {

    private final List<ImportDeclaration> staticImports;

    public StaticImportResolver(CompilationUnit cu) {
        this.staticImports = cu.getImports().stream()
            .filter(ImportDeclaration::isStatic)
            .toList();
    }

    /** True if {@code name} matches a single-member static import or a wildcard import. */
    public boolean isStaticallyImported(String name) {
        return findOwner(name).isPresent();
    }

    /**
     * Returns the FQN of the owning class if {@code name} resolves to a static import.
     * For single-member imports this is exact. For wildcard imports, returns the
     * first wildcard owner seen (best-effort without a symbol solver).
     */
    public Optional<String> findOwner(String name) {
        for (ImportDeclaration imp : staticImports) {
            String importName = imp.getNameAsString();
            if (imp.isAsterisk()) {
                return Optional.of(importName);
            }
            int dot = importName.lastIndexOf('.');
            String member = dot >= 0 ? importName.substring(dot + 1) : importName;
            if (member.equals(name)) {
                return Optional.of(dot >= 0 ? importName.substring(0, dot) : "");
            }
        }
        return Optional.empty();
    }
}
