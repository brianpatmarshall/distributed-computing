package com.boeing.constcatalog.model;

/**
 * Represents a usage of a parameter in a Java file.
 */
public record ParameterUsage(
    String parameterName,
    String usedInFile,
    String usedInClass,
    int lineNumber,
    String context
) {
    public String toCsvLine() {
        String escapedContext = context != null ? context.replace("\"", "\"\"") : "";
        return String.format("\"%s\",\"%s\",\"%s\",%d,\"%s\"",
            parameterName, usedInFile, usedInClass, lineNumber, escapedContext);
    }

    public static String csvHeader() {
        return "parameterName,usedInFile,usedInClass,lineNumber,context";
    }
}
