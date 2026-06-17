package com.boeing.constcatalog.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for triggering a code transformation.
 * Replaces hardcoded constants and parameter usages with
 * {@code config.lookupType()} calls using data from the Neo4j graph.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to transform constants and parameters into config lookups")
public class TransformRequest {

    public enum CandidateScope {
        CONSTANTS_ONLY,
        PARAMETERS_ONLY,
        ALL,
        REMOVE_UNUSED
    }

    @NotBlank(message = "Project path is required")
    @Schema(
        description = "Absolute path to the project whose constants should be transformed",
        example = "/home/user/projects/my-app",
        required = true
    )
    private String projectPath;

    @Builder.Default
    @Schema(
        description = "If true, report what would change without modifying files",
        example = "false"
    )
    private boolean dryRun = false;

    @Builder.Default
    @Schema(
        description = "Which candidate types to include: CONSTANTS_ONLY, PARAMETERS_ONLY, or ALL",
        example = "ALL"
    )
    private CandidateScope candidateScope = CandidateScope.ALL;

    @Builder.Default
    @Schema(
        description = "If true, create a new git branch with the transformed files committed",
        example = "false"
    )
    private boolean createBranch = false;

    @Schema(
        description = "Optional file path filter. Only files whose path contains this string will be transformed.",
        example = "src/main/java"
    )
    private String fileFilter;
}
