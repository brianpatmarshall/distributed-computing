# Configuring the ConfigStore

This document describes how to configure the Constants Catalog's pluggable configuration store backend. The application supports two implementations — ValKey (default) and MongoDB — selected at deployment time through a single property. This guide covers installation, configuration, verification, testing, and troubleshooting for both backends.

**Audience:** Developers and operations engineers responsible for deploying and maintaining the Constants Catalog.

**Prerequisites:** Docker, Docker Compose, Java 21, Maven 3.9+.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Configuring ValKey (Default)](#2-configuring-valkey-default)
3. [Configuring MongoDB](#3-configuring-mongodb)
4. [Configuration Properties Reference](#4-configuration-properties-reference)
5. [Docker Compose Deployment](#5-docker-compose-deployment)
6. [application.yml Reference](#6-applicationyml-reference)
7. [Bean Selection Mechanism](#7-bean-selection-mechanism)
8. [Verifying the Active Store](#8-verifying-the-active-store)
9. [ConfigStore Interface Specification](#9-configstore-interface-specification)
10. [Caching Architecture](#10-caching-architecture)
11. [Running Integration Tests](#11-running-integration-tests)
12. [Deployment Configurations](#12-deployment-configurations)
13. [Troubleshooting](#13-troubleshooting)
14. [Appendix A: Configuring an External MongoDB](#appendix-a-configuring-an-external-mongodb)

---

## 1. Overview

The Constants Catalog uses a `ConfigStore` interface to abstract configuration persistence. Two implementations are provided:

- **ValKeyConfigStore** — A Redis-compatible in-memory key-value store with pub/sub support for distributed cache invalidation. This is the default backend.
- **MongoConfigStore** — A MongoDB document store providing disk-based persistence. Pub/sub is not supported in standalone deployments.

```mermaid
flowchart TD
    SCS["SiteConfigurationService"] --> CS["ConfigStore\n(interface)"]
    CS --> VK["ValKeyConfigStore\n(default)"]
    CS --> MG["MongoConfigStore\n(optional)"]

    style CS fill:#4488ff,color:#fff
    style VK fill:#ff8844,color:#fff
    style MG fill:#44aa44,color:#fff
```

Backend selection is controlled by a single property: `config.store.type`. The application code depends only on the `ConfigStore` interface and requires no changes when switching backends.

---

## 2. Configuring ValKey (Default)

ValKey is the default backend. No explicit configuration is required for standard deployments.

### Starting the Service

```bash
docker compose --env-file=.env up -d valkey
```

For WSL environments, use `--env-file=.env.wsl`.

### Verifying Connectivity

```bash
docker exec const-catalog-valkey valkey-cli PING
```

Expected response: `PONG`

### Default Configuration (application.yml)

```yaml
valkey:
  host: ${VALKEY_HOST:localhost}
  port: ${VALKEY_PORT:6379}
  password: ${VALKEY_PASSWORD:}
  database: ${VALKEY_DATABASE:0}
  timeout: ${VALKEY_TIMEOUT:5000}
```

Each property uses Spring Boot's `${ENV_VAR:default}` syntax. When deployed via Docker Compose, the backend service sets `VALKEY_HOST=valkey` to resolve the container hostname on the Docker network.

### Inspecting Stored Data

```bash
# List all configuration keys
docker exec const-catalog-valkey valkey-cli KEYS "config:*"

# Retrieve a specific value
docker exec const-catalog-valkey valkey-cli GET "config:db.host"
```

---

## 3. Configuring MongoDB

To use MongoDB, set `config.store.type=mongodb`. The MongoDB service runs under the `mongodb` Docker Compose profile and must be explicitly started.

### Starting the Service

```bash
docker compose --env-file=.env --profile=mongodb up -d mongo
```

### Verifying Connectivity

```bash
docker exec const-catalog-mongo mongosh --eval "db.adminCommand('ping')"
```

Expected response: `{ ok: 1 }`

### Configuration Methods

There are three approaches for setting `config.store.type=mongodb`:

**Method 1: docker-compose.yml environment section**

```yaml
backend:
  environment:
    - CONFIG_STORE_TYPE=mongodb
    - CONFIG_STORE_MONGODB_URI=mongodb://mongo:27017
    - CONFIG_STORE_MONGODB_DATABASE=const-catalog
```

Spring Boot maps `config.store.type` to the environment variable `CONFIG_STORE_TYPE` (dots converted to underscores, uppercased).

**Method 2: .env file**

```
CONFIG_STORE_TYPE=mongodb
CONFIG_STORE_MONGODB_URI=mongodb://mongo:27017
CONFIG_STORE_MONGODB_DATABASE=const-catalog
```

Reference these variables in docker-compose.yml:

```yaml
backend:
  environment:
    - CONFIG_STORE_TYPE=${CONFIG_STORE_TYPE:-valkey}
    - CONFIG_STORE_MONGODB_URI=${CONFIG_STORE_MONGODB_URI:-mongodb://mongo:27017}
    - CONFIG_STORE_MONGODB_DATABASE=${CONFIG_STORE_MONGODB_DATABASE:-const-catalog}
```

**Method 3: application.yml (local development)**

```yaml
config:
  store:
    type: mongodb
    mongodb:
      uri: mongodb://localhost:27017
      database: const-catalog
```

### Inspecting Stored Data

```bash
docker exec -it const-catalog-mongo mongosh --eval "
  use('const-catalog');
  db.config.find().toArray();
"
```

---

## 4. Configuration Properties Reference

### ValKey Properties

| Property | Environment Variable | Default | Description |
|---|---|---|---|
| `valkey.host` | `VALKEY_HOST` | `localhost` | ValKey server hostname |
| `valkey.port` | `VALKEY_PORT` | `6379` | ValKey server port |
| `valkey.password` | `VALKEY_PASSWORD` | *(empty)* | Authentication password |
| `valkey.database` | `VALKEY_DATABASE` | `0` | Redis database index |
| `valkey.timeout` | `VALKEY_TIMEOUT` | `5000` | Connection timeout (ms) |

### MongoDB Properties

| Property | Environment Variable | Default | Description |
|---|---|---|---|
| `config.store.type` | `CONFIG_STORE_TYPE` | `valkey` | Backend selection (`valkey` or `mongodb`) |
| `config.store.mongodb.uri` | `CONFIG_STORE_MONGODB_URI` | `mongodb://localhost:27017` | MongoDB connection URI |
| `config.store.mongodb.database` | `CONFIG_STORE_MONGODB_DATABASE` | `const-catalog` | Target database name |

### Caffeine Cache Properties (applicable to both backends)

| Property | Environment Variable | Default | Description |
|---|---|---|---|
| `cache.caffeine.max-size` | `CACHE_MAX_SIZE` | `10000` | Maximum cache entries |
| `cache.caffeine.expire-after-write-minutes` | `CACHE_EXPIRE_MINUTES` | `60` | Cache entry TTL (minutes) |

---

## 5. Docker Compose Deployment

### Standard deployment (ValKey)

```bash
docker compose --env-file=.env up -d
```

Starts neo4j, valkey, backend, and frontend. ValKey has no profile restriction and is always available.

### Deployment with both backends available

```bash
docker compose --env-file=.env --profile=mongodb up -d
```

Both ValKey and MongoDB are running. The backend uses the backend specified by `config.store.type`. This configuration is useful for running MongoDB integration tests while the application uses ValKey.

### Deployment with MongoDB as primary store

```bash
# Ensure .env contains: CONFIG_STORE_TYPE=mongodb
docker compose --env-file=.env --profile=mongodb up -d
```

**Note:** ValKey starts regardless of the selected backend, as it has no profile restriction. The ValKey connection beans are created but not used by `SiteConfigurationService` when MongoDB is selected. To prevent ValKey from starting, add a `profiles` entry to the valkey service definition in docker-compose.yml.

---

## 6. application.yml Reference

The default `application.yml` includes explicit ValKey configuration. MongoDB properties are resolved through `@Value` defaults in `ConfigStoreConfig.java`.

To make all configuration store properties explicit and self-documenting, add:

```yaml
config:
  store:
    type: ${CONFIG_STORE_TYPE:valkey}
    mongodb:
      uri: ${CONFIG_STORE_MONGODB_URI:mongodb://localhost:27017}
      database: ${CONFIG_STORE_MONGODB_DATABASE:const-catalog}
```

This block is optional. The defaults in `ConfigStoreConfig.java` provide identical behavior.

---

## 7. Bean Selection Mechanism

Backend selection is implemented in `ConfigStoreConfig.java` using Spring's `@ConditionalOnProperty` annotation:

```java
@Bean
@ConditionalOnProperty(name = "config.store.type", havingValue = "valkey", matchIfMissing = true)
public ConfigStore valKeyConfigStore(...) { ... }

@Bean
@ConditionalOnProperty(name = "config.store.type", havingValue = "mongodb")
public ConfigStore mongoConfigStore(...) { ... }
```

Spring evaluates these conditions during application context initialization:

```mermaid
flowchart TD
    S["Application startup"] --> P{"config.store.type"}
    P -->|"not set\n(matchIfMissing = true)"| VK["Instantiate ValKeyConfigStore"]
    P -->|"valkey"| VK
    P -->|"mongodb"| MC["Instantiate MongoClient"]
    MC --> MD["Instantiate MongoDatabase"]
    MD --> MG["Instantiate MongoConfigStore"]
    P -->|"unsupported value"| ERR["No ConfigStore bean created\nApplication fails to start"]
    VK --> INJ["Inject into SiteConfigurationService"]
    MG --> INJ

    style VK fill:#ff8844,color:#fff
    style MG fill:#44aa44,color:#fff
    style ERR fill:#ff4444,color:#fff
```

Exactly one `ConfigStore` bean is created per application context. `SiteConfigurationService` receives it via constructor injection.

**Invalid configuration:** If `config.store.type` is set to an unsupported value, no `ConfigStore` bean is created. The application fails at startup with:

```
Parameter 0 of constructor in SiteConfigurationService required a bean
of type 'ConfigStore' that could not be found.
```

---

## 8. Verifying the Active Store

### Application log

The selected backend is logged at INFO level during startup:

```
Using ValKey config store backend
```

or:

```
Using MongoDB config store backend
```

### Health endpoint

```bash
curl -s http://localhost:9090/api/stats/health | jq .
```

For local (non-Docker) execution:

```bash
curl -s http://localhost:8080/api/stats/health | jq .
```

### Container log inspection

```bash
docker logs const-catalog-backend 2>&1 | grep "config store"
```

---

## 9. ConfigStore Interface Specification

Both implementations conform to the following contract:

```java
public interface ConfigStore {
    String get(String key);
    void set(String key, String value);
    boolean delete(String key);
    void setBatch(Map<String, String> entries);
    List<String> keysByPrefix(String prefix);
    Map<String, String> getByPrefix(String prefix);
    void publishChange(String key);
    String storeName();
}
```

### Implementation Comparison

| Method | ValKey Implementation | MongoDB Implementation |
|---|---|---|
| `get(key)` | `GET config:{key}` | `find({_id: key})` |
| `set(key, value)` | `SET config:{key} value` | `replaceOne({_id: key}, doc, upsert: true)` |
| `delete(key)` | `DEL config:{key}` | `deleteOne({_id: key})` |
| `setBatch(entries)` | `MSET` (single atomic command) | Iterative `replaceOne` calls |
| `keysByPrefix(prefix)` | `KEYS config:{prefix}*` | `find({_id: /^{prefix}/})` |
| `getByPrefix(prefix)` | `KEYS` + individual `GET` | `find({_id: /^{prefix}/})` |
| `publishChange(key)` | `PUBLISH config:changes {key}` | No-op (standalone mode) |

**Implementation notes:**
- ValKey applies a `config:` key prefix internally. MongoDB uses the configuration key directly as the document `_id`.
- ValKey's `setBatch` executes as a single atomic `MSET` command. MongoDB's implementation iterates and performs individual upserts.
- Pub/sub for distributed cache invalidation is available only with ValKey. MongoDB would require Change Streams (replica set deployment) for equivalent functionality.

---

## 10. Caching Architecture

`SiteConfigurationService` interposes a Caffeine L1 cache between the application and the `ConfigStore` backend:

```mermaid
flowchart LR
    REQ["lookup('db.host')"] --> L1{"Caffeine L1 cache\n(JVM-local)"}
    L1 -->|hit| RES["Return value"]
    L1 -->|miss| L2["ConfigStore.get('db.host')"]
    L2 -->|ValKey| VK["GET config:db.host"]
    L2 -->|MongoDB| MG["find({_id: 'db.host'})"]
    VK --> CACHE["Populate cache\nReturn value"]
    MG --> CACHE

    style L1 fill:#4488ff,color:#fff
    style VK fill:#ff8844,color:#fff
    style MG fill:#44aa44,color:#fff
```

- **Read path (cache hit):** Value returned directly from Caffeine. No network I/O.
- **Read path (cache miss):** `ConfigStore.get(key)` is invoked. The result is stored in Caffeine for subsequent requests.
- **Write path:** `ConfigStore.set(key, value)` persists the value. `ConfigStore.publishChange(key)` broadcasts the change for distributed cache invalidation (ValKey only). The local Caffeine entry is updated immediately.

The backend selection affects only cache-miss latency and write durability. Cache-hit performance is identical regardless of backend.

---

## 11. Running Integration Tests

### ValKey integration tests

```bash
# Start the ValKey service
docker compose --env-file=.env up -d valkey

# Execute tests
mvn test -pl const-catalog-backend -Dtest=ValKeyConfigStoreTest
```

### MongoDB integration tests

```bash
# Start the MongoDB service
docker compose --env-file=.env --profile=mongodb up -d mongo

# Execute tests
mvn test -pl const-catalog-backend -Dtest=MongoConfigStoreTest
```

### Combined execution

```bash
docker compose --env-file=.env --profile=mongodb up -d valkey mongo

mvn test -pl const-catalog-backend -Dtest="ValKeyConfigStoreTest,MongoConfigStoreTest"
```

Both test suites use JUnit 5 `Assumptions` to skip gracefully when their backing service is unavailable. Tests are reported as **skipped**, not **failed**.

**Prerequisite:** If `const-catalog-core` has been modified, install the updated JAR before running backend tests:

```bash
mvn install -pl const-catalog-core -DskipTests
```

---

## 12. Deployment Configurations

### Local development — ValKey (default)

No configuration changes required.

```bash
docker compose --env-file=.env up -d
```

### Local development — MongoDB

`.env`:
```
HOST_SRC_PATH=/home/user/src
CONFIG_STORE_TYPE=mongodb
```

```bash
docker compose --env-file=.env --profile=mongodb up -d
```

### Docker Compose — MongoDB as primary store

Add to the `backend` service `environment` in docker-compose.yml:

```yaml
backend:
  environment:
    - NEO4J_URI=bolt://neo4j:7687
    - SPRING_PROFILES_ACTIVE=docker
    - VALKEY_HOST=valkey
    - VALKEY_PORT=6379
    - CONFIG_STORE_TYPE=mongodb
    - CONFIG_STORE_MONGODB_URI=mongodb://mongo:27017
    - CONFIG_STORE_MONGODB_DATABASE=const-catalog
```

### IDE execution (no Docker)

Set VM arguments in the run configuration:

For ValKey:
```
-Dvalkey.host=localhost -Dvalkey.port=6379
```

For MongoDB:
```
-Dconfig.store.type=mongodb
-Dconfig.store.mongodb.uri=mongodb://localhost:27017
-Dconfig.store.mongodb.database=const-catalog
```

### Production — MongoDB with authentication and replica set

```yaml
config:
  store:
    type: mongodb
    mongodb:
      uri: mongodb://user:password@primary:27017,secondary:27017/const-catalog?replicaSet=rs0&authSource=admin
      database: const-catalog
```

With a replica set deployment, MongoDB Change Streams become available for implementing distributed cache invalidation via `publishChange()`.

---

## 13. Troubleshooting

### "No qualifying bean of type 'ConfigStore'"

**Cause:** `config.store.type` is set to an unsupported value.

**Resolution:** Verify the environment variable value:

```bash
docker exec const-catalog-backend env | grep -i config
```

Valid values are `valkey` and `mongodb`.

### "Unable to connect to ValKey"

**Cause:** The ValKey service is not running, or the hostname is incorrect.

**Resolution:**

```bash
# Verify service status
docker compose ps valkey

# Test network connectivity from the backend container
docker exec const-catalog-backend sh -c "nc -zv valkey 6379"
```

When running outside Docker, the hostname should be `localhost`, not `valkey`.

### "MongoTimeoutException: Timed out after 30000 ms"

**Cause:** The MongoDB service is not running, or the connection URI is incorrect.

**Resolution:**

```bash
# Verify service status
docker compose --profile=mongodb ps mongo

# Test connectivity
docker exec const-catalog-mongo mongosh --eval "db.adminCommand('ping')"
```

Common causes:
- The `--profile=mongodb` flag was omitted from the Docker Compose command
- The URI uses `mongo:27017` from outside Docker (should be `localhost:27017`)
- The URI uses `localhost:27017` from inside Docker (should be `mongo:27017`)

### "Backend starts but uses the wrong store"

**Cause:** The `CONFIG_STORE_TYPE` environment variable is not being passed to the container.

**Resolution:**

```bash
# Check the startup log
docker logs const-catalog-backend 2>&1 | grep "Using.*config store"

# Verify the environment variable inside the container
docker exec const-catalog-backend env | grep CONFIG_STORE
```

### MongoDB tests skip with "MongoDB not available"

**Cause:** The MongoDB service is not running or has not completed initialization.

**Resolution:**

```bash
docker compose --env-file=.env --profile=mongodb up -d mongo
```

Wait for the health check to pass:

```bash
docker compose --profile=mongodb ps
```

The status should show `Up (healthy)`, not `Up (health: starting)`.

---

## Appendix A: Configuring an External MongoDB

When connecting to a MongoDB instance hosted outside of Docker Compose — such as a remote server, managed service (e.g., MongoDB Atlas), or shared development database — the local `mongo` service is not required. Configuration is managed entirely through environment variables.

### Step 1: Define environment variables

Add the following to your `.env` file:

```
CONFIG_STORE_TYPE=mongodb
CONFIG_STORE_MONGODB_URI=mongodb://user:password@your-external-host:27017
CONFIG_STORE_MONGODB_DATABASE=const-catalog
```

For replica set deployments:

```
CONFIG_STORE_MONGODB_URI=mongodb://user:password@host1:27017,host2:27017,host3:27017/?replicaSet=rs0&authSource=admin
```

For MongoDB Atlas:

```
CONFIG_STORE_MONGODB_URI=mongodb+srv://user:password@cluster0.abc123.mongodb.net/?retryWrites=true&w=majority
```

### Step 2: Configure application.yml (one-time setup)

Add the config store section if not already present:

```yaml
config:
  store:
    type: ${CONFIG_STORE_TYPE:valkey}
    mongodb:
      uri: ${CONFIG_STORE_MONGODB_URI:mongodb://localhost:27017}
      database: ${CONFIG_STORE_MONGODB_DATABASE:const-catalog}
```

The `${VAR:default}` syntax resolves to the environment variable value when set, and falls back to the default otherwise. This single configuration block supports all deployment scenarios — local development with ValKey, Docker with local MongoDB, and external MongoDB — controlled entirely by environment variables.

### Step 3: Configure docker-compose.yml (one-time setup)

Add the environment variables to the backend service definition:

```yaml
backend:
  environment:
    - NEO4J_URI=bolt://neo4j:7687
    - SPRING_PROFILES_ACTIVE=docker
    - VALKEY_HOST=valkey
    - VALKEY_PORT=6379
    - CONFIG_STORE_TYPE=${CONFIG_STORE_TYPE:-valkey}
    - CONFIG_STORE_MONGODB_URI=${CONFIG_STORE_MONGODB_URI:-mongodb://mongo:27017}
    - CONFIG_STORE_MONGODB_DATABASE=${CONFIG_STORE_MONGODB_DATABASE:-const-catalog}
```

The `${VAR:-default}` syntax (Docker Compose/shell) passes `.env` values into the container. When the variables are not defined, the defaults reference the local Docker MongoDB service.

### Step 4: Deploy without the local MongoDB service

```bash
docker compose --env-file=.env up -d
```

The `--profile=mongodb` flag is not required. The backend connects to the external MongoDB instance specified in `.env`. The local `mongo` service remains stopped.

### Environment variable flow

Both `application.yml` and `docker-compose.yml` reference the same environment variable names. A single `.env` file change propagates through the entire deployment:

```mermaid
flowchart LR
    ENV[".env file"] --> DC["docker-compose.yml\n(passes variables to container)"]
    DC --> APP["application.yml\n(resolves variables at startup)"]
    APP --> BEAN["ConfigStoreConfig\n(creates MongoConfigStore bean)"]

    style ENV fill:#44aa44,color:#fff
    style BEAN fill:#ff8844,color:#fff
```

To revert to ValKey, remove or comment out the three `CONFIG_STORE_*` entries in `.env` and restart the services.

### Verification

```bash
# Confirm the active backend
docker logs const-catalog-backend 2>&1 | grep "config store"
# Expected: "Using MongoDB config store backend"

# Test external connectivity (requires local mongosh installation)
mongosh "mongodb://user:password@your-external-host:27017" --eval "db.adminCommand('ping')"
```

### Security Considerations

- **Credential management:** Do not commit `.env` files containing credentials to version control. Ensure `.env` is listed in `.gitignore`. Distribute credentials through a secure channel appropriate to your organization's policies.
- **Authentication source:** Include `authSource=admin` in the URI if credentials are stored in a database other than the target database.
- **Transport encryption:** For TLS/SSL connections, append `&tls=true` to the URI. MongoDB Atlas connections use TLS by default.
