package com.boeing.constcatalog.backend.service;

import com.boeing.constcatalog.backend.dto.TransformRequest;
import com.boeing.constcatalog.backend.dto.TransformRequest.CandidateScope;
import com.boeing.constcatalog.backend.dto.TransformResponse;
import com.boeing.constcatalog.model.ConstantTarget;
import com.boeing.constcatalog.model.TransformationEntry;
import com.boeing.constcatalog.model.TransformationResult;
import com.boeing.constcatalog.transformer.ConfigLookupTransformer;
import com.boeing.constcatalog.transformer.MusketeerLog;
import com.boeing.constcatalog.transformer.UnusedConstantRemover;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates the constant/parameter-to-lookup transformation by:
 * <ol>
 *   <li>Querying Neo4j for configuration-candidate constants and parameter usages</li>
 *   <li>Grouping targets by source file</li>
 *   <li>Delegating to {@link ConfigLookupTransformer} for AST modification</li>
 *   <li>Returning an aggregated result</li>
 * </ol>
 */
@Service
@Slf4j
public class CodeTransformationService {

    private final Neo4jClient neo4jClient;
    private final ConfigLookupTransformer transformer;
    private final UnusedConstantRemover unusedRemover;
    private final GitService gitService;
    private final SiteConfigurationService configService;

    public CodeTransformationService(Neo4jClient neo4jClient, GitService gitService,
                                     SiteConfigurationService configService) {
        this.neo4jClient = neo4jClient;
        this.transformer = new ConfigLookupTransformer();
        this.unusedRemover = new UnusedConstantRemover();
        this.gitService = gitService;
        this.configService = configService;
    }

