# Config-Injection Backfill Guide

Mass-migrating an existing Java project to add static access to
`SiteConfigurationService`, as the prep step before replacing hardcoded
`public static final` constants with runtime config lookups. Covers the
tooling decision (annotation processor vs JavaParser vs OpenRewrite vs
bytecode), a design decision about *what* to add to each file, and a
practical JavaParser-based implementation with the gotchas you'll hit
on a large run.

The scenario this guide is written against: a generic (probably
non-Spring) Java codebase being scanned by the const-catalog. The
catalog has identified the constants worth externalizing; we now need
the lookup service to be reachable from the call sites that will be
rewritten. This guide is about the one-shot injection that lands the
field everywhere; the constant-to-lookup rewrite is a separate pass
(see `ConfigLookupTransformer` in `const-catalog-core`).

## Table of Contents

1.  Picking the right tool
2.  Design decision: static-provider field or Spring `@Autowired`?
3.  The JavaParser approach --- overall shape
4.  `LexicalPreservingPrinter` --- the difference between works and
    useless
5.  The predicate is the actual hard part
6.  Idempotency
7.  Import handling
8.  Do you need the symbol solver?
9.  OpenRewrite --- the higher-level alternative
10. A sanity-check pipeline
11. A nudge on the field signature

## 1. Picking the right tool

Different tools solve different parts of the lifecycle. The line
between them is sharp:

| Task                                                         | Right tool                                                 | Why                                                                                                                            |
| ------------------------------------------------------------ | ---------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| New code adopting config lookups going forward               | Hand-written field or `@SiteConfig` / `@ConfigLookup` (Spring-only) | The annotation only works in a Spring context. New code that's already Spring can use it; new generic-Java code writes the field directly. |
| **Existing code, one-shot injection across many files**      | **JavaParser (or OpenRewrite)**                            | You need to *mutate existing files*. Annotation processors can't do this — JSR 269 only generates new files.                   |
| Runtime weaving                                              | Bytecode instrumentation (ASM, ByteBuddy)                  | If you couldn't touch source at all — e.g., third-party JARs you don't own. Overkill if you have the source.                   |

Two important framing points:

- **This tool is the prep step, not the rewrite.** It only injects a
  field. The const-catalog's existing `ConfigLookupTransformer`
  rewrites constant accesses (`MAX_RETRIES`) into calls
  (`config.lookupInt("max.retries", 3)`). The transformer assumes a
  field called `config` of type `SiteConfigurationService` is in
  scope; this tool puts it there.
- **The const-catalog's transformer already injects the field — in
  Spring form.** Look at
  `ConfigLookupTransformer.addConfigFieldIfMissing` in
  `const-catalog-core`: it adds `@Autowired private
  SiteConfigurationService config;`. That's the right injection for a
  Spring project. For a non-Spring project, you want the static-provider
  form below. The two patterns are mutually exclusive in a given file —
  pick one per project.

## 2. Design decision: static-provider field or Spring `@Autowired`?

Before writing the migration, decide between two end states.

### Option A --- Spring DI: `@Autowired` instance field

    @Service
    public class CheckoutService {
        @Autowired private SiteConfigurationService config;
        // ...
    }

Spring wires the field at startup. Calls use `config.lookup(...)`.
This is what `ConfigLookupTransformer.addConfigFieldIfMissing` already
does. Pros: idiomatic in a Spring app; one bean, one canonical
lifecycle. Cons: only works in Spring-managed classes. Static
initializers and utility classes can't use it. Tests need a Spring
context (or a constructor variant).

### Option B --- Static-provider: `public static final` field

    public class CheckoutService {
        public static final SiteConfigurationService config =
                SiteConfigurationServiceProvider.get();
        // ...
    }

