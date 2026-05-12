package com.boeing.constcatalog.model;

/**
 * Represents a constant found in a Java file.
 * Constants are public static final fields and enum constants.
 */
public record Constant(
    String name,
    String type,
    String value,
    String fileName,
    String className,
    int lineNumber,
    String accessModifier,
    boolean isEnum,
    String enumClassName
) {
    /**
     * Convenience constructor for non-enum constants (backward compatible).
     */
    public Constant(String name, String type, String value, String fileName,
                    String className, int lineNumber) {
        this(name, type, value, fileName, className, lineNumber, "public", false, null);
    }

    public String toCsvLine() {
        String escapedValue = value != null ? value.replace("\"", "\"\"") : "";
        String escapedEnumClass = enumClassName != null ? enumClassName : "";
        return String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%d,\"%s\",%b,\"%s\"",
            name, type, escapedValue, fileName, className, lineNumber,
            accessModifier, isEnum, escapedEnumClass);
    }

    public static String csvHeader() {
        return "name,type,value,fileName,className,lineNumber,accessModifier,isEnum,enumClassName";
    }
}
