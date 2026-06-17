package com.boeing.constcatalog.scanner;

import com.boeing.constcatalog.model.Constant;
import com.boeing.constcatalog.model.ConstantUsage;
import com.boeing.constcatalog.model.MicroServiceInfo;
import com.boeing.constcatalog.model.Parameter;
import com.boeing.constcatalog.model.ParameterUsage;
import com.boeing.constcatalog.parser.JavaFileParser;
import com.boeing.constcatalog.parser.PropertyFileParser;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scans a Java project directory to find all Java and property files,
 * then extracts constants and parameters.
 */
@Slf4j
public class ProjectScanner {

    /**
     * Listener for scan progress updates.
     */
    @FunctionalInterface
    public interface ScanProgressListener {
        void onProgress(String fileName, String filePath, int fileIndex, int totalFiles,
                        int constantsFound, int parametersFound);
    }

    private final JavaFileParser javaParser;
    private final PropertyFileParser propertyParser;

    // Directories to skip
    private static final Set<String> EXCLUDED_DIRS = Set.of(
        "target", "build", "out", ".git", ".idea", ".gradle",
        "node_modules", ".mvn", "bin", ".settings"
    );

    // File extensions for property files
    private static final Set<String> PROPERTY_EXTENSIONS = Set.of(
        ".properties", ".yml", ".yaml", ".env", ".json", ".xml"
    );

    public ProjectScanner() {
        this.javaParser = new JavaFileParser();
        this.propertyParser = new PropertyFileParser();
    }

    /**
     * Per-extension file counts and public-static-final constant count.
     */
    public record FileTypeStats(
        int propertiesFiles, int ymlFiles, int xmlFiles,
        int jsonFiles, int envFiles, int publicStaticFinals
    ) {}

    /**
     * Result of scanning a project.
     */
    public record ScanResult(
        List<Constant> constants,
        List<Parameter> parameters,
        List<ParameterUsage> parameterUsages,
        List<ConstantUsage> constantUsages,
        Map<String, String> javaFileFqns,
        Map<String, MicroServiceInfo> microServiceInfos,
        int javaFilesScanned,
        int propertyFilesScanned,
        FileTypeStats fileTypeStats
    ) {}

    /**
     * Scan a project directory and extract all constants and parameters.
     */
    public ScanResult scan(Path projectRoot) throws IOException {
        return scan(projectRoot, null);
    }

    /**
     * Scan a project directory with progress reporting via a listener.
     */
    public ScanResult scan(Path projectRoot, ScanProgressListener listener) throws IOException {
        log.info("Starting scan of project: {}", projectRoot);

        List<Path> javaFiles = new ArrayList<>();
        List<Path> propertyFiles = new ArrayList<>();

        // Per-extension counters
        int[] propCount = {0}, ymlCount = {0}, xmlCount = {0}, jsonCount = {0}, envCount = {0};

        /*
         * Walk the directory tree
         */
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                if (EXCLUDED_DIRS.contains(dirName)) {
                    log.debug("Skipping excluded directory: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fileName = file.getFileName().toString().toLowerCase();

                if (fileName.endsWith(".java")) {
                    javaFiles.add(file);
                } else if (isPropertyFile(fileName)) {
                    propertyFiles.add(file);
                    // Track per-extension counts
                    if (fileName.endsWith(".properties")) propCount[0]++;
                    else if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) ymlCount[0]++;
                    else if (fileName.endsWith(".xml")) xmlCount[0]++;
                    else if (fileName.endsWith(".json")) jsonCount[0]++;
                    else if (fileName.endsWith(".env")) envCount[0]++;
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("Failed to access file: {}", file);
                return FileVisitResult.CONTINUE;
            }
        });

        log.info("Found {} Java files and {} property files", javaFiles.size(), propertyFiles.size());

        int totalFiles = javaFiles.size() + propertyFiles.size();
        int fileIndex = 0;
        /*
         * Now parse all files and collect constants and parameters
         */
        List<Constant> constants = new ArrayList<>();
        List<Parameter> parameters = new ArrayList<>();
        /*
         * Parse property files first to get known parameter names
         */
        for (Path propertyFile : propertyFiles) {
            fileIndex++;
            String fileName = propertyFile.getFileName().toString();
            log.debug("Parsing property file: {}", propertyFile);

            List<Parameter> fileParams = propertyParser.parseParameters(propertyFile);
            parameters.addAll(fileParams);
            log.debug("  Found {} parameters", fileParams.size());

            if (listener != null) {
                listener.onProgress(fileName, propertyFile.toString(), fileIndex, totalFiles,
                    constants.size(), parameters.size());
            }
        }
        /*
         * Get set of known parameter names for usage detection
         */
        Set<String> parameterNames = parameters.stream()
            .map(Parameter::name)
            .collect(Collectors.toSet());
        /*
         * Parse Java files
         */
        List<ParameterUsage> parameterUsages = new ArrayList<>();
        Map<String, String> javaFileFqns = new HashMap<>();
        Map<String, MicroServiceInfo> microServiceInfos = new HashMap<>();

        for (Path javaFile : javaFiles) {
            fileIndex++;
            String fileName = javaFile.getFileName().toString();
            log.debug("Parsing Java file: {}", javaFile);

            List<Constant> fileConstants = javaParser.parseConstants(javaFile);
            constants.addAll(fileConstants);
            log.debug("  Found {} constants", fileConstants.size());

            List<ParameterUsage> fileUsages = javaParser.parseParameterUsages(javaFile, parameterNames);
            parameterUsages.addAll(fileUsages);
            log.debug("  Found {} parameter usages", fileUsages.size());

            if (listener != null) {
                listener.onProgress(fileName, javaFile.toString(), fileIndex, totalFiles,
                    constants.size(), parameters.size());
            }

            String fqn = javaParser.extractFqn(javaFile);
            if (fqn != null) {
                javaFileFqns.put(javaFile.toString(), fqn);
            }

            MicroServiceInfo msInfo = javaParser.detectMicroService(javaFile);
            if (msInfo.isMicroService()) {
                microServiceInfos.put(javaFile.toString(), msInfo);
            }
        }

        /*
         * Build constant lookup map and detect constant usages
         */
        Map<String, Set<String>> constantsByName = new HashMap<>();
        for (Constant c : constants) {
            constantsByName.computeIfAbsent(c.name(), k -> new HashSet<>()).add(c.className());
        }

        List<ConstantUsage> constantUsages = new ArrayList<>();
        for (Path javaFile : javaFiles) {
            constantUsages.addAll(javaParser.parseConstantUsages(javaFile, constantsByName));
        }

        int publicStaticFinals = (int) constants.stream().filter(c -> !c.isEnum()).count();

        FileTypeStats fileTypeStats = new FileTypeStats(
            propCount[0], ymlCount[0], xmlCount[0],
            jsonCount[0], envCount[0], publicStaticFinals
        );

        log.info("Scan complete. Found {} constants ({} public static final), {} parameters, {} param usages, {} constant usages",
            constants.size(), publicStaticFinals, parameters.size(), parameterUsages.size(), constantUsages.size());

        return new ScanResult(
            constants,
            parameters,
            parameterUsages,
            constantUsages,
            javaFileFqns,
            microServiceInfos,
            javaFiles.size(),
            propertyFiles.size(),
            fileTypeStats
        );
    }

    private boolean isPropertyFile(String fileName) {
        return PROPERTY_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }
}