    /**
     * Transforms configuration-candidate constants and/or parameter usages in a scanned project.
     * Pulls data from Neo4j, then uses ConfigLookupTransformer to replace usages with
     * config.lookupType() calls.
     */
    public TransformResponse transform(TransformRequest request) {
        String projectPath = request.getProjectPath();
        CandidateScope scope = request.getCandidateScope();
        log.info("Starting transformation for project: {} (dryRun={}, scope={})",
                 projectPath, request.isDryRun(), scope);

        if (scope == CandidateScope.REMOVE_UNUSED) {
            return handleRemoveUnused(request);
        }

        try {
            // Phase 1: Query Neo4j for candidates based on scope
            Map<Path, List<ConstantTarget>> targetsByFile = new HashMap<>();

            if (scope != CandidateScope.PARAMETERS_ONLY) {
                List<ConstantWithFile> constantCandidates = findConfigurationCandidates(projectPath);
                log.info("Found {} constant candidates", constantCandidates.size());

                // Deduplicate by constant name (same constant may appear in multiple definition files)
                List<ConstantTarget> constantTargets = constantCandidates.stream()
                    .map(ConstantWithFile::toTarget)
                    .collect(Collectors.toMap(ConstantTarget::constantName, t -> t, (a, b) -> a))
                    .values().stream().toList();

                if (!constantTargets.isEmpty()) {
                    // Assign constant targets to ALL Java files in the project so the
                    // transformer can find usages wherever they occur, not just in the
                    // definition file.
                    List<String> javaFiles = findProjectJavaFiles(projectPath);
                    for (String javaFile : javaFiles) {
                        targetsByFile.computeIfAbsent(Paths.get(javaFile), k -> new ArrayList<>())
                            .addAll(constantTargets);
                    }
                }
            }

            if (scope != CandidateScope.CONSTANTS_ONLY) {
                List<ParameterWithFile> paramCandidates = findParameterCandidates(projectPath);
                log.info("Found {} parameter candidates", paramCandidates.size());
                for (ParameterWithFile p : paramCandidates) {
                    targetsByFile.computeIfAbsent(Paths.get(p.filePath()), k -> new ArrayList<>())
                        .add(p.toTarget());
                }
            }

            // Apply optional file filter
            applyFileFilter(targetsByFile, request.getFileFilter());

            if (targetsByFile.isEmpty()) {
                return TransformResponse.builder()
                    .success(true)
                    .projectPath(projectPath)
                    .transformedAt(LocalDateTime.now())
                    .dryRun(request.isDryRun())
                    .message("No configuration candidates found for this project")
                    .totalTransformed(0)
                    .totalSkipped(0)
                    .totalFailed(0)
                    .entries(List.of())
                    .build();
            }

            int totalCandidates = targetsByFile.values().stream().mapToInt(List::size).sum();
            log.info("Found {} total candidates across {} files", totalCandidates, targetsByFile.size());

            // Phase 2: Transform (dry-run, git branch, or backup-based)
            TransformationResult result;
            BranchResult branchResult = BranchResult.SKIPPED;

            if (request.isDryRun()) {
                List<TransformationEntry> entries = targetsByFile.entrySet().stream()
                    .flatMap(entry -> transformer.dryRun(entry.getKey(), entry.getValue()).stream())
                    .toList();
                result = new TransformationResult(projectPath, LocalDateTime.now(), entries);
            } else if (request.isCreateBranch()) {
                // Live run with git: create branch, transform in place, commit, switch back
                branchResult = transformOnBranch(projectPath, targetsByFile, false);
                if (branchResult.result() != null) {
                    result = branchResult.result();
                } else {
                    // Branch creation failed — fall back to backup-based transform
                    log.warn("Git branch creation failed, falling back to backup-based transform: {}",
                        branchResult.error());
                    result = transformer.transformProject(projectPath, targetsByFile);
                }
            } else {
                // Live run without git: backup originals, transform in place
                result = transformer.transformProject(projectPath, targetsByFile);
            }

            // Phase 3: Build response
            List<TransformResponse.EntryDto> entryDtos = result.entries().stream()
                .map(TransformResponse.EntryDto::from)
                .toList();

            String summary = "Transformed %d items, skipped %d, failed %d"
                .formatted(result.totalTransformed(), result.totalSkipped(), result.totalFailed());

            String message;
            if (request.isDryRun()) {
                message = "Dry run complete: %d would be transformed, %d skipped, %d failed"
                    .formatted(result.totalTransformed(), result.totalSkipped(), result.totalFailed());
            } else if (branchResult.branchName() != null) {
                message = summary + " — committed to branch '%s'".formatted(branchResult.branchName());
            } else if (branchResult.error() != null) {
                message = summary + " (git branch failed: %s)".formatted(branchResult.error());
            } else {
                message = summary;
            }

            TransformResponse response = TransformResponse.builder()
                .success(true)
                .projectPath(projectPath)
                .transformedAt(result.transformedAt())
                .dryRun(request.isDryRun())
                .message(message)
                .totalTransformed(result.totalTransformed())
                .totalSkipped(result.totalSkipped())
                .totalFailed(result.totalFailed())
                .branchName(branchResult.branchName())
                .entries(entryDtos)
                .build();

            // Phase 4: Store transformed values in the config store (ValKey or MongoDB)
            if (!request.isDryRun() && result.totalTransformed() > 0) {
                storeTransformedValues(result);
            }

            writeTransformLog(response);
            writeMusketeerLog(result, scope.name(), branchResult.branchName(), projectPath);
            return response;

        } catch (Exception e) {
            log.error("Transformation failed for project: {}", projectPath, e);
            return TransformResponse.builder()
                .success(false)
                .projectPath(projectPath)
                .transformedAt(LocalDateTime.now())
                .dryRun(request.isDryRun())
                .message("Transformation failed: " + e.getMessage())
                .entries(List.of())
                .build();
        }
    }

