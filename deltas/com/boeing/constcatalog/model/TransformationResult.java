package com.boeing.constcatalog.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregated result of a batch code transformation operation.
 *
 * @param projectPath   the project that was transformed
 * @param transformedAt when the transformation ran
 * @param entries       individual transformation outcomes
 */
public record TransformationResult(
    String projectPath,
    LocalDateTime transformedAt,
    List<TransformationEntry> entries
) {

    public long totalTransformed() {
        return entries.stream()
            .filter(e -> e.status() == TransformationEntry.Status.TRANSFORMED)
            .count();
    }

    public long totalSkipped() {
        return entries.stream()
            .filter(e -> e.status() == TransformationEntry.Status.SKIPPED)
            .count();
    }

    public long totalFailed() {
        return entries.stream()
            .filter(e -> e.status() == TransformationEntry.Status.FAILED)
            .count();
    }

    public Map<TransformationEntry.Status, List<TransformationEntry>> groupedByStatus() {
        return entries.stream()
            .collect(Collectors.groupingBy(TransformationEntry::status));
    }

    public List<TransformationEntry> transformedEntries() {
        return entries.stream()
            .filter(e -> e.status() == TransformationEntry.Status.TRANSFORMED)
            .toList();
    }

    public List<TransformationEntry> failedEntries() {
        return entries.stream()
            .filter(e -> e.status() == TransformationEntry.Status.FAILED)
            .toList();
    }
}
