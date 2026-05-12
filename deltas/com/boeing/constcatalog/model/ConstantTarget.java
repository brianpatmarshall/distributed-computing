package com.boeing.constcatalog.model;

/**
 * Represents a constant or parameter that should be transformed into a configuration lookup.
 * Pulled from Neo4j with file location and derived property key.
 *
 * @param constantName  the original constant name or parameter name
 * @param propertyKey   the derived property key (e.g., db.host)
 * @param originalValue the hardcoded value being replaced
 * @param originalType  the Java type (e.g., String, int)
 * @param className     the class containing the constant or usage
 * @param lineNumber    the line number in the source file
 * @param sourceKind    whether this target is a CONSTANT or PARAMETER
 */
public record ConstantTarget(
    String constantName,
    String propertyKey,
    String originalValue,
    String originalType,
    String className,
    int lineNumber,
    SourceKind sourceKind
) {

    public enum SourceKind {
        CONSTANT,
        PARAMETER
    }

    /**
     * Backward-compatible constructor that defaults sourceKind to CONSTANT.
     */
    public ConstantTarget(String constantName, String propertyKey, String originalValue,
                          String originalType, String className, int lineNumber) {
        this(constantName, propertyKey, originalValue, originalType, className, lineNumber,
             SourceKind.CONSTANT);
    }

    /**
     * Derives a property key from a SCREAMING_SNAKE_CASE constant name.
     * Example: DB_HOST -> db.host, API_BASE_URL -> api.base.url
     */
    public static String derivePropertyKey(String constantName) {
        return constantName.toLowerCase().replace("_", ".");
    }

    /**
     * Derives a camelCase field name from a SCREAMING_SNAKE_CASE constant name.
     * Example: DB_HOST -> dbHost, API_BASE_URL -> apiBaseUrl
     */
    public static String deriveCamelCaseName(String constantName) {
        String[] parts = constantName.toLowerCase().split("_");
        if (parts.length == 1) return parts[0];

        var sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)));
                sb.append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }
}
