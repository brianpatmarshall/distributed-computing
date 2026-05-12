package com.boeing.constcatalog.transformer;

import com.boeing.constcatalog.model.ConstantTarget;
import com.boeing.constcatalog.model.TransformationEntry;
import com.boeing.constcatalog.model.TransformationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates "The Musketeer Log" — a detailed markdown document recording every
 * transformation applied to a project. Each file gets its own section with
 * line numbers and before/after code for every constant or parameter replaced.
 *
 * <p>The log serves as a complete audit trail: what was changed, where, and how.
 * It is written to the project directory (or a specified output path) after each
 * transformation run.
 */
public class MusketeerLog {

    private static final Logger log = LoggerFactory.getLogger(MusketeerLog.class);

    private static final String TITLE = "The Musketeer Log";
    private static final String SUBTITLE = "In the service of Louis XIII";

    /**
     * Generates the Musketeer Log markdown from a transformation result.
     *
     * @param result      the transformation result
     * @param scope       the candidate scope used (e.g., "ALL", "REMOVE_UNUSED")
     * @param branchName  the git branch where transforms were committed (null if none)
     * @return the markdown content as a string
     */
    /**
     * Generates the Musketeer Log markdown from a transformation result (no supervisor results).
     */
    public static String generate(TransformationResult result, String scope, String branchName) {
        return generate(result, scope, branchName, Map.of());
    }