    /**
     * Handles the REMOVE_UNUSED scope: queries Neo4j for constants with no USED_IN
     * relationships, then delegates to {@link UnusedConstantRemover} to strip their
     * declarations from source files.
     */
    private TransformResponse handleRemoveUnused(TransformRequest request) {
        String projectPath = request.getProjectPath();

        try {
            // Phase 1: Query Neo4j for unused constants
            List<ConstantWithFile> unusedConstants = findUnusedConstants(projectPath);
            log.info("Found {} unused constant candidates", unusedConstants.size());

            if (unusedConstants.isEmpty()) {
                return TransformResponse.builder()
                    .success(true)
                    .projectPath(projectPath)
                    .transformedAt(LocalDateTime.now())
                    .dryRun(request.isDryRun())
                    .message("No unused constants found for this project")
                    .totalTransformed(0)
                    .totalSkipped(0)
                    .totalFailed(0)
                    .entries(List.of())
                    .build();
            }

            // Group targets by definition file
            Map<Path, List<ConstantTarget>> targetsByFile = new HashMap<>();
            for (ConstantWithFile c : unusedConstants) {
                targetsByFile.computeIfAbsent(Paths.get(c.filePath()), k -> new ArrayList<>())
                    .add(c.toTarget());
            }

            // Apply optional file filter
            applyFileFilter(targetsByFile, request.getFileFilter());

            log.info("Found {} total unused candidates across {} files",
                     unusedConstants.size(), targetsByFile.size());

            // Phase 2: Remove (dry-run or live with git branch)
            TransformationResult result;
            BranchResult branchResult = BranchResult.SKIPPED;

            if (request.isDryRun()) {
                List<TransformationEntry> entries = targetsByFile.entrySet().stream()
                    .flatMap(entry -> unusedRemover.dryRun(entry.getKey(), entry.getValue()).stream())
                    .toList();
                result = new TransformationResult(projectPath, LocalDateTime.now(), entries);
            } else {
                // Live run: create branch, remove in place, commit, switch back
                branchResult = transformOnBranch(projectPath, targetsByFile, true);
                if (branchResult.result() != null) {
                    result = branchResult.result();
                } else {
                    log.warn("Git branch creation failed, falling back to backup-based removal: {}",
                        branchResult.error());
                    result = unusedRemover.removeFromProject(projectPath, targetsByFile);
                }
            }

            // Phase 3: Build response
            List<TransformResponse.EntryDto> entryDtos = result.entries().stream()
                .map(TransformResponse.EntryDto::from)
                .toList();

            String summary = "Removed %d constants, skipped %d, failed %d"
                .formatted(result.totalTransformed(), result.totalSkipped(), result.totalFailed());

            String message;
            if (request.isDryRun()) {
                message = "Dry run complete: %d would be removed, %d skipped, %d failed"
                    .formatted(result.totalTransformed(), result.totalSkipped(), result.totalFailed());
            } else if (branchResult.branchName() != null) {
                message = summary + " — committed to branch '%s'".formatted(branchResult.branchName());
            } else if (branchResult.error() != null) {
                message = summary + " (git branch failed: %s)".formatted(branchResult.error());
            } else {
                message = summary;
            }

            TransformResponse response = TransformResponse.builder()
                .success(true)
                .projectPath(projectPath)
                .transformedAt(result.transformedAt())
                .dryRun(request.isDryRun())
                .message(message)
                .totalTransformed(result.totalTransformed())
                .totalSkipped(result.totalSkipped())
                .totalFailed(result.totalFailed())
                .branchName(branchResult.branchName())
                .entries(entryDtos)
                .build();

            writeTransformLog(response);
            writeMusketeerLog(result, "REMOVE_UNUSED", branchResult.branchName(), projectPath);
            return response;

        } catch (Exception e) {
            log.error("Remove-unused failed for project: {}", projectPath, e);
            return TransformResponse.builder()
                .success(false)
                .projectPath(projectPath)
                .transformedAt(LocalDateTime.now())
                .dryRun(request.isDryRun())
                .message("Remove unused failed: " + e.getMessage())
                .entries(List.of())
                .build();
        }
    }

    /**
     * Result of a git-branch-based transformation.
     *
     * @param branchName the name of the transform branch (null if failed)
     * @param error      error message (null if succeeded)
     * @param result     the transformation result (null if git setup failed before transform)
     */
    private record BranchResult(String branchName, String error, TransformationResult result) {
        static final BranchResult SKIPPED = new BranchResult(null, null, null);

        static BranchResult success(String branchName, TransformationResult result) {
            return new BranchResult(branchName, null, result);
        }

