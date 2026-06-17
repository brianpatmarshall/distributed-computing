package com.boeing.constcatalog.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/*
 * Main entry point for the Constants Catalog Backend.
 *
 * This Spring Boot application provides a REST API for scanning Java projects
 * and querying the resulting graph database. It connects to Neo4J for persistent
 * storage of constants, parameters, and their relationships.
 *
 * The application exposes three main API surfaces:
 * - /api/scan: Trigger project scans
 * - /api/query: Execute pre-canned and ad-hoc Cypher queries
 * - /api/stats: Retrieve database statistics
 *
 * Configuration is managed through application.yml and environment variables.
 */
@SpringBootApplication
@EnableAsync
public class ConstCatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConstCatalogApplication.class, args);
    }
}
