package com.boeing.constcatalog.export;

import com.boeing.constcatalog.model.Constant;
import com.boeing.constcatalog.model.ConstantUsage;
import com.boeing.constcatalog.model.Parameter;
import com.boeing.constcatalog.model.ParameterUsage;
import com.boeing.constcatalog.scanner.ProjectScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Exports scan results to CSV files formatted for Neo4J import.
 *
 * Neo4J import format:
 * - Node files have headers with :ID and :LABEL
 * - Relationship files have :START_ID, :END_ID, and :TYPE
 */
public class CsvExporter {

    private static final Logger logger = LoggerFactory.getLogger(CsvExporter.class);

    /**
     * Export all data to CSV files for Neo4J import.
     */
    public void exportForNeo4j(ProjectScanner.ScanResult result, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        // Export nodes
        exportConstants(result, outputDir.resolve("constants.csv"));
        exportParameters(result, outputDir.resolve("parameters.csv"));
        exportFiles(result, outputDir.resolve("files.csv"));

        // Export relationships
        exportConstantFileRelationships(result, outputDir.resolve("constant_in_file.csv"));
        exportParameterFileRelationships(result, outputDir.resolve("parameter_defined_in.csv"));
        exportParameterUsageRelationships(result, outputDir.resolve("parameter_used_in.csv"));
        exportConstantUsageRelationships(result, outputDir.resolve("constant_used_in.csv"));

        // Generate Neo4J import script
        generateImportScript(outputDir);

        logger.info("CSV files exported to: {}", outputDir);
    }

    /**
     * Export constants as nodes.
     */
    private void exportConstants(ProjectScanner.ScanResult result, Path outputFile) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            // Neo4J header format
            writer.write("constantId:ID,name,type,value,className,accessModifier,isEnum:boolean,enumClassName,:LABEL");
            writer.newLine();