        static BranchResult failure(String error) {
            return new BranchResult(null, error, null);
        }
    }

    /**
     * Creates a new git branch, transforms files in-place on that branch, commits,
     * and switches back to the original branch.
     *
     * <p>Flow:
     * <ol>
     *   <li>If not a git repo: {@code git init}, create .gitignore, commit as "Pre-transform code"</li>
     *   <li>Create branch {@code <rootBranch>-transform}</li>
     *   <li>Transform files in-place (directly on the source files)</li>
     *   <li>Commit transformed files</li>
     *   <li>Switch back to the original branch (restores original files)</li>
     * </ol>
     *
     * <p>The original code is preserved on the original branch. The transformed code
     * is on the new branch. Compare with: {@code git diff <original>..<transform>}
     *
     * @param projectPath   path to the project
     * @param targetsByFile map of file paths to transformation targets
     * @param isRemoval     if true, removes constants; if false, replaces with lookups
     * @return BranchResult with branch name and transformation result
     */
    private BranchResult transformOnBranch(
            String projectPath,
            Map<Path, List<ConstantTarget>> targetsByFile,
            boolean isRemoval) {

        Path projectDir = Paths.get(projectPath);
        String originalBranch = null;
        String newBranch = null;

        try {
            // Step 1: Ensure git repo exists
            if (!gitService.isGitRepo(projectDir)) {
                log.info("Project has no git repo; initialising: {}", projectPath);
                originalBranch = gitService.initRepoWithInitialCommit(projectDir);
            } else {
                originalBranch = gitService.getCurrentBranch(projectDir);
            }

            // Step 2: Create transform branch
            newBranch = originalBranch + "-transform";

            // If the branch already exists from a previous run, delete it
            if (gitService.branchExists(projectDir, newBranch)) {
                log.info("Branch '{}' already exists, deleting it", newBranch);
                gitService.deleteBranch(projectDir, newBranch, true);
            }

            gitService.createAndCheckoutBranch(projectDir, newBranch);

            // Step 3: Transform files in-place (on the new branch)
            TransformationResult result;
            if (isRemoval) {
                result = unusedRemover.removeFromProjectInPlace(projectPath, targetsByFile);
            } else {
                result = transformer.transformProjectInPlace(projectPath, targetsByFile);
            }

            // Step 4: Commit transformed files
            Set<String> transformedFiles = result.entries().stream()
                .filter(e -> e.status() == TransformationEntry.Status.TRANSFORMED)
                .map(TransformationEntry::filePath)
                .collect(Collectors.toCollection(LinkedHashSet::new));

            if (!transformedFiles.isEmpty()) {
                String commitMsg = isRemoval
                    ? "Remove %d unused constants".formatted(transformedFiles.size())
                    : "Transform %d constants to config lookups".formatted(transformedFiles.size());
                gitService.addFilesAndCommit(projectDir, transformedFiles, commitMsg);
            }

            // Step 5: Switch back to original branch (restores original files)
            gitService.checkoutBranch(projectDir, originalBranch);

            log.info("Created branch '{}' with {} transformed files", newBranch, transformedFiles.size());
            return BranchResult.success(newBranch, result);

        } catch (GitService.GitException e) {
            log.error("Git-based transformation failed", e);

            // Rollback: try to get back to the original branch and clean up
            if (originalBranch != null) {
                try {
                    gitService.checkoutBranch(projectDir, originalBranch);
                    if (newBranch != null && gitService.branchExists(projectDir, newBranch)) {
                        gitService.deleteBranch(projectDir, newBranch, true);
                    }
                } catch (GitService.GitException rollbackEx) {
                    log.error("Rollback failed — could not checkout '{}': {}", originalBranch, rollbackEx.getMessage());
                }
            }
            return BranchResult.failure(e.getMessage());
        }
    }

