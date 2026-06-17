package com.boeing.constcatalog.parser;

import com.boeing.constcatalog.model.MicroServiceInfo;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects whether a Java class is a microservice by inspecting class-level
 * and method-level annotations and superclass patterns.
 *
 * The annotation-to-type map is ordered by priority (highest first).
 * If a class has multiple microservice annotations, the highest-priority type wins.
 */
public class MicroServiceDetector {
    private static final Logger logger = LoggerFactory.getLogger(MicroServiceDetector.class);

    /**
     * Class-level annotations mapped to microservice type, in priority order.
     */
    private static final Map<String, String> CLASS_ANNOTATIONS = new LinkedHashMap<>();
    static {
        CLASS_ANNOTATIONS.put("SpringBootApplication", "SPRING_BOOT");
        CLASS_ANNOTATIONS.put("RestController", "REST_CONTROLLER");
        CLASS_ANNOTATIONS.put("Path", "REST_CONTROLLER");
        CLASS_ANNOTATIONS.put("FeignClient", "FEIGN_CLIENT");
        CLASS_ANNOTATIONS.put("GrpcService", "GRPC_SERVICE");
        CLASS_ANNOTATIONS.put("EnableEurekaClient", "EUREKA_CLIENT");
        CLASS_ANNOTATIONS.put("EnableDiscoveryClient", "EUREKA_CLIENT");
    }

    /**
     * Method-level annotations mapped to microservice type, in priority order.
     */
    private static final Map<String, String> METHOD_ANNOTATIONS = new LinkedHashMap<>();
    static {
        METHOD_ANNOTATIONS.put("KafkaListener", "KAFKA_LISTENER");
        METHOD_ANNOTATIONS.put("Scheduled", "SCHEDULED_SERVICE");
    }

    /**
     * Detect microservice type from a parsed CompilationUnit.
     * Checks class annotations, method annotations, and superclass patterns.
     */
    public MicroServiceInfo detect(CompilationUnit cu) {
        List<ClassOrInterfaceDeclaration> declarations = cu.findAll(ClassOrInterfaceDeclaration.class);

        for (ClassOrInterfaceDeclaration declaration : declarations) {
            // Check class-level annotations (highest priority)
            for (AnnotationExpr annotation : declaration.getAnnotations()) {
                String annotationName = annotation.getNameAsString();
                String type = CLASS_ANNOTATIONS.get(annotationName);
                if (type != null) {
                    logger.debug("Detected {} via @{} on class {}", type, annotationName, declaration.getNameAsString());
                    return MicroServiceInfo.of(type);
                }
            }

            // Check superclass for gRPC pattern (*ImplBase)
            for (ClassOrInterfaceType extended : declaration.getExtendedTypes()) {
                String superName = extended.getNameAsString();
                if (superName.endsWith("ImplBase")) {
                    logger.debug("Detected GRPC_SERVICE via superclass {} on class {}", superName, declaration.getNameAsString());
                    return MicroServiceInfo.of("GRPC_SERVICE");
                }
            }

            // Check method-level annotations
            for (MethodDeclaration method : declaration.getMethods()) {
                for (AnnotationExpr annotation : method.getAnnotations()) {
                    String annotationName = annotation.getNameAsString();
                    String type = METHOD_ANNOTATIONS.get(annotationName);
                    if (type != null) {
                        logger.debug("Detected {} via @{} on method {}.{}", type, annotationName,
                            declaration.getNameAsString(), method.getNameAsString());
                        return MicroServiceInfo.of(type);
                    }
                }
            }
        }

        return MicroServiceInfo.none();
    }
}
