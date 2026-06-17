#!/bin/bash
# Apply ConfigStore changes to a copy of the project.
# Run from the project root directory.
#
# Usage:
#   1. Copy new.tar and configstore-modifications.patch to the project root
#   2. Run: bash scripts/apply-configstore-changes.sh
#
# What this does:
#   - Extracts new files (interface, implementations, tests, entrypoint)
#   - Applies diffs to modified files (SiteConfigurationService, Dockerfile, pom.xml, docker-compose.yml)

set -e

echo "=== Step 1: Extract new files ==="
if [ ! -f new.tar ]; then
    echo "ERROR: new.tar not found in current directory"
    exit 1
fi
tar xvf new.tar
echo ""

echo "=== Step 2: Apply modifications ==="
if [ ! -f configstore-modifications.patch ]; then
    echo "ERROR: configstore-modifications.patch not found in current directory"
    exit 1
fi

# Dry run first to check for conflicts
if git apply --check configstore-modifications.patch 2>/dev/null; then
    git apply configstore-modifications.patch
    echo "Patch applied successfully."
else
    echo "Patch does not apply cleanly. Trying with 3-way merge..."
    if git apply --3way configstore-modifications.patch; then
        echo "Patch applied with 3-way merge. Check for conflict markers."
    else
        echo "Patch failed. Apply manually using:"
        echo "  git apply --reject configstore-modifications.patch"
        echo "This will create .rej files for hunks that don't apply."
        exit 1
    fi
fi

echo ""
echo "=== Done ==="
echo "New files:"
echo "  const-catalog-backend/src/main/java/.../store/ConfigStore.java"
echo "  const-catalog-backend/src/main/java/.../store/ValKeyConfigStore.java"
echo "  const-catalog-backend/src/main/java/.../store/MongoConfigStore.java"
echo "  const-catalog-backend/src/main/java/.../config/ConfigStoreConfig.java"
echo "  const-catalog-backend/src/test/.../store/MongoConfigStoreTest.java"
echo "  const-catalog-backend/src/test/.../store/ValKeyConfigStoreTest.java"
echo "  const-catalog-backend/entrypoint.sh"
echo ""
echo "Modified files:"
echo "  const-catalog-backend/.../SiteConfigurationService.java"
echo "  const-catalog-backend/Dockerfile"
echo "  const-catalog-backend/pom.xml"
echo "  docker-compose.yml"
echo ""
echo "Next steps:"
echo "  1. mvn compile -pl const-catalog-backend -am   # verify it compiles"
echo "  2. mvn test -pl const-catalog-backend -Dtest=MongoConfigStoreTest  # run MongoDB tests (needs Docker)"
echo "  3. mvn test -pl const-catalog-backend -Dtest=ValKeyConfigStoreTest  # run ValKey tests (needs docker compose up -d valkey)"
