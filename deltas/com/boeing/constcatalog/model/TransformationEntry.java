package com.boeing.constcatalog.model;

/**
 * Records the outcome of a single constant-to-lookup transformation.
 *
 * @param filePath        the source file that was modified
 * @param className       the class containing the constant
 * @param constantName    the original constant name (e.g., DB_HOST)
 * @param propertyKey     the derived property key (e.g., db.host)
 * @param originalValue   the value that was hardcoded
 * @param originalType    the Java type of the constant
 * @param lineNumber      where the constant was found
 * @param status          outcome of the transformation
 * @param message         human-readable description of what happened
 * @param sourceKind      whether this entry is a CONSTANT or PARAMETER
 * @param backupFilePath  path to the .orig backup file (null for SKIPPED/FAILED)
 * @param originalLine    the actual source line before transformation (null if not captured)
 * @param transformedLine the actual source line after transformation (null if not captured)
 */
public record TransformationEntry(
    String filePath,
    String className,
    String constantName,
    String propertyKey,
    String originalValue,
    String originalType,
    int lineNumber,
    Status status,
    String message,
    ConstantTarget.SourceKind sourceKind,
    String backupFilePath,
    String originalLine,
    String transformedLine
) {

    /** Backward-compatible constructor without originalLine/transformedLine. */
    public TransformationEntry(String filePath, String className, String constantName,
                               String propertyKey, String originalValue, String originalType,
                               int lineNumber, Status status, String message,
                               ConstantTarget.SourceKind sourceKind, String backupFilePath) {
        this(filePath, className, constantName, propertyKey, originalValue, originalType,
             lineNumber, status, message, sourceKind, backupFilePath, null, null);
    }

    /** Backward-compatible constructor without backupFilePath or lines. */
    public TransformationEntry(String filePath, String className, String constantName,
                               String propertyKey, String originalValue, String originalType,
                               int lineNumber, Status status, String message,
                               ConstantTarget.SourceKind sourceKind) {
        this(filePath, className, constantName, propertyKey, originalValue, originalType,
             lineNumber, status, message, sourceKind, null, null, null);
    }

    public enum Status {
        TRANSFORMED,
        SKIPPED,
        FAILED
    }

    public static TransformationEntry transformed(ConstantTarget target, String filePath,
                                                    String backupFilePath,
                                                    String originalLine, String transformedLine) {
        return new TransformationEntry(
            filePath, target.className(), target.constantName(), target.propertyKey(),
            target.originalValue(), target.originalType(), target.lineNumber(),
            Status.TRANSFORMED,
            "Replaced with config.lookup(\"%s\")".formatted(target.propertyKey()),
            target.sourceKind(),
            backupFilePath,
            originalLine,
            transformedLine
        );
    }

    public static TransformationEntry transformed(ConstantTarget target, String filePath, String backupFilePath) {
        return transformed(target, filePath, backupFilePath, null, null);
    }

    public static TransformationEntry transformed(ConstantTarget target, String filePath) {
        return transformed(target, filePath, null, null, null);
    }

    public static TransformationEntry skipped(ConstantTarget target, String filePath, String reason) {
        return new TransformationEntry(
            filePath, target.className(), target.constantName(), target.propertyKey(),
            target.originalValue(), target.originalType(), target.lineNumber(),
            Status.SKIPPED, reason, target.sourceKind()
        );
    }

    public static TransformationEntry failed(ConstantTarget target, String filePath, String reason) {
        return new TransformationEntry(
            filePath, target.className(), target.constantName(), target.propertyKey(),
            target.originalValue(), target.originalType(), target.lineNumber(),
            Status.FAILED, reason, target.sourceKind()
        );
    }
}