    /**
     * Generates the Musketeer Log markdown from a transformation result,
     * including supervisor check results.
     *
     * @param result            the transformation result
     * @param scope             the candidate scope used (e.g., "ALL", "REMOVE_UNUSED")
     * @param branchName        the git branch where transforms were committed (null if none)
     * @param supervisorResults supervisor check results keyed by file path
     * @return the markdown content as a string
     */
    public static String generate(TransformationResult result, String scope, String branchName,
                                  Map<String, TransformSupervisor.FileCheckResult> supervisorResults) {
        StringBuilder md = new StringBuilder();

        // Title and subtitle
        md.append("# ").append(TITLE).append("\n\n");
        md.append("*").append(SUBTITLE).append("*\n\n");

        // Metadata
        md.append("---\n\n");
        md.append("| | |\n");
        md.append("|---|---|\n");
        md.append("| **Project** | `").append(result.projectPath()).append("` |\n");
        md.append("| **Date** | ").append(result.transformedAt().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" |\n");
        md.append("| **Scope** | ").append(scope).append(" |\n");
        if (branchName != null) {
            md.append("| **Branch** | `").append(branchName).append("` |\n");
        }
        md.append("| **Transformed** | ").append(result.totalTransformed()).append(" |\n");
        md.append("| **Skipped** | ").append(result.totalSkipped()).append(" |\n");
        md.append("| **Failed** | ").append(result.totalFailed()).append(" |\n");
        md.append("\n---\n\n");

        // Table of Contents
        Map<String, List<TransformationEntry>> byFile = result.entries().stream()
            .collect(Collectors.groupingBy(
                TransformationEntry::filePath,
                LinkedHashMap::new,
                Collectors.toList()
            ));

        md.append("## Table of Contents\n\n");
        int sectionNum = 1;
        for (Map.Entry<String, List<TransformationEntry>> fileEntry : byFile.entrySet()) {
            String shortName = extractFileName(fileEntry.getKey());
            long transformed = fileEntry.getValue().stream()
                .filter(e -> e.status() == TransformationEntry.Status.TRANSFORMED).count();
            long total = fileEntry.getValue().size();
            String anchor = toAnchor(sectionNum + "-" + shortName);
            md.append(sectionNum).append(". [").append(shortName)
                .append("](#").append(anchor).append(")")
                .append(" — ").append(transformed).append("/").append(total).append(" changes\n");
            sectionNum++;
        }

        if (result.totalSkipped() > 0) {
            md.append("\n**[Appendix A: Skipped Transformations](#appendix-a-skipped-transformations)**")
                .append(" — ").append(result.totalSkipped()).append(" entries\n");
        }
        if (result.totalFailed() > 0) {
            md.append("\n**[Appendix B: Failed Transformations](#appendix-b-failed-transformations)**")
                .append(" — ").append(result.totalFailed()).append(" entries\n");
        }

        md.append("\n---\n\n");

        // File sections
        sectionNum = 1;
        for (Map.Entry<String, List<TransformationEntry>> fileEntry : byFile.entrySet()) {
            String filePath = fileEntry.getKey();
            String shortName = extractFileName(filePath);
            List<TransformationEntry> entries = fileEntry.getValue();

            md.append("## ").append(sectionNum).append(". ").append(shortName).append("\n\n");
            md.append("**File:** `").append(filePath).append("`\n\n");

            // Transformed entries
            List<TransformationEntry> transformed = entries.stream()
                .filter(e -> e.status() == TransformationEntry.Status.TRANSFORMED)
                .sorted(Comparator.comparingInt(TransformationEntry::lineNumber))
                .toList();

            if (!transformed.isEmpty()) {
                for (TransformationEntry entry : transformed) {
                    md.append("### Line ").append(entry.lineNumber())
                        .append(": `").append(entry.constantName()).append("`\n\n");

                    String before = formatBefore(entry);
                    String after = formatAfter(entry);

                    md.append("**Before:**\n\n");
                    md.append("```java\n");
                    md.append(before).append("\n");
                    md.append("```\n\n");

                    md.append("**After:**\n\n");
                    md.append("```java\n");
                    md.append(after).append("\n");
                    md.append("```\n\n");
                }
            }

            // Skipped/Failed entries for this file (brief)
            List<TransformationEntry> nonTransformed = entries.stream()
                .filter(e -> e.status() != TransformationEntry.Status.TRANSFORMED)
                .toList();

            if (!nonTransformed.isEmpty()) {
                md.append("| Status | Constant | Line | Reason |\n");
                md.append("|---|---|---|---|\n");
                for (TransformationEntry entry : nonTransformed) {
                    md.append("| ").append(statusEmoji(entry.status())).append(" ")
                        .append(entry.status()).append(" | `")
                        .append(entry.constantName()).append("` | ")
                        .append(entry.lineNumber()).append(" | ")
                        .append(entry.message()).append(" |\n");
                }
                md.append("\n");
            }

            md.append("---\n\n");
            sectionNum++;
        }

        // Appendix A: Skipped Transformations
        List<TransformationEntry> allSkipped = result.entries().stream()
            .filter(e -> e.status() == TransformationEntry.Status.SKIPPED)
            .toList();

        if (!allSkipped.isEmpty()) {
            md.append("## Appendix A: Skipped Transformations\n\n");
            md.append("These constants were identified as candidates but were **not transformed**.\n");
            md.append("The reason column explains why each was skipped.\n\n");

            // Group skipped by file for readability
            Map<String, List<TransformationEntry>> skippedByFile = allSkipped.stream()
                .collect(Collectors.groupingBy(
                    TransformationEntry::filePath,
                    LinkedHashMap::new,
                    Collectors.toList()
                ));

            for (Map.Entry<String, List<TransformationEntry>> fileEntry : skippedByFile.entrySet()) {
                md.append("### `").append(extractFileName(fileEntry.getKey())).append("`\n\n");
                md.append("**File:** `").append(fileEntry.getKey()).append("`\n\n");
                md.append("| Constant | Line | Reason |\n");
                md.append("|---|---|---|\n");
                for (TransformationEntry entry : fileEntry.getValue()) {
                    md.append("| `").append(entry.constantName())
                        .append("` | ").append(entry.lineNumber())
                        .append(" | ").append(entry.message()).append(" |\n");
                }
                md.append("\n");
            }

            md.append("---\n\n");
        }

        // Appendix B: Failed Transformations
        List<TransformationEntry> allFailed = result.entries().stream()
            .filter(e -> e.status() == TransformationEntry.Status.FAILED)
            .toList();

        if (!allFailed.isEmpty()) {
            md.append("## Appendix B: Failed Transformations\n\n");
            md.append("These constants were targeted for transformation but the operation **failed**.\n");
            md.append("The reason column explains what went wrong.\n\n");

            // Group failed by file
            Map<String, List<TransformationEntry>> failedByFile = allFailed.stream()
                .collect(Collectors.groupingBy(
                    TransformationEntry::filePath,
                    LinkedHashMap::new,
                    Collectors.toList()
                ));

            for (Map.Entry<String, List<TransformationEntry>> fileEntry : failedByFile.entrySet()) {
                md.append("### `").append(extractFileName(fileEntry.getKey())).append("`\n\n");
                md.append("**File:** `").append(fileEntry.getKey()).append("`\n\n");
                md.append("| Constant | Line | Reason |\n");
                md.append("|---|---|---|\n");
                for (TransformationEntry entry : fileEntry.getValue()) {
                    md.append("| `").append(entry.constantName())
                        .append("` | ").append(entry.lineNumber())
                        .append(" | ").append(entry.message()).append(" |\n");
                }
                md.append("\n");
            }

            md.append("---\n\n");
        }

        // Appendix C: Supervisor Check
        if (!supervisorResults.isEmpty()) {
            md.append("## Appendix C: Supervisor Check\n\n");
            md.append("Post-transformation advisory checks for each modified file.\n\n");

            md.append("| File | Syntax | Config Field | Autowired Import | ConfigService Import | Overall |\n");
            md.append("|---|---|---|---|---|---|\n");

            for (Map.Entry<String, TransformSupervisor.FileCheckResult> entry : supervisorResults.entrySet()) {
                String fileName = extractFileName(entry.getKey());
                TransformSupervisor.FileCheckResult fileResult = entry.getValue();

                Map<String, TransformSupervisor.CheckResult> checksByName = new LinkedHashMap<>();
                for (TransformSupervisor.CheckResult check : fileResult.checks()) {
                    checksByName.put(check.checkName(), check);
                }

                String overall = fileResult.allPassed() ? "PASS"
                    : fileResult.hasFailures() ? "FAIL" : "WARN";
                String overallEmoji = fileResult.allPassed() ? "✅"
                    : fileResult.hasFailures() ? "❌" : "⚠️";

                md.append("| `").append(fileName).append("` | ");
                md.append(formatCheck(checksByName.get("Syntax"))).append(" | ");
                md.append(formatCheck(checksByName.get("Config Field"))).append(" | ");
                md.append(formatCheck(checksByName.get("Autowired Import"))).append(" | ");
                md.append(formatCheck(checksByName.get("ConfigService Import"))).append(" | ");
                md.append(overallEmoji).append(" ").append(overall).append(" |\n");
            }

            // Detail section for warnings/failures
            boolean hasIssues = supervisorResults.values().stream()
                .anyMatch(r -> !r.allPassed());

            if (hasIssues) {
                md.append("\n### Details\n\n");
                for (Map.Entry<String, TransformSupervisor.FileCheckResult> entry : supervisorResults.entrySet()) {
                    TransformSupervisor.FileCheckResult fileResult = entry.getValue();
                    if (!fileResult.allPassed()) {
                        md.append("**`").append(extractFileName(entry.getKey())).append("`**\n\n");
                        for (TransformSupervisor.CheckResult check : fileResult.checks()) {
                            if (check.status() != TransformSupervisor.CheckResult.Status.PASS && check.detail() != null) {
                                md.append("- ").append(statusIcon(check.status())).append(" **")
                                    .append(check.checkName()).append(":** ")
                                    .append(check.detail()).append("\n");
                            }
                        }
                        md.append("\n");
                    }
                }
            }

            md.append("---\n\n");
        }

        // Footer
        md.append("---\n\n");
        md.append("*Generated by The Musketeer Log — Constants Catalog*\n");

        return md.toString();
    }