    /**
     * Removes entries from {@code targetsByFile} whose file path does not contain the filter string.
     * If the filter is null or blank, no filtering is applied.
     */
    private void applyFileFilter(Map<Path, List<ConstantTarget>> targetsByFile, String fileFilter) {
        if (fileFilter != null && !fileFilter.isBlank()) {
            String trimmed = fileFilter.trim();
            int before = targetsByFile.size();
            targetsByFile.keySet().removeIf(path -> !path.toString().contains(trimmed));
            log.info("File filter '{}': {} → {} files", trimmed, before, targetsByFile.size());
        }
    }

    /**
     * In-memory preview for the {@code /compare} dry-run path. Finds the
     * targets that would apply to {@code filePath} within {@code projectPath}
     * and runs them through {@link ConfigLookupTransformer#previewFile}.
     * Returns the original and would-be-transformed source as strings.
     */
    public ConfigLookupTransformer.PreviewResult previewFile(String projectPath, Path filePath) {
        List<ConstantTarget> targets = findTargetsForFile(projectPath, filePath);
        return transformer.previewFile(filePath, targets);
    }

    /**
     * Returns the {@link ConstantTarget}s that would apply to a single file
     * within the project. Equivalent to the Phase 1 candidate selection in
     * {@link #transform}: all dedup'd public-static-final constants from the
     * project, plus the parameters defined in the requested file.
     *
     * <p>Used by the {@code /transform/compare} endpoint when {@code dryRun=true}
     * to drive {@link ConfigLookupTransformer#previewFile}.
     */
    List<ConstantTarget> findTargetsForFile(String projectPath, Path filePath) {
        List<ConstantTarget> targets = new ArrayList<>();

        // Project-wide constants (deduped by name — same logic as Phase 1)
        findConfigurationCandidates(projectPath).stream()
            .map(ConstantWithFile::toTarget)
            .collect(Collectors.toMap(ConstantTarget::constantName, t -> t, (a, b) -> a))
            .values()
            .forEach(targets::add);

        // Parameters defined in the specific file
        String filePathStr = filePath.toString();
        findParameterCandidates(projectPath).stream()
            .filter(p -> p.filePath().equals(filePathStr))
            .map(ParameterWithFile::toTarget)
            .forEach(targets::add);

        return targets;
    }

    /**
     * Queries Neo4j for constants that look like configuration, joining with
     * file nodes to get the source file path.
     */
    private List<ConstantWithFile> findConfigurationCandidates(String projectPath) {
        return neo4jClient.query("""
            MATCH (p:Project {rootPath: $path})-[:CONTAINS]->(f:File)<-[:DEFINED_IN]-(c:Constant)
            WHERE c.accessModifier = 'public'
              AND (c.type IN ['String', 'java.lang.String', 'int', 'long', 'Integer', 'Long', 'boolean', 'Boolean', 'double', 'Double']
                   OR c.isEnum = true)
//              AND c.name =~ '(?i).*(HOST|PORT|URL|URI|PATH|KEY|SECRET|PASSWORD|TIMEOUT|ENDPOINT|DATABASE|CONNECTION|SERVER|CLIENT).*'
            RETURN c.name AS constantName,
                   c.type AS constantType,
                   c.value AS constantValue,
                   c.className AS className,
                   c.lineNumber AS lineNumber,
                   f.path AS filePath
            ORDER BY f.path, c.lineNumber
            """)
            .bind(projectPath).to("path")
            .fetchAs(ConstantWithFile.class)
            .mappedBy((typeSystem, record) -> new ConstantWithFile(
                record.get("constantName").asString(),
                record.get("constantType").asString(),
                record.get("constantValue").asString(""),
                record.get("className").asString(),
                record.get("lineNumber").asInt(),
                record.get("filePath").asString()
            ))
            .all()
            .stream()
            .toList();
    }