The field is initialized once when the class loads, via a static
provider that returns the singleton. Calls use `config.lookup(...)`
exactly the same way at the call site. Pros: works in any class,
including static contexts; no DI framework required; legal in
utility/static-method classes that are common targets for constants.
Cons: relies on the provider being initialized before any of these
classes are touched (in a Spring app the `@PostConstruct` on
`SiteConfigurationServiceProvider` handles this; in a non-Spring app
you need to call its initializer explicitly at startup).

### Trade-offs

|                          | **Option A** (`@Autowired`)                                | **Option B** (static-provider)                          |
| ------------------------ | ---------------------------------------------------------- | ------------------------------------------------------- |
| Diff per file            | 1 field + 2 imports + 1 annotation                         | 1 field + 2 imports                                     |
| Works outside Spring     | No                                                         | Yes                                                     |
| Access from static ctx   | No (instance field)                                        | Yes (the field is itself static)                        |
| Initialization order     | Spring guarantees beans before app code                    | Provider must be initialized before first class-load    |
| Test ergonomics          | Needs `@MockBean` or constructor override                  | Test sets the provider via a setter or a test-only init |
| Idempotency of migration | Easy --- check whether field exists                        | Easy --- check whether field exists                     |
| Long-term cleanliness    | Matches Spring conventions                                 | Looks like FeatureToggle's `featureService` --- mirror imagery |

A scanned generic Java project is almost always **Option B**, because
the project may have no Spring context at all. A scanned Spring app
could go either way, but Option A is the more idiomatic answer for that
case and `ConfigLookupTransformer` already does it. **The rest of this
guide assumes Option B**, since the user's scenario is generic Java and
that's where this tool adds value.

If you're migrating a mixed codebase, the most pragmatic plan is to
classify each file (does its top-level class look Spring-managed —
`@Component`, `@Service`, `@RestController`, etc.) and pick the
injection form per file. The split is a function of the *target file*,
not a global choice.

## 3. The JavaParser approach --- overall shape

A sketch of the migration tool. This is the structure to hang real
logic off; error handling, file filtering, and the "which classes get
this?" predicate are the parts you'll customize.

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
            // YOUR criterion for the subset of files that need the field.
            // E.g., file appears in a manifest of "files with replaceable constants",
            // sits under a specific package, matches a Neo4j query result, etc.
            return true;
        }
    }

The numbered points each have their own section below.

## 4. `LexicalPreservingPrinter` --- the difference between works and useless

JavaParser's default printer (`cu.toString()`) **reformats the entire
file**:

-   Loses your indentation.
-   Moves comments around.
-   Drops blank lines.
-   Sometimes rewrites Javadoc layout.
-   Standardizes brace style.

For a multi-file migration where the diffs need to be reviewable,
that's a non-starter. The diff is supposed to show *your* change, not
"and also the whole file got reformatted."

`LexicalPreservingPrinter.setup(cu)` attaches a metadata layer that
tracks the original tokens. Then `LexicalPreservingPrinter.print(cu)`
emits source where only your edits show as diffs; everything else
round-trips byte-for-byte.

> **Note about the existing `ConfigLookupTransformer`.** It calls
> `cu.toString()` rather than `LexicalPreservingPrinter.print(cu)` (see
> `writeModifiedSource` in
> `const-catalog-core/src/main/java/.../ConfigLookupTransformer.java`),
> which is why running it against an externally-formatted project
> reformats every touched file. The injection backfill described here
> is more diff-friendly because it goes through
> `LexicalPreservingPrinter`. If you eventually unify the two passes,
> migrate the transformer to the preserving printer first; otherwise
> the constants-replacement step will undo the reviewable-diff property
> the injection step worked to keep.

### Caveats

-   **Rough edges.** Interactions between multiple edits on the same
    node sometimes produce odd whitespace. Test on a representative
    subset of your files first and eyeball the diffs before letting the
    tool loose on the whole tree.
-   **Workarounds when it misbehaves:** do edits in smaller, more
    focused steps (one field at a time, re-parse between edits), or
    fall back to a text-based insertion for files where the AST
    manipulation produces bad output.
