package com.boeing.constcatalog.model;

/**
 * Represents a parameter defined in a property file.
 */
public record Parameter(
    String name,
    String value,
    String definitionFile,
    int lineNumber
) {
    public String toCsvLine() {
        String escapedValue = value != null ? value.replace("\"", "\"\"") : "";
        return String.format("\"%s\",\"%s\",\"%s\",%d",
            name, escapedValue, definitionFile, lineNumber);
    }

    public static String csvHeader() {
        return "name,value,definitionFile,lineNumber";
    }
}