    /**
     * Queries Neo4j for constants that have no USED_IN relationships (i.e., they are
     * declared but never referenced in any scanned file). Excludes enum constants
     * and non-public constants (private/protected/package-private fields are internal
     * to their class and should not be removed or transformed).
     */
    private List<ConstantWithFile> findUnusedConstants(String projectPath) {
        return neo4jClient.query("""
            MATCH (p:Project {rootPath: $path})-[:CONTAINS]->(f:File)<-[:DEFINED_IN]-(c:Constant)
            WHERE c.accessModifier = 'public'
              AND NOT exists { MATCH (c)-[:USED_IN]->() }
              AND NOT coalesce(c.isEnum, false) = true
            RETURN c.name AS constantName,
                   c.type AS constantType,
                   c.value AS constantValue,
                   c.className AS className,
                   c.lineNumber AS lineNumber,
                   f.path AS filePath
            ORDER BY f.path, c.lineNumber
            """)
            .bind(projectPath).to("path")
            .fetchAs(ConstantWithFile.class)
            .mappedBy((typeSystem, record) -> new ConstantWithFile(
                record.get("constantName").asString(),
                record.get("constantType").asString(),
                record.get("constantValue").asString(""),
                record.get("className").asString(),
                record.get("lineNumber").asInt(),
                record.get("filePath").asString()
            ))
            .all()
            .stream()
            .toList();
    }

    /**
     * Queries Neo4j for parameter usages (e.g., getProperty("db.host")) that were
     * discovered during scanning, joining with the file where each usage occurs.
     */
    private List<ParameterWithFile> findParameterCandidates(String projectPath) {
        return neo4jClient.query("""
            MATCH (p:Project {rootPath: $path})-[:CONTAINS]->(f:File)<-[:DEFINED_IN]-(param:Parameter)
                  -[usage:USED_IN]->(usageFile:File)
            RETURN param.name AS paramName,
                   param.value AS paramValue,
                   usage.className AS className,
                   usage.lineNumber AS lineNumber,
                   usageFile.path AS filePath
            ORDER BY usageFile.path, usage.lineNumber
            """)
            .bind(projectPath).to("path")
            .fetchAs(ParameterWithFile.class)
            .mappedBy((typeSystem, record) -> new ParameterWithFile(
                record.get("paramName").asString(),
                record.get("paramValue").asString(""),
                record.get("className").asString(),
                record.get("lineNumber").asInt(),
                record.get("filePath").asString()
            ))
            .all()
            .stream()
            .toList();
    }

    /**
     * Queries Neo4j for all Java source files belonging to the given project.
     */
    private List<String> findProjectJavaFiles(String projectPath) {
        return neo4jClient.query("""
            MATCH (p:Project {rootPath: $path})-[:CONTAINS]->(f:File)
            WHERE f.path ENDS WITH '.java'
            RETURN f.path AS filePath
            """)
            .bind(projectPath).to("path")
            .fetchAs(String.class)
            .mappedBy((typeSystem, record) -> record.get("filePath").asString())
            .all()
            .stream()
            .toList();
    }

    /**
     * For each constant in the project, returns the list of files where it is defined.
     */
    private Map<String, List<String>> findConstantFiles(String projectPath) {
        record ConstantFile(String constantName, String fileName) {}
        List<ConstantFile> rows = neo4jClient.query("""
            MATCH (p:Project {rootPath: $path})-[:CONTAINS]->(f:File)<-[:DEFINED_IN]-(c:Constant)
            RETURN c.name AS constantName, f.fileName AS fileName
            ORDER BY c.name
            """)
            .bind(projectPath).to("path")
            .fetchAs(ConstantFile.class)
            .mappedBy((typeSystem, record) -> new ConstantFile(
                record.get("constantName").asString(),
                record.get("fileName").asString()
            ))
            .all()
            .stream()
            .toList();

        return rows.stream().collect(Collectors.groupingBy(
            ConstantFile::constantName,
            Collectors.mapping(ConstantFile::fileName, Collectors.toList())
        ));
    }