-   **Line endings.** On a mixed Windows/Unix codebase, watch for
    `\r\n` vs `\n` drift in the output. `LexicalPreservingPrinter`
    generally preserves whatever the input had, but combine it with
    `Files.readString` / `Files.writeString` and you'll occasionally
    see a stray normalization. If you control the codebase, ensure a
    consistent line-ending policy first.

## 5. The predicate is the actual hard part

The skeleton above skips interfaces, inner classes, and local classes.
That's the easy part. The real decisions:

-   **Records and enums?** Records can't have non-static *instance*
    fields, but they *can* have static fields --- so adding
    `public static final SiteConfigurationService config` to a record
    is legal. Enums similarly. Decide whether your migration covers
    them.
-   **Abstract classes?** Usually yes --- the static field is shared
    with every subclass anyway and is independent of instantiation.
-   **Inner classes?** Usually no. The outer class's `config` is
    already in scope from inner classes. Adding it to both is redundant
    (and you'd need `public static final` in a non-static inner class,
    which historically wasn't allowed --- modern Java accepts it but
    it's a smell).
-   **Generated files?** Skip anything with `@Generated` or living
    under `target/generated-sources/`. Your migration tool should never
    touch generator output, since the next build regenerates it
    without your changes.
-   **Test sources?** If `src/test/java/` has constants the catalog
    wants to externalize, include it; otherwise exclude. Tests are
    often a good place to *prove* the injection works (write a small
    test that reads `config.lookup(...)` in a known-good module).
-   **Files with no candidate constants.** Injecting `config` into a
    file that has no constants the catalog is going to rewrite is dead
    code --- legal, but a useless diff. The cleanest predicate is the
    manifest of files the rewrite step will actually touch (CSV from a
    Neo4j query like *"every file that owns at least one constant
    flagged for externalization"*). Computing that beforehand makes the
    injection diff exactly mirror the rewrite diff.
-   **Package-restricted classes (**`final`**, package-private utility
    classes)?** Usually yes; the modifier doesn't affect whether the
    field makes sense.

The predicate is also where most migration bugs hide. Spend time on
it. Print a candidate count before running the actual modification;
verify by eye that the number matches expectations.

## 6. Idempotency

Your migration may not be a single atomic run. You'll likely:

-   Run on a subset, review diffs, find a bug.
-   Fix the bug.
-   Run on the same files plus more files.

If the second run adds a *second* `config` field to classes that
already got it, you'll get compile errors. The `hasField()` check keeps
the tool idempotent.

You could also add a cheap pre-parse short-circuit:

    if (source.contains("static final SiteConfigurationService config")) {
        return;  // probably already migrated; skip parse cost
    }

This is a heuristic, not a guarantee --- a comment containing that
string would also skip the file. But on a large run, it saves real
parse time for the files that are obviously already done.

A deeper form of idempotency worth thinking about: **what if your team
hand-edits one of the migrated files later?** The migration runs again
(say, against a different scan). The hand-edit gets preserved by
`LexicalPreservingPrinter`, but the predicate `hasField` will see the
existing field and skip the class --- even if its initializer is now
subtly different from what the migration would produce. Decide whether
the migration's role is "make sure the field exists" or "make sure the
field matches a canonical form." The former is friendlier; the latter
is more opinionated.

A related concern that only applies here: if the const-catalog later
adds `ConfigLookupTransformer.addConfigFieldIfMissing` *to the same
file*, you'll have two `config` fields with different modifiers
(`public static final` vs `@Autowired private`). Run only one of the
two field-injection paths per project. Easiest way: set a project-wide
flag (Spring-mode vs static-mode) and have the transformer pick the
matching one.

## 7. Import handling

`cu.addImport(fqcn)` adds the import if absent. The explicit check in
the skeleton above is belt-and-braces --- `addImport` is internally
idempotent in modern JavaParser versions, but being explicit means you
don't depend on that.

What `addImport` does *not* do:

-   **Resolve conflicts** with an existing
    `import com.somewhere.SiteConfigurationService;` from a different
    package. You'd get two imports (a compile error), or the one
    already present takes precedence (your migration silently uses the
    wrong class).
-   **Detect coverage via wildcards.**
    `import com.boeing.constcatalog.backend.service.*;` already brings
    in `SiteConfigurationService`, but `cu.getImports()` won't tell you
    that unless you look at the wildcard imports explicitly.

If your codebase uses wildcard imports, you'll want the
`JavaSymbolSolver` to actually resolve symbols. But for a one-shot
migration where you control both sides, an FQCN-equality check is
usually enough --- and if it's not, you find out at compile time.

A simple defensive enhancement:

    private static boolean ensureImport(CompilationUnit cu, String fqcn) {
        String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);

        for (ImportDeclaration i : cu.getImports()) {
            if (!i.isStatic() && !i.isAsterisk()
                    && i.getNameAsString().equals(fqcn)) return false;

            if (!i.isStatic() && !i.isAsterisk()
                    && i.getName().getIdentifier().equals(simpleName)
                    && !i.getNameAsString().equals(fqcn)) {
                throw new IllegalStateException("Name conflict on " + simpleName);
            }
        }
        cu.addImport(fqcn);
        return true;
    }

Now the tool blows up loudly on a file where another class also called
`SiteConfigurationService` is already imported, instead of silently
producing broken code.

## 8. Do you need the symbol solver?

For *this specific transformation* (add an import, add a field),
**no.** The transformation is structural --- you don't need to know
what `config` resolves to in any given file. You're just inserting
tokens.

You'd reach for `JavaSymbolSolver` when:

-   You need to find files where a specific class is referenced (e.g.,
    "every file that uses one of the catalog's tagged constants"). For
    that lookup, the catalog's Neo4j graph is usually a better source
    than the symbol solver --- it already has the answer.
-   You need to verify a method call resolves to a specific overload
    (relevant for the *rewrite* step, not the injection step).
-   You need to know whether an identifier is shadowed.

For the injection migration as described, **skip it.** It adds setup
complexity (configuring source roots, classpath, JAR roots) and
significant parse-time cost. JavaParser without the symbol solver runs
at thousands of files per minute; with it, you're in the hundreds,
sometimes the tens.

If you find yourself reaching for the symbol solver, that's usually a
signal that the transformation is getting too clever for a one-shot
migration and OpenRewrite might be a better fit (see next section).

## 9. OpenRewrite --- the higher-level alternative

There's a tool purpose-built for this category of task: **OpenRewrite**
from Moderne. It's a higher-level alternative to JavaParser for
source-to-source refactoring, with several advantages for migrations
of this shape:

-   **Built-in recipes** for `AddImport`, `AddField`, `FindUsages`,
    `ChangeMethodName`, etc. You compose them rather than writing AST
    manipulation by hand.
-   **Round-trip fidelity is its design center** --- generally cleaner
    than JavaParser's `LexicalPreservingPrinter`, because the LST
    (Lossless Semantic Tree) was built from the ground up around the
    invariant that unchanged code emits byte-identical output.
-   **Maven/Gradle integration**: `mvn rewrite:run` applies recipes to
    your project. No need to write a separate `main()` to wire
    everything.
-   **Recipes are reusable artifacts** --- write one, run it on this
    codebase today, run it on another codebase next quarter, or publish
    it for the community.

### Trade-offs

-   **More to learn.** Recipes, visitors, the LST model --- it's a
    small but real conceptual surface area.
-   **More dependencies.** Heavier toolkit than JavaParser; significant
    classpath additions.
-   **The ecosystem skews to "common refactorings".** Niche
    transformations sometimes need you to drop down to the visitor API
    anyway.

### When to pick which

-   **JavaParser** for a one-shot where you'll write the migration
    logic once and throw it away. Less ceremony; you control
    everything. The code in §3 is most of what you need.
-   **OpenRewrite** for a team that's going to do many such migrations
    --- deprecation campaigns, library upgrades, security backfills.
    The upfront investment pays off across the second, third, and
    fourth migration.

For a one-shot config-injection across a scanned project,
**JavaParser is probably the right call.**

## 10. A sanity-check pipeline

For a multi-thousand file project, run the migration in escalating
stages:

1.  **Dry run on a single file.** Pick a representative one. Run the
    tool with output to stdout instead of writing to disk. Eyeball the
    diff.
2.  **Dry run on 10 files.** Make sure `LexicalPreservingPrinter`
    doesn't mangle anything weird --- string templates with `\n`,
    files with `\r\n` line endings, files using tabs, files with
    unusual annotation placement.
3.  **Apply to a single package.** Commit to a feature branch. Run
    `mvn compile` (or `gradle compileJava`). This catches:
    -   Import conflicts (two classes with the same simple name).
    -   Name collisions (a field already called `config` of a different
        type).
    -   Files that parsed successfully but produced invalid output
        (rare, but happens --- JavaParser can accept some edge cases
        that `javac` rejects).
4.  **Apply to all candidates.** Single commit, big diff, review by
    directory.
5.  **Compile, run the full test suite.**

The "compile after applying" step usually catches the small number of
files where the migration goes wrong. Those are then either fixed
manually or by refining the predicate and re-running.

### Logging to plan for

The migration tool should log enough that you can audit it after the
fact:

-   **Files considered** (the result of
    `looksLikeMigrationCandidate`).
-   **Files modified** (the ones that actually got the field).
-   **Files skipped because already migrated** (the idempotency path).
-   **Files that failed to parse or threw.**

A summary line at the end ("Modified 873 of 900 candidates; 27 skipped
as already-migrated; 0 errors") gives you the confidence to merge.

## 11. A nudge on the field signature

    public static final SiteConfigurationService config =
            SiteConfigurationServiceProvider.get();

That `get()` (no args) returns the singleton initialized by
`SiteConfigurationServiceProvider` (via Spring `@PostConstruct` in a
Spring host, or a manual init call in a non-Spring host). That's fine
for a uniform migration. Three things worth being deliberate about:

-   **Field name `config` is load-bearing.** The const-catalog's
    `ConfigLookupTransformer` emits calls of the form
    `config.lookup(...)`, `config.lookupInt(...)`, etc. — see
    `CONFIG_FIELD_NAME = "config"` in
    `const-catalog-core/src/main/java/.../ConfigLookupTransformer.java`.
    If you rename the field, you must rename the constant in the
    transformer too, otherwise the rewrite emits dangling references.
-   **Initialization order in non-Spring hosts.** If the host app has
    no Spring context, `SiteConfigurationServiceProvider` has to be
    initialized explicitly at startup --- before any class with a
    `public static final SiteConfigurationService config = ...` field
    is touched. The provider's `init(SiteConfigurationService)` setter
    (or whatever the equivalent is in your wiring) needs to run first.
    Class-loading is lazy, so as long as nothing references a migrated
    class before init, you're fine; but the moment something does, you
    get an `IllegalStateException` from the provider. A standard
    pattern: have the host app's `main()` call the provider's
    initializer as its first line.
-   **Hardcoding alternatives.** Don't be tempted by:
    -   `new SiteConfigurationService(...)` directly --- bypasses
        caching, defeats the point.
    -   `SiteConfigurationServiceProvider.get("Production")` (if such
        an overload existed) --- couples the class to one environment.
    -   `SiteConfigurationServiceProvider.getInstance().withCache(...)`
        --- field initializers should be one-liners; if you need
        configuration, do it inside the provider.

The plain `SiteConfigurationServiceProvider.get()` form is the right
default. The provider is the single point where environment or test
overrides should live, and the field shape stays uniform across every
migrated file --- which keeps the migration diff reviewable and the
rewrite step (which knows nothing about the initializer) correct.

*End of guide.*
