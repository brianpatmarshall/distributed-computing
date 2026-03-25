#!/bin/bash
# Manual modification script for ConfigStore changes.
# Run from the project root. Each section modifies one file.
# If a sed command fails, the script continues and reports what failed.
set -u

ERRORS=0

echo "=== 1/4: Dockerfile ==="
FILE="const-catalog-backend/Dockerfile"
if [ -f "$FILE" ]; then
    # Remove gosu references
    sed -i 's/# Install gosu.*//g' "$FILE"
    sed -i 's/git gosu/git/g' "$FILE"
    # Fix entrypoint comment
    sed -i 's/via gosu/via su/g' "$FILE"
    echo "  Updated $FILE"
else
    echo "  ERROR: $FILE not found"; ERRORS=$((ERRORS+1))
fi

echo ""
echo "=== 2/4: pom.xml (add MongoDB dependencies) ==="
FILE="const-catalog-backend/pom.xml"
if [ -f "$FILE" ]; then
    # Add mongodb-driver-sync after lettuce-core dependency block
    if ! grep -q "mongodb-driver-sync" "$FILE"; then
        sed -i '/<artifactId>lettuce-core<\/artifactId>/,/<\/dependency>/{
            /<\/dependency>/a\
\
        <!-- MongoDB driver for optional MongoDB config store backend -->\
        <dependency>\
            <groupId>org.mongodb</groupId>\
            <artifactId>mongodb-driver-sync</artifactId>\
        </dependency>
        }' "$FILE"
        echo "  Added mongodb-driver-sync dependency"
    else
        echo "  mongodb-driver-sync already present, skipping"
    fi

    # Add testcontainers mongodb after testcontainers neo4j dependency block
    if ! grep -q 'artifactId>mongodb' "$FILE"; then
        sed -i '/<artifactId>neo4j<\/artifactId>/{
            N
            /<scope>test<\/scope>/{
                N
                /<\/dependency>/a\
        <dependency>\
            <groupId>org.testcontainers</groupId>\
            <artifactId>mongodb</artifactId>\
            <scope>test</scope>\
        </dependency>
            }
        }' "$FILE"
        echo "  Added testcontainers mongodb dependency"
    else
        echo "  testcontainers mongodb already present, skipping"
    fi
else
    echo "  ERROR: $FILE not found"; ERRORS=$((ERRORS+1))
fi

echo ""
echo "=== 3/4: docker-compose.yml (add mongo service + volume) ==="
FILE="docker-compose.yml"
if [ -f "$FILE" ]; then
    # Add mongo_data volume if not present
    if ! grep -q "mongo_data" "$FILE"; then
        sed -i '/valkey_data:/a\  mongo_data:' "$FILE"
        echo "  Added mongo_data volume"
    else
        echo "  mongo_data volume already present, skipping"
    fi

    # Add mongo service if not present
    if ! grep -q "const-catalog-mongo" "$FILE"; then
        # Insert before the neo4j service block
        sed -i '/# Neo4J Graph Database/i\
  # MongoDB (Optional Config Store Backend)\
  # ----------------------------------------\
  # Alternative to ValKey for storing configuration key-value pairs.\
  # Only starts when the mongodb profile is active:\
  #   docker compose --profile mongodb up -d\
  # Then set config.store.type=mongodb in application.yml or env var.\
  mongo:\
    image: mongo:7\
    container_name: const-catalog-mongo\
    profiles:\
      - mongodb\
    ports:\
      - "27017:27017"\
    volumes:\
      - mongo_data:/data/db\
    healthcheck:\
      test: ["CMD", "mongosh", "--eval", "db.adminCommand('"'"'ping'"'"')"]\
      interval: 10s\
      timeout: 5s\
      retries: 5\
      start_period: 10s\
    networks:\
      - const-catalog-network\
' "$FILE"
        echo "  Added mongo service"
    else
        echo "  mongo service already present, skipping"
    fi
else
    echo "  ERROR: $FILE not found"; ERRORS=$((ERRORS+1))
fi

echo ""
echo "=== 4/4: SiteConfigurationService.java ==="
echo "  This file has extensive changes. It must be replaced entirely."
echo "  The new version is included in new.tar as:"
echo "    const-catalog-backend/src/main/java/com/boeing/constcatalog/backend/service/SiteConfigurationService.java"
echo ""
echo "  To include it in the tar, run on the source machine:"
echo "    tar rf new.tar const-catalog-backend/src/main/java/com/boeing/constcatalog/backend/service/SiteConfigurationService.java"
echo ""

if [ $ERRORS -gt 0 ]; then
    echo "=== DONE with $ERRORS error(s) ==="
else
    echo "=== DONE (no errors) ==="
fi

echo ""
echo "Next steps:"
echo "  1. Copy the updated SiteConfigurationService.java from new.tar"
echo "  2. mvn compile -pl const-catalog-backend -am    # verify compilation"
echo "  3. mvn test -pl const-catalog-backend -Dtest=MongoConfigStoreTest  # MongoDB tests"