            for (Constant c : result.constants()) {
                String id = generateConstantId(c);
                String escapedValue = escapeCSV(c.value());
                writer.write(String.format("%s,%s,%s,%s,%s,%s,%b,%s,Constant",
                    id,
                    escapeCSV(c.name()),
                    escapeCSV(c.type()),
                    escapedValue,
                    escapeCSV(c.className()),
                    escapeCSV(c.accessModifier()),
                    c.isEnum(),
                    escapeCSV(c.enumClassName() != null ? c.enumClassName() : "")
                ));
                writer.newLine();
            }
        }
        logger.info("Exported {} constants to {}", result.constants().size(), outputFile);
    }

    /**
     * Export parameters as nodes.
     */
    private void exportParameters(ProjectScanner.ScanResult result, Path outputFile) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write("parameterId:ID,name,value,:LABEL");
            writer.newLine();

            for (Parameter p : result.parameters()) {
                String id = generateParameterId(p);
                writer.write(String.format("%s,%s,%s,Parameter",
                    id,
                    escapeCSV(p.name()),
                    escapeCSV(p.value())
                ));
                writer.newLine();
            }
        }
        logger.info("Exported {} parameters to {}", result.parameters().size(), outputFile);
    }

    /**
     * Export unique files as nodes.
     */
    private void exportFiles(ProjectScanner.ScanResult result, Path outputFile) throws IOException {
        Set<String> files = new HashSet<>();

        // Collect all unique files
        for (Constant c : result.constants()) {
            files.add(c.fileName());
        }
        for (Parameter p : result.parameters()) {
            files.add(p.definitionFile());
        }
        for (ParameterUsage u : result.parameterUsages()) {
            files.add(u.usedInFile());
        }
        for (ConstantUsage u : result.constantUsages()) {
            files.add(u.usedInFile());
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write("fileId:ID,path,fileName,:LABEL");
            writer.newLine();

            for (String filePath : files) {
                String id = generateFileId(filePath);
                String fileName = Path.of(filePath).getFileName().toString();
                writer.write(String.format("%s,%s,%s,File",
                    id,
                    escapeCSV(filePath),
                    escapeCSV(fileName)
                ));
                writer.newLine();
            }
        }
        logger.info("Exported {} files to {}", files.size(), outputFile);
    }

    /**
     * Export DEFINED_IN relationships between constants and files.
     */
    private void exportConstantFileRelationships(ProjectScanner.ScanResult result, Path outputFile) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write(":START_ID,:END_ID,lineNumber:int,:TYPE");
            writer.newLine();

            for (Constant c : result.constants()) {
                String constantId = generateConstantId(c);
                String fileId = generateFileId(c.fileName());
                writer.write(String.format("%s,%s,%d,DEFINED_IN",
                    constantId, fileId, c.lineNumber()
                ));
                writer.newLine();
            }
        }
        logger.info("Exported constant-file relationships to {}", outputFile);
    }

    /**
     * Export DEFINED_IN relationships between parameters and files.
     */
    private void exportParameterFileRelationships(ProjectScanner.ScanResult result, Path outputFile) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write(":START_ID,:END_ID,lineNumber:int,:TYPE");
            writer.newLine();

            for (Parameter p : result.parameters()) {
                String parameterId = generateParameterId(p);
                String fileId = generateFileId(p.definitionFile());
                writer.write(String.format("%s,%s,%d,DEFINED_IN",
                    parameterId, fileId, p.lineNumber()
                ));
                writer.newLine();
            }
        }
        logger.info("Exported parameter-file relationships to {}", outputFile);
    }

    /**
     * Export USED_IN relationships between parameters and files.
     */
    private void exportParameterUsageRelationships(ProjectScanner.ScanResult result, Path outputFile) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write(":START_ID,:END_ID,lineNumber:int,context,:TYPE");
            writer.newLine();

            for (ParameterUsage u : result.parameterUsages()) {
                // Find the parameter ID (need to match by name)
                String parameterId = "param_" + sanitizeId(u.parameterName());
                String fileId = generateFileId(u.usedInFile());
                writer.write(String.format("%s,%s,%d,%s,USED_IN",
                    parameterId, fileId, u.lineNumber(), escapeCSV(u.context())
                ));
                writer.newLine();
            }
        }
        logger.info("Exported parameter usage relationships to {}", outputFile);
    }

    /**
     * Export USED_IN relationships between constants and files.
     */
    private void exportConstantUsageRelationships(ProjectScanner.ScanResult result, Path outputFile) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write(":START_ID,:END_ID,lineNumber:int,className,context,:TYPE");
            writer.newLine();

            for (ConstantUsage u : result.constantUsages()) {
                String constantId = "const_" + sanitizeId(u.definingClassName() + "_" + u.constantName());
                String fileId = generateFileId(u.usedInFile());
                writer.write(String.format("%s,%s,%d,%s,%s,USED_IN",
                    constantId, fileId, u.lineNumber(),
                    escapeCSV(u.usedInClass()), escapeCSV(u.context())
                ));
                writer.newLine();
            }
        }
        logger.info("Exported constant usage relationships to {}", outputFile);
    }

    /**
     * Generate Neo4J import script.
     */
    private void generateImportScript(Path outputDir) throws IOException {
        Path scriptFile = outputDir.resolve("import-neo4j.cypher");

        try (BufferedWriter writer = Files.newBufferedWriter(scriptFile, StandardCharsets.UTF_8)) {
            writer.write("""
                // Neo4J Import Script for Constants and Parameters Catalog
                // Run this script in Neo4J Browser or use neo4j-admin import

                // ============================================
                // Option 1: Using LOAD CSV (for existing database)
                // ============================================

                // Create constraints for better performance
                CREATE CONSTRAINT IF NOT EXISTS FOR (c:Constant) REQUIRE c.constantId IS UNIQUE;
                CREATE CONSTRAINT IF NOT EXISTS FOR (p:Parameter) REQUIRE p.parameterId IS UNIQUE;
                CREATE CONSTRAINT IF NOT EXISTS FOR (f:File) REQUIRE f.fileId IS UNIQUE;

                // Load Files
                LOAD CSV WITH HEADERS FROM 'file:///files.csv' AS row
                CREATE (f:File {
                    fileId: row.fileId,
                    path: row.path,
                    fileName: row.fileName
                });

                // Load Constants
                LOAD CSV WITH HEADERS FROM 'file:///constants.csv' AS row
                CREATE (c:Constant {
                    constantId: row.constantId,
                    name: row.name,
                    type: row.type,
                    value: row.value,
                    className: row.className
                });

                // Load Parameters
                LOAD CSV WITH HEADERS FROM 'file:///parameters.csv' AS row
                CREATE (p:Parameter {
                    parameterId: row.parameterId,
                    name: row.name,
                    value: row.value
                });

                // Load Constant-File relationships
                LOAD CSV WITH HEADERS FROM 'file:///constant_in_file.csv' AS row
                MATCH (c:Constant {constantId: row.`:START_ID`})
                MATCH (f:File {fileId: row.`:END_ID`})
                CREATE (c)-[:DEFINED_IN {lineNumber: toInteger(row.`lineNumber:int`)}]->(f);

                // Load Parameter-File relationships (definitions)
                LOAD CSV WITH HEADERS FROM 'file:///parameter_defined_in.csv' AS row
                MATCH (p:Parameter {parameterId: row.`:START_ID`})
                MATCH (f:File {fileId: row.`:END_ID`})
                CREATE (p)-[:DEFINED_IN {lineNumber: toInteger(row.`lineNumber:int`)}]->(f);

                // Load Parameter-File relationships (usages)
                LOAD CSV WITH HEADERS FROM 'file:///parameter_used_in.csv' AS row
                MATCH (p:Parameter {parameterId: row.`:START_ID`})
                MATCH (f:File {fileId: row.`:END_ID`})
                CREATE (p)-[:USED_IN {lineNumber: toInteger(row.`lineNumber:int`), context: row.context}]->(f);

                // Load Constant-File relationships (usages)
                LOAD CSV WITH HEADERS FROM 'file:///constant_used_in.csv' AS row
                MATCH (c:Constant {constantId: row.`:START_ID`})
                MATCH (f:File {fileId: row.`:END_ID`})
                CREATE (c)-[:USED_IN {lineNumber: toInteger(row.`lineNumber:int`), className: row.className, context: row.context}]->(f);

                // ============================================
                // Useful Queries
                // ============================================

                // Find all constants in a specific file
                // MATCH (c:Constant)-[:DEFINED_IN]->(f:File) WHERE f.fileName = 'MyClass.java' RETURN c;

                // Find where a parameter is used
                // MATCH (p:Parameter)-[:USED_IN]->(f:File) WHERE p.name = 'database.host' RETURN p, f;

                // Find parameters that might change (hosts, ports, URLs)
                // MATCH (p:Parameter) WHERE p.name CONTAINS 'host' OR p.name CONTAINS 'port' OR p.name CONTAINS 'url' RETURN p;

                // Find all constants of a specific type
                // MATCH (c:Constant) WHERE c.type = 'String' RETURN c;
                """);
        }

        logger.info("Generated Neo4J import script: {}", scriptFile);
    }

    private String generateConstantId(Constant c) {
        return "const_" + sanitizeId(c.className() + "_" + c.name());
    }

    private String generateParameterId(Parameter p) {
        return "param_" + sanitizeId(p.name());
    }

    private String generateFileId(String filePath) {
        return "file_" + sanitizeId(filePath);
    }

    private String sanitizeId(String input) {
        return input.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String escapeCSV(String value) {
        if (value == null) return "";
        // Escape quotes by doubling them and wrap in quotes if contains special chars
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