    private static String formatCheck(TransformSupervisor.CheckResult check) {
        if (check == null) return "—";
        return switch (check.status()) {
            case PASS -> "✅ PASS";
            case WARN -> "⚠️ WARN";
            case FAIL -> "❌ FAIL";
        };
    }

    private static String statusIcon(TransformSupervisor.CheckResult.Status status) {
        return switch (status) {
            case PASS -> "✅";
            case WARN -> "⚠️";
            case FAIL -> "❌";
        };
    }

    /**
     * Generates the log and writes it to a file.
     */
    public static void writeLog(TransformationResult result, String scope,
                                String branchName, Path outputPath) {
        writeLog(result, scope, branchName, Map.of(), outputPath);
    }

    /**
     * Generates the log with supervisor results and writes it to a file.
     *
     * @param result            the transformation result
     * @param scope             the candidate scope
     * @param branchName        the git branch name (null if none)
     * @param supervisorResults supervisor check results keyed by file path
     * @param outputPath        where to write the log file
     */
    public static void writeLog(TransformationResult result, String scope,
                                String branchName,
                                Map<String, TransformSupervisor.FileCheckResult> supervisorResults,
                                Path outputPath) {
        try {
            String content = generate(result, scope, branchName, supervisorResults);
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, content);
            log.info("Musketeer Log written to: {}", outputPath);
        } catch (IOException e) {
            log.warn("Failed to write Musketeer Log to {}: {}", outputPath, e.getMessage());
        }
    }

    // ========================================================================
    // Formatting helpers
    // ========================================================================

    private static String formatBefore(TransformationEntry entry) {
        // Prefer actual source line when available
        if (entry.originalLine() != null) {
            return entry.originalLine();
        }

        if (entry.sourceKind() == ConstantTarget.SourceKind.PARAMETER) {
            return "props.getProperty(\"%s\")".formatted(entry.constantName());
        }

        if (entry.message() != null && entry.message().startsWith("Removed")) {
            return "public static final %s %s = %s;".formatted(
                entry.originalType(), entry.constantName(), entry.originalValue());
        }

        return "%s.%s".formatted(entry.className(), entry.constantName());
    }

    private static String formatAfter(TransformationEntry entry) {
        // Prefer actual source line when available
        if (entry.transformedLine() != null) {
            return entry.transformedLine();
        }

        if (entry.message() != null && entry.message().startsWith("Removed")) {
            return "// removed";
        }

        String method = ConfigLookupTransformer.chooseLookupMethod(entry.originalType());
        String key = entry.propertyKey();

        if ("lookup".equals(method)) {
            return "config.lookup(\"%s\")".formatted(key);
        } else {
            return "config.%s(\"%s\", %s)".formatted(method, key, entry.originalValue());
        }
    }

    private static String extractFileName(String filePath) {
        int lastSep = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return lastSep >= 0 ? filePath.substring(lastSep + 1) : filePath;
    }

    private static String toAnchor(String text) {
        return text.toLowerCase()
            .replaceAll("[^a-z0-9\\s-]", "")
            .replaceAll("\\s+", "-")
            .replaceAll("-+", "-");
    }

    private static String statusEmoji(TransformationEntry.Status status) {
        return switch (status) {
            case TRANSFORMED -> "✅";
            case SKIPPED -> "⏭️";
            case FAILED -> "❌";
        };
    }
}
