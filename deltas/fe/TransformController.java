package com.boeing.constcatalog.backend.controller;

import com.boeing.constcatalog.backend.dto.FileComparisonResponse;
import com.boeing.constcatalog.backend.dto.TransformRequest;
import com.boeing.constcatalog.backend.dto.TransformResponse;
import com.boeing.constcatalog.backend.service.CodeTransformationService;
import com.boeing.constcatalog.backend.service.GitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * REST controller for code transformation operations.
 *
 * <p>Replaces hardcoded constants in scanned Java projects with
 * Spring {@code @Value} property lookups. Uses Neo4j graph data
 * (file paths, line numbers, constant names) to locate and transform
 * configuration candidates in the original source files.
 */
@RestController
@RequestMapping("/api/transform")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transform",
     description = "Code transformation - replace hardcoded constants with property lookups")
public class TransformController {

    private final CodeTransformationService transformationService;
    private final GitService gitService;

    @Operation(
        summary = "Transform constants into property lookups",
        description = """
            Reads configuration-candidate constants from the Neo4j graph (previously
            discovered by scanning) and rewrites the original Java source files to
            replace hardcoded values with Spring @Value annotations.

            **The transformation process:**
            1. Queries Neo4j for constants matching config patterns (HOST, PORT, URL, etc.)
            2. Groups them by source file path
            3. Parses each file with JavaParser
            4. Replaces `private static final String DB_HOST = "localhost"` with
               `@Value("${db.host}") private String dbHost;`
            5. Adds `@Component` annotation if the class isn't already a Spring bean
            6. Adds required imports

            **Dry run mode:** Set `dryRun: true` to see what would change without
            modifying any files.

            **Prerequisites:** The project must have been scanned first via POST /api/scan.
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Transformation completed (check success flag for outcome)",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = TransformResponse.class),
                examples = @ExampleObject(value = """
                    {
                        "success": true,
                        "message": "Transformed 12 constants, skipped 3, failed 1",
                        "projectPath": "/path/to/project",
                        "dryRun": false,
                        "totalTransformed": 12,
                        "totalSkipped": 3,
                        "totalFailed": 1,
                        "entries": []
                    }
                    """)
            )
        ),
        @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    @PostMapping
    public ResponseEntity<TransformResponse> transformProject(@Valid @RequestBody TransformRequest request) {

        log.info("Received transform request for: {} (dryRun={})",
            request.getProjectPath(), request.isDryRun());

        TransformResponse response = transformationService.transform(request);

        // Always return 200 — the success field in the body indicates outcome.
        // Returning 400 for business failures causes Axios to throw and discard
        // the detailed response body, leaving the frontend with a generic error.
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Get before/after comparison for a transformed file",
        description = """
            Returns the original and transformed content of a file for side-by-side comparison.
            Behavior depends on which optional parameters are supplied:
              - dryRun=true (with projectPath): synthesize the transformed content in memory.
              - branch=<name>: read original from current branch and transformed from the transform branch.
              - backupFilePath=<path>: read original from the supplied .orig file, transformed from path.
              - none of the above: try <path>.orig as a fallback; otherwise return an informative message.
            """
    )
    @GetMapping("/compare")
    public ResponseEntity<FileComparisonResponse> compareFile(
            @RequestParam("path") String path,
            @RequestParam(value = "projectPath", required = false) String projectPath,
            @RequestParam(value = "branch", required = false) String branch,
            @RequestParam(value = "backupFilePath", required = false) String backupFilePath,
            @RequestParam(value = "dryRun", required = false, defaultValue = "false") boolean dryRun) {

        log.info("Comparison requested for: {} (dryRun={}, branch={}, backupFilePath={})",
            path, dryRun, branch, backupFilePath);

        try {
            Path sourcePath = Path.of(path).toAbsolutePath();

            // (1) Dry run — synthesize the transformed content in memory.
            if (dryRun) {
                if (projectPath == null || projectPath.isBlank()) {
                    return ResponseEntity.badRequest().body(FileComparisonResponse.builder()
                        .filePath(path)
                        .changes(List.of("dryRun=true requires projectPath"))
                        .build());
                }
                var preview = transformationService.previewFile(projectPath, sourcePath);
                return okResponse(path, preview.originalSource(), preview.transformedSource());
            }

            // (2) Branch mode — original from current branch, transformed from transform branch.
            Path projectDir = findProjectRoot(sourcePath);
            if (projectDir != null && branch != null && gitService.branchExists(projectDir, branch)) {
                String relativePath = projectDir.relativize(sourcePath).toString();
                String originalContent = Files.exists(sourcePath) ? Files.readString(sourcePath) : "";
                String transformedContent = gitService.getFileFromBranch(projectDir, branch, relativePath);
                if (transformedContent == null) transformedContent = originalContent;
                return okResponse(path, originalContent, transformedContent);
            }

            // (3) Live in-place — read original from .orig (caller-supplied or sibling), transformed from sourcePath.
            Path originalFile = resolveOriginalFile(sourcePath, backupFilePath);
            if (Files.exists(originalFile) && Files.exists(sourcePath)) {
                String originalContent = Files.readString(originalFile);
                String transformedContent = Files.readString(sourcePath);
                return okResponse(path, originalContent, transformedContent);
            }

            // (4) No transform on disk and no preview signal — informative message.
            return ResponseEntity.ok(FileComparisonResponse.builder()
                .filePath(path)
                .changes(List.of(
                    "No transformed version available for this file. " +
                    "If this was a dry run, pass dryRun=true with projectPath. " +
                    "If this was a live in-place transform, pass backupFilePath. " +
                    "If this was a live + create-branch transform, pass branch."))
                .build());

        } catch (IOException | GitService.GitException e) {
            log.error("Failed to read file for comparison: {}", path, e);
            return ResponseEntity.internalServerError().body(FileComparisonResponse.builder()
                .filePath(path)
                .changes(List.of("Error reading file: " + e.getMessage()))
                .build());
        }
    }

    /** Resolves where to read the pre-transform original from. */
    private Path resolveOriginalFile(Path sourcePath, String backupFilePathParam) {
        if (backupFilePathParam != null && !backupFilePathParam.isBlank()) {
            return Path.of(backupFilePathParam).toAbsolutePath();
        }
        return Path.of(sourcePath + ".orig");
    }

    private ResponseEntity<FileComparisonResponse> okResponse(
            String path, String originalContent, String transformedContent) {
        List<String> changes = generateChanges(originalContent, transformedContent);
        return ResponseEntity.ok(FileComparisonResponse.builder()
            .filePath(path)
            .originalContent(originalContent)
            .transformedContent(transformedContent)
            .changes(changes)
            .build());
    }

    /**
     * Walks up the directory tree to find the nearest .git directory.
     */
    private Path findProjectRoot(Path filePath) {
        Path dir = filePath.getParent();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve(".git"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private List<String> generateChanges(String original, String transformed) {
        List<String> changes = new ArrayList<>();
        String[] origLines = original.split("\n");
        String[] transLines = transformed.split("\n");

        int maxLines = Math.max(origLines.length, transLines.length);
        for (int i = 0; i < maxLines; i++) {
            String origLine = i < origLines.length ? origLines[i] : "";
            String transLine = i < transLines.length ? transLines[i] : "";

            if (!origLine.equals(transLine)) {
                changes.add("Line %d: '%s' -> '%s'".formatted(i + 1, origLine.trim(), transLine.trim()));
            }
        }

        return changes;
    }
}