    /**
     * Stores the (propertyKey, originalValue) pairs from successfully transformed entries
     * into the active config store (ValKey or MongoDB). This makes the values available
     * at runtime via {@code config.lookup("key")} after the code has been transformed.
     */
    private void storeTransformedValues(TransformationResult result) {
        Map<String, String> values = result.entries().stream()
            .filter(e -> e.status() == TransformationEntry.Status.TRANSFORMED)
            .filter(e -> e.propertyKey() != null && !e.propertyKey().isBlank())
            .filter(e -> e.originalValue() != null && !e.originalValue().isBlank())
            .collect(Collectors.toMap(
                TransformationEntry::propertyKey,
                e -> stripQuotes(e.originalValue()),
                (a, b) -> a  // keep first if duplicate keys
            ));

        if (!values.isEmpty()) {
            configService.storeBatch(values);
            log.info("Stored {} configuration values in {}", values.size(),
                configService.getStoreName());
        }
    }

    /**
     * Strips surrounding quotes from a value. Constants in source code have values
     * like {@code "localhost"} (with quotes); the stored value should be {@code localhost}.
     */
    private String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Writes "The Musketeer Log" — a detailed markdown document with before/after
     * code for every transformation, organized by file with a table of contents.
     */
    private void writeMusketeerLog(TransformationResult result, String scope,
                                    String branchName, String projectPath) {
        if (result == null || result.entries().isEmpty()) return;

        try {
            Path logsDir = Paths.get("logs");
            Files.createDirectories(logsDir);
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .format(result.transformedAt());
            Path logFile = logsDir.resolve("musketeer-" + timestamp + ".md");
            MusketeerLog.writeLog(result, scope, branchName,
                transformer.getLastSupervisorResults(), logFile);
        } catch (Exception e) {
            log.warn("Failed to write Musketeer Log: {}", e.getMessage());
        }
    }

    /**
     * Writes a summary log file for the transformation run to {@code logs/transform-<timestamp>.log}.
     * Failures are caught and logged so they never break the transform response.
     */
    private void writeTransformLog(TransformResponse response) {
        try {
            Path logsDir = Paths.get("logs");
            Files.createDirectories(logsDir);

            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                    .format(response.getTransformedAt());
            Path logFile = logsDir.resolve("transform-" + timestamp + ".log");

            StringBuilder sb = new StringBuilder();
            sb.append("=== Transformation Log ===\n");
            sb.append("Timestamp:   ").append(response.getTransformedAt()).append('\n');
            sb.append("Project:     ").append(response.getProjectPath()).append('\n');
            sb.append("Dry Run:     ").append(response.isDryRun()).append('\n');
            sb.append("Transformed: ").append(response.getTotalTransformed()).append('\n');
            sb.append("Skipped:     ").append(response.getTotalSkipped()).append('\n');
            sb.append("Failed:      ").append(response.getTotalFailed()).append('\n');
            sb.append("==========================\n\n");

            for (TransformResponse.EntryDto entry : response.getEntries()) {
                sb.append('[').append(entry.getStatus()).append("] ")
                  .append(entry.getFilePath()).append(':').append(entry.getLineNumber())
                  .append(" — ").append(entry.getConstantName())
                  .append(" → ").append(entry.getPropertyKey())
                  .append(" — ").append(entry.getMessage())
                  .append('\n');
            }

            Files.writeString(logFile, sb.toString());
            log.info("Transform log written to {}", logFile.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to write transform log file: {}", e.getMessage());
        }
    }

    /**
     * Intermediate record joining constant data with its file location from Neo4j.
     */
    record ConstantWithFile(
        String constantName,
        String constantType,
        String constantValue,
        String className,
        int lineNumber,
        String filePath
    ) {
        ConstantTarget toTarget() {
            return new ConstantTarget(
                constantName,
                ConstantTarget.derivePropertyKey(constantName),
                constantValue,
                constantType,
                className,
                lineNumber
            );
        }
    }

    /**
     * Intermediate record joining parameter usage data with its file location from Neo4j.
     */
    record ParameterWithFile(
        String paramName,
        String paramValue,
        String className,
        int lineNumber,
        String filePath
    ) {
        ConstantTarget toTarget() {
            return new ConstantTarget(
                paramName,          // constantName holds the parameter name
                paramName,          // propertyKey is the param name as-is (already dotted)
                paramValue,
                "String",           // parameters are always String
                className,
                lineNumber,
                ConstantTarget.SourceKind.PARAMETER
            );
        }
    }
}
