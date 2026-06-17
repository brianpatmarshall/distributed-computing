package com.boeing.constcatalog.parser;

import com.boeing.constcatalog.model.Parameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Parses property files (.properties, .yml, .yaml, .xml config files)
 * to extract parameters.
 */
public class PropertyFileParser {

    private static final Logger logger = LoggerFactory.getLogger(PropertyFileParser.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse a property file and extract all parameters.
     */
    public List<Parameter> parseParameters(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".properties")) {
            return parsePropertiesFile(filePath);
        } else if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
            return parseYamlFile(filePath);
        } else if (fileName.endsWith(".xml")) {
            return parseXmlConfigFile(filePath);
        } else if (fileName.endsWith(".env")) {
            return parseEnvFile(filePath);
        } else if (fileName.endsWith(".json")) {
            return parseJsonFile(filePath);
        }

        return List.of();
    }

    /**
     * Parse a standard .properties file.
     */
    private List<Parameter> parsePropertiesFile(Path filePath) {
        List<Parameter> parameters = new ArrayList<>();
        String filePathStr = filePath.toString();

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            StringBuilder continuedLine = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmedLine = line.trim();

                // Skip comments and empty lines
                if (trimmedLine.isEmpty() || trimmedLine.startsWith("#") || trimmedLine.startsWith("!")) {
                    continue;
                }

                // Handle line continuation
                if (trimmedLine.endsWith("\\")) {
                    continuedLine.append(trimmedLine, 0, trimmedLine.length() - 1);
                    continue;
                }

                String fullLine = continuedLine + trimmedLine;
                continuedLine.setLength(0);

                // Parse key=value or key:value
                int separatorIndex = findSeparator(fullLine);
                if (separatorIndex > 0) {
                    String key = fullLine.substring(0, separatorIndex).trim();
                    String value = fullLine.substring(separatorIndex + 1).trim();

                    parameters.add(new Parameter(key, value, filePathStr, lineNumber));
                }
            }
        } catch (IOException e) {
            logger.error("Error reading properties file: {}", filePath, e);
        }

        return parameters;
    }

    /**
     * Find the separator (= or :) in a property line.
     */
    private int findSeparator(String line) {
        int equalsIndex = line.indexOf('=');
        int colonIndex = line.indexOf(':');

        if (equalsIndex < 0) return colonIndex;
        if (colonIndex < 0) return equalsIndex;
        return Math.min(equalsIndex, colonIndex);
    }

    /**
     * Parse a YAML file using SnakeYAML library.
     * Supports all YAML features including:
     * - Nested maps
     * - Arrays (block and flow style)
     * - Flow maps
     * - Multi-document YAML
     * - Anchors and aliases
     */
    private List<Parameter> parseYamlFile(Path filePath) {
        List<Parameter> parameters = new ArrayList<>();
        String filePathStr = filePath.toString();

        try (InputStream inputStream = Files.newInputStream(filePath)) {
            Yaml yaml = new Yaml();
            Object document = yaml.load(inputStream);

            if (document instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> root = (Map<String, Object>) document;
                flattenYamlMap(root, "", parameters, filePathStr);
            }
        } catch (IOException e) {
            logger.error("Error reading YAML file: {}", filePath, e);
        } catch (Exception e) {
            logger.error("Error parsing YAML file: {}", filePath, e);
        }

        return parameters;
    }

    /**
     * Recursively flatten a YAML map structure into dot-notation parameters.
     *
     * @param map        The current map to process
     * @param prefix     The current key prefix (dot-notation path)
     * @param parameters The list to add parameters to
     * @param filePath   The source file path
     */
    private void flattenYamlMap(Map<String, Object> map, String prefix, List<Parameter> parameters, String filePath) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;

            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                flattenYamlMap(nestedMap, fullKey, parameters, filePath);
            } else if (value instanceof Collection) {
                flattenYamlCollection((Collection<?>) value, fullKey, parameters, filePath);
            } else {
                // Scalar value (String, Number, Boolean, null)
                String stringValue = value != null ? value.toString() : "";
                parameters.add(new Parameter(fullKey, stringValue, filePath, -1));
            }
        }
    }

    /**
     * Flatten a YAML collection (array/list) into indexed parameters.
     *
     * @param collection The collection to process
     * @param prefix     The current key prefix
     * @param parameters The list to add parameters to
     * @param filePath   The source file path
     */
    private void flattenYamlCollection(Collection<?> collection, String prefix, List<Parameter> parameters, String filePath) {
        int index = 0;
        for (Object item : collection) {
            String indexedKey = prefix + "[" + index + "]";

            if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) item;
                flattenYamlMap(nestedMap, indexedKey, parameters, filePath);
            } else if (item instanceof Collection) {
                flattenYamlCollection((Collection<?>) item, indexedKey, parameters, filePath);
            } else {
                // Scalar value
                String stringValue = item != null ? item.toString() : "";
                parameters.add(new Parameter(indexedKey, stringValue, filePath, -1));
            }
            index++;
        }
    }

    /**
     * Parse a YAML file using simple line-by-line parsing.
     * <p>
     * <b>Note:</b> This method has limited YAML support. It does not handle:
     * <ul>
     *   <li>Flow sequences (inline arrays): {@code ports: [8080, 8443]}</li>
     *   <li>Flow maps (inline objects): {@code user: {name: "Bob"}}</li>
     *   <li>Arrays of objects</li>
     *   <li>Multi-line strings</li>
     *   <li>Anchors and aliases</li>
     * </ul>
     * Use {@link #parseYamlFile(Path)} instead for full YAML support.
     *
     * @param filePath the path to the YAML file
     * @return list of parameters extracted from the file
     * @deprecated Use {@link #parseYamlFile(Path)} which uses SnakeYAML for full YAML support.
     *             This method is retained for reference and simple use cases only.
     */
    @Deprecated(since = "1.1", forRemoval = false)
    private List<Parameter> parseYamlFileSimple(Path filePath) {
        List<Parameter> parameters = new ArrayList<>();
        String filePathStr = filePath.toString();

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            /*
             * Keep track of parent keys. For these, indentation level
             * increases by one. Here "components" and "api" are
             * parents
             * VIZ:
             * components:
             *   api:
             *     version: 2.3.1
             */
            List<String> keyPath = new ArrayList<>();
            /*
             * Indentation in yaml files is crucial. Add an
             * indentation level when we find a parent key
             */
            List<Integer> indentLevels = new ArrayList<>();
            /*
             * Track array index for the current array context.
             * When we encounter array items (- value), we increment this.
             */
            int arrayIndex = 0;
            int arrayIndent = -1;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                /*
                 * Skip comments and empty lines
                 */
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                /*
                 * Calculate indent level (first non-whitespace character)
                 * super important for yaml files
                 */
                int indent = 0;
                while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
                    indent++;
                }

                /*
                 * Handle YAML array items (lines starting with "- ")
                 * Examples:
                 *   public:
                 *     - /metrics
                 *     - /health
                 */
                if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                    // Check if we're starting a new array or continuing one
                    if (arrayIndent != indent) {
                        arrayIndex = 0;
                        arrayIndent = indent;
                    }

                    // Adjust keyPath based on indentation
                    while (!indentLevels.isEmpty() && indent <= indentLevels.getLast()) {
                        indentLevels.removeLast();
                        keyPath.removeLast();
                    }

                    String arrayValue = trimmed.length() > 2 ? trimmed.substring(2).trim() : "";

                    // Remove quotes from value if present
                    if ((arrayValue.startsWith("\"") && arrayValue.endsWith("\"")) ||
                        (arrayValue.startsWith("'") && arrayValue.endsWith("'"))) {
                        arrayValue = arrayValue.substring(1, arrayValue.length() - 1);
                    }

                    if (!arrayValue.isEmpty()) {
                        // Create key with array index, e.g., "endpoints.public[0]"
                        String fullKey = keyPath.isEmpty() ? "[" + arrayIndex + "]" : String.join(".", keyPath) + "[" + arrayIndex + "]";
                        parameters.add(new Parameter(fullKey, arrayValue, filePathStr, lineNumber));
                    }
                    arrayIndex++;
                    continue;
                }

                // Reset array tracking when we move out of array context
                if (indent <= arrayIndent) {
                    arrayIndent = -1;
                    arrayIndex = 0;
                }

                /*
                 * Find key:value
                 */
                int colonIndex = trimmed.indexOf(':');
                if (colonIndex > 0) {
                    String key = trimmed.substring(0, colonIndex).trim();
                    String value = trimmed.substring(colonIndex + 1).trim();
                    /*
                     * The current key doesn't belong the current parent
                     */
                    while (!indentLevels.isEmpty() && indent <= indentLevels.getLast()) {
                        indentLevels.removeLast();
                        keyPath.removeLast();
                    }
                    /*
                     * value is not a flow map (e.g. user: {name: "Bruno", age: 12})
                     * value is not a flow sequence (.e.g ages: [32, 34, 37])
                     */
                    if (!value.isEmpty() && !value.startsWith("{") && !value.startsWith("[")) {
                        /*
                         * This is a leaf value, meaning we have a line with <spaces*indentationLevel>key:value
                         */
                        String fullKey = keyPath.isEmpty() ? key : String.join(".", keyPath) + "." + key;
                        // Remove quotes from value if present
                        if ((value.
                              startsWith("\"") && value.endsWith("\"")) ||
                            (value.startsWith("'") && value.endsWith("'"))) {
                            value = value.substring(1, value.length() - 1);
                        }
                        parameters.add(new Parameter(fullKey, value, filePathStr, lineNumber));
                    } else if (value.isEmpty()) {
                        /*
                         * key is a parent
                         * application:
                         *   name: F11
                         *   components:
                         *     eclipse:
                         *        version: 1.1
                         * Indentation increases
                         */
                        keyPath.add(key);
                        indentLevels.add(indent);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Error reading YAML file: {}", filePath, e);
        }

        return parameters;
    }

    /**
     * Parse a JSON file using Jackson.
     * Recursively flattens JSON objects to dot-notation keys.
     */
    private List<Parameter> parseJsonFile(Path filePath) {
        List<Parameter> parameters = new ArrayList<>();
        String filePathStr = filePath.toString();

        try {
            JsonNode rootNode = objectMapper.readTree(filePath.toFile());
            flattenJsonNode(rootNode, "", parameters, filePathStr);
        } catch (IOException e) {
            logger.error("Error reading JSON file: {}", filePath, e);
        }

        return parameters;
    }

    private void flattenJsonNode(JsonNode node, String prefix, List<Parameter> parameters, String filePath) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fullKey = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                flattenJsonNode(entry.getValue(), fullKey, parameters, filePath);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String indexedKey = prefix + "[" + i + "]";
                flattenJsonNode(node.get(i), indexedKey, parameters, filePath);
            }
        } else {
            // Scalar value
            String value = node.isNull() ? "" : node.asText();
            parameters.add(new Parameter(prefix, value, filePath, -1));
        }
    }

    /**
     * Parse XML config files (looking for property-like elements).
     */
    private List<Parameter> parseXmlConfigFile(Path filePath) {
        List<Parameter> parameters = new ArrayList<>();
        String filePathStr = filePath.toString();

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // Look for property patterns like:
                // <property name="xxx" value="yyy"/>
                // <entry key="xxx">yyy</entry>
                // <property name="xxx">yyy</property>

                if (line.contains("property") || line.contains("entry") || line.contains("param")) {
                    String name = extractXmlAttribute(line, "name");
                    if (name == null) name = extractXmlAttribute(line, "key");

                    String value = extractXmlAttribute(line, "value");
                    if (value == null) value = extractXmlElementValue(line);

                    if (name != null && !name.isEmpty()) {
                        parameters.add(new Parameter(name, value != null ? value : "", filePathStr, lineNumber));
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Error reading XML file: {}", filePath, e);
        }

        return parameters;
    }

    /**
     * Parse .env files.
     */
    private List<Parameter> parseEnvFile(Path filePath) {
        List<Parameter> parameters = new ArrayList<>();
        String filePathStr = filePath.toString();

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();

                // Skip comments and empty lines
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int equalsIndex = trimmed.indexOf('=');
                if (equalsIndex > 0) {
                    String key = trimmed.substring(0, equalsIndex).trim();
                    String value = trimmed.substring(equalsIndex + 1).trim();

                    // Remove quotes if present
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }

                    parameters.add(new Parameter(key, value, filePathStr, lineNumber));
                }
            }
        } catch (IOException e) {
            logger.error("Error reading env file: {}", filePath, e);
        }

        return parameters;
    }

    private String extractXmlAttribute(String line, String attributeName) {
        String pattern = attributeName + "=\"";
        int startIndex = line.indexOf(pattern);
        if (startIndex < 0) {
            pattern = attributeName + "='";
            startIndex = line.indexOf(pattern);
        }

        if (startIndex >= 0) {
            startIndex += pattern.length();
            char quote = pattern.charAt(pattern.length() - 1);
            int endIndex = line.indexOf(quote, startIndex);
            if (endIndex > startIndex) {
                return line.substring(startIndex, endIndex);
            }
        }
        return null;
    }

    private String extractXmlElementValue(String line) {
        int startTag = line.indexOf('>');
        int endTag = line.indexOf("</");
        if (startTag >= 0 && endTag > startTag + 1) {
            return line.substring(startTag + 1, endTag).trim();
        }
        return null;
    }
}
