package com.boeing.constcatalog.backend.service;

import com.boeing.constcatalog.backend.dto.TransformRequest;
import com.boeing.constcatalog.backend.dto.TransformRequest.CandidateScope;
import com.boeing.constcatalog.backend.dto.TransformResponse;
import com.boeing.constcatalog.model.ConstantTarget;
import com.boeing.constcatalog.model.TransformationEntry;
import com.boeing.constcatalog.model.TransformationResult;
import com.boeing.constcatalog.transformer.ConfigLookupTransformer;
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

    public CodeTransformationService(Neo4jClient neo4jClient, GitService gitService) {
        this.neo4jClient = neo4jClient;
        this.transformer = new ConfigLookupTransformer();
        this.unusedRemover = new UnusedConstantRemover();
        this.gitService = gitService;
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

            // Phase 2: Transform (or dry-run)
            TransformationResult result;
            if (request.isDryRun()) {
                List<TransformationEntry> entries = targetsByFile.entrySet().stream()
                    .flatMap(entry -> transformer.dryRun(entry.getKey(), entry.getValue()).stream())
                    .toList();
                result = new TransformationResult(projectPath, LocalDateTime.now(), entries);
            } else {
                result = transformer.transformProject(projectPath, targetsByFile);
            }

            // Phase 2.5: Create branch with transformed files if requested
            BranchResult branchResult = BranchResult.SKIPPED;
            if (request.isCreateBranch() && !request.isDryRun() && result.totalTransformed() > 0) {
                branchResult = createBranchWithTransformedFiles(projectPath, result);
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
                message = summary + " — branch creation failed: " + branchResult.error();
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

            log.info("Found {} total unused candidates across {} files",
                     unusedConstants.size(), targetsByFile.size());

            // Phase 2: Remove (or dry-run)
            TransformationResult result;
            if (request.isDryRun()) {
                List<TransformationEntry> entries = targetsByFile.entrySet().stream()
                    .flatMap(entry -> unusedRemover.dryRun(entry.getKey(), entry.getValue()).stream())
                    .toList();
                result = new TransformationResult(projectPath, LocalDateTime.now(), entries);
            } else {
                result = unusedRemover.removeFromProject(projectPath, targetsByFile);
            }

            // Phase 2.5: Create branch with modified files if requested
            BranchResult branchResult = BranchResult.SKIPPED;
            if (request.isCreateBranch() && !request.isDryRun() && result.totalTransformed() > 0) {
                branchResult = createBranchWithTransformedFiles(projectPath, result);
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
                message = summary + " — branch creation failed: " + branchResult.error();
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
     * Result of attempting to create a git branch with transformed files.
     */
    private record BranchResult(String branchName, String error) {
        static final BranchResult SKIPPED = new BranchResult(null, null);

        static BranchResult success(String branchName) {
            return new BranchResult(branchName, null);
        }

        static BranchResult failure(String error) {
            return new BranchResult(null, error);
        }
    }

    /**
     * Creates a {@code <branch>-transformed} git branch in the scanned project, copies
     * the transformed files from the backups directory back into the project, commits,
     * and switches back to the original branch.
     *
     * <p>If the scanned project is not yet under git version control, it is initialised
     * with {@code git init}, an initial commit of the pre-transformation state is made
     * on branch {@code main}, and the transformed changes are then committed on
     * {@code main-transformed}.  The two branches can be compared with:
     * <pre>  git diff main..main-transformed</pre>
     */
    private BranchResult createBranchWithTransformedFiles(String projectPath, TransformationResult result) {
        Path projectDir = Paths.get(projectPath);

        String originalBranch = null;
        try {
            if (!gitService.isGitRepo(projectDir)) {
                log.info("Project has no git repo; initialising one with a pre-transformation baseline: {}", projectPath);
                originalBranch = gitService.initRepoWithInitialCommit(projectDir);
            } else {
                originalBranch = gitService.getCurrentBranch(projectDir);
            }
            String newBranch = originalBranch + "-transformed";

            // Collect unique file paths that were successfully transformed
            Set<String> transformedFiles = result.entries().stream()
                    .filter(e -> e.status() == TransformationEntry.Status.TRANSFORMED)
                    .map(TransformationEntry::filePath)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            if (transformedFiles.isEmpty()) {
                return BranchResult.SKIPPED;
            }

            gitService.createAndCheckoutBranch(projectDir, newBranch);

            // Copy transformed files from backups/ to their original source paths
            Path backupsDir = Path.of("backups").toAbsolutePath();
            for (String filePath : transformedFiles) {
                Path absSource = Paths.get(filePath).toAbsolutePath();
                Path relPath = absSource.getRoot().relativize(absSource);
                Path backupPath = backupsDir.resolve(relPath);

                if (Files.exists(backupPath)) {
                    Files.copy(backupPath, absSource, StandardCopyOption.REPLACE_EXISTING);
                    log.debug("Copied transformed file: {} -> {}", backupPath, absSource);
                } else {
                    log.warn("Transformed backup not found: {}", backupPath);
                }
            }

            gitService.addFilesAndCommit(projectDir, transformedFiles, "Transform constants to config lookups");
            gitService.checkoutBranch(projectDir, originalBranch);

            log.info("Created branch '{}' with {} transformed files", newBranch, transformedFiles.size());
            return BranchResult.success(newBranch);

        } catch (GitService.GitException | IOException e) {
            log.error("Failed to create branch with transformed files", e);
            // Rollback: try to get back to the original branch
            if (originalBranch != null) {
                try {
                    gitService.checkoutBranch(projectDir, originalBranch);
                } catch (GitService.GitException rollbackEx) {
                    log.error("Rollback failed — could not checkout original branch '{}'", originalBranch, rollbackEx);
                }
            }
            return BranchResult.failure(e.getMessage());
        }
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
              AND c.name =~ '(?i).*(HOST|PORT|URL|URI|PATH|KEY|SECRET|PASSWORD|TIMEOUT|ENDPOINT|DATABASE|CONNECTION|SERVER|CLIENT).*'
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
