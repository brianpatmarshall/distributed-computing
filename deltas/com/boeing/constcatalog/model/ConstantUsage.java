package com.boeing.constcatalog.model;

/**
 * Represents a usage of a constant in a Java file.
 */
public record ConstantUsage(
    String constantName,
    String definingClassName,
    String usedInFile,
    String usedInClass,
    int lineNumber,
    String context
) {
    public String toCsvLine() {
        String escapedContext = context != null ? context.replace("\"", "\"\"") : "";
        return String.format("\"%s\",\"%s\",\"%s\",\"%s\",%d,\"%s\"",
            constantName, definingClassName, usedInFile, usedInClass, lineNumber, escapedContext);
    }

    public static String csvHeader() {
        return "constantName,definingClassName,usedInFile,usedInClass,lineNumber,context";
    }
}
