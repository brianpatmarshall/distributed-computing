package com.boeing.constcatalog.backend.controller;

import com.boeing.constcatalog.backend.dto.ScanProgressEvent;
import com.boeing.constcatalog.backend.dto.ScanRequest;
import com.boeing.constcatalog.backend.dto.ScanResponse;
import com.boeing.constcatalog.backend.service.ScanService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST controller for project scanning operations.
 *
 * The scan endpoint is the entry point for adding new data to the graph.
 * It accepts a project path, triggers the scanning process, and returns
 * a summary of what was found.
 *
 * Scanning is idempotent: re-scanning a project updates existing nodes
 * rather than creating duplicates. This enables incremental updates as
 * code evolves.
 */
@RestController
@RequestMapping("/api/scan")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Scan", description = "Project scanning operations - discover constants and parameters in Java projects")
public class ScanController {

    private final ScanService scanService;
    private final ObjectMapper objectMapper;

    @Operation(
        summary = "Scan a Java project",
        description = """
            Scans a Java project directory to discover constants and configuration parameters.

            **The scan process:**
            1. Validates the path exists and is a directory
            2. Walks the file tree to find Java and property files
            3. Parses each file to extract constants (`static final` fields) and parameters
            4. Detects parameter usages in Java code (e.g., `getProperty("key")`)
            5. Populates the Neo4j graph with nodes and relationships

            **Scanning is idempotent:** Re-scanning a project updates existing nodes rather than
            creating duplicates, enabling incremental updates as code evolves.

            **Excluded directories:** `target/`, `build/`, `node_modules/`, `.git/`, `.idea/`
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Scan completed successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ScanResponse.class),
                examples = @ExampleObject(
                    value = """
                        {
                            "success": true,
                            "projectPath": "/path/to/project",
                            "projectName": "my-project",
                            "scannedAt": "2024-01-15T10:30:00",
                            "javaFilesScanned": 150,
                            "propertyFilesScanned": 12,
                            "constantsFound": 487,
                            "parametersFound": 95,
                            "parameterUsagesFound": 234,
                            "message": "Scan completed successfully"
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request - path does not exist or is not a directory",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ScanResponse.class),
                examples = @ExampleObject(
                    value = """
                        {
                            "success": false,
                            "message": "Path does not exist: /invalid/path"
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "500",
            description = "Internal server error during scan",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ScanResponse.class)
            )
        )
    })
    @PostMapping
    public ResponseEntity<ScanResponse> scanProject(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                description = "Scan request with project path",
                required = true,
                content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ScanRequest.class),
                    examples = {
                        @ExampleObject(
                            name = "Unix Path",
                            value = """
                                {"projectPath": "/home/user/projects/my-app"}
                                """
                        ),
                        @ExampleObject(
                            name = "Windows Path",
                            value = """
                                {"projectPath": "C:\\\\Users\\\\dev\\\\projects\\\\my-app"}
                                """
                        )
                    }
                )
            )
            @Valid @RequestBody ScanRequest request) {

        log.info("Received scan request for: {}", request.getProjectPath());

        try {
            ScanResponse response = scanService.scanProject(request);

            if (response.isSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.badRequest().body(response);
            }

        } catch (IllegalArgumentException e) {
            log.warn("Invalid scan request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                ScanResponse.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build()
            );
        } catch (Exception e) {
            log.error("Scan failed unexpectedly", e);
            return ResponseEntity.internalServerError().body(
                ScanResponse.builder()
                    .success(false)
                    .message("Internal error: " + e.getMessage())
                    .build()
            );
        }
    }

    @Operation(
        summary = "Stream scan progress via Server-Sent Events",
        description = "Starts a project scan and streams real-time progress events. " +
            "Connect to this endpoint using an EventSource to receive progress updates."
    )
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamScan(@RequestParam("projectPath") String projectPath) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 minute timeout

        log.info("SSE scan stream requested for: {}", projectPath);

        scanService.scanProjectWithProgress(
            new ScanRequest(projectPath),
            emitter,
            objectMapper
        );

        return emitter;
    }
}
