#! /bin/bash
# @(#) 
#==========================================================================
# File            : installAndInjectDependencies.sh
# Description     : Builds and installs the Constants Catalog JARs into 
#                   the local Maven repository, then injects the necessary 
#                   <dependencyManagement> and <dependency> entries into a 
#                   target multi-module project so that refactored code 
#                   can compile.
#
# Usage:
#   bash installAndInjectDependencies.sh /path/to/target-multi-module-project
#
# What this does:
#   1. Runs 'mvn clean package' and 'mvn install' for:
#      - The constants catalog multi-module project (core + backend)
#   2. In the target project:
#      - Adds <dependencyManagement> entries to the parent pom.xml for
#        const-catalog-core and const-catalog-backend
#      - Adds <dependency> entries to each child module's pom.xml
#
# Prerequisites:
#   - Maven (mvn) on PATH
#   - xmllint (from libxml2-utils) for XML verification (optional)
#
# Revision History: 
#
#   18 Apr 2026 - Brian Marshall
#      Initial Version.
#
# ============================================================================
set -euo pipefail
#---------------------------------------------------------------------------
# Colors and helpers
#---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*"; }
die()   { err "$*"; exit 1; }

#---------------------------------------------------------------------------
# Configuration — Maven coordinates of the JARs to inject
#---------------------------------------------------------------------------
M2_REPO="$HOME/.m2/repository"

CONSTANTS_PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

CORE_GROUP_ID="com.boeing"
CORE_ARTIFACT_ID="const-catalog-core"
CORE_VERSION="1.0-SNAPSHOT"

BACKEND_GROUP_ID="com.boeing"
BACKEND_ARTIFACT_ID="const-catalog-backend"
BACKEND_VERSION="1.0-SNAPSHOT"

# Version property names to use in the target parent POM
CONST_CATALOG_VERSION_PROP="const-catalog.version"
CONST_CATALOG_VERSION_VALUE="1.0-SNAPSHOT"

# ----------------------------------------------------------------------------
# Function already_present
# ----------------------------------------------------------------------------
already_present() {
    grep -q "$1" "$2" 2>/dev/null
}

# ----------------------------------------------------------------------------
# Function: inject_property
# ----------------------------------------------------------------------------
inject_property() {
    local pom="$1"
    if already_present "<${CONST_CATALOG_VERSION_PROP}>" "$pom"; then
        ok "Property <${CONST_CATALOG_VERSION_PROP}> already exists in parent POM"
        return 0
    fi

    info "Adding <${CONST_CATALOG_VERSION_PROP}> property to parent POM"

    local prop_block="        <${CONST_CATALOG_VERSION_PROP}>${CONST_CATALOG_VERSION_VALUE}</${CONST_CATALOG_VERSION_PROP}>"

    if [ "$DRY_RUN" = true ]; then
        warn "[DRY RUN] Would add to <properties>:"
        echo "    $prop_block"
        return 0
    fi

    cp "$pom" "${pom}${BACKUP_SUFFIX}"

    # Insert before </properties>
    sed -i "/<\/properties>/i\\
${prop_block}" "$pom"

    ok "Added property to parent POM"
}

#----------------------------------------------------------------------------
# Function: check_jar
#----------------------------------------------------------------------------
check_jar() {
    local group_path="${1//./\/}"  # Replace all instances of '.' with '/'
    local artifact="$2"
    local version="$3"
    local jar_path="$M2_REPO/$group_path/$artifact/$version/$artifact-$version.jar"

    if [ -f "$jar_path" ]; then
        ok "Found: $jar_path"
        return 0
    else
        err "Missing: $jar_path"
        return 1
    fi
}


#---------------------------------------------------------------------------
# Argument parsing
#---------------------------------------------------------------------------
if [ $# -lt 1 ]; then
    echo "Usage: $0 <target-multi-module-project-path> [--skip-build] [--dry-run]"
    echo ""
    echo "Options:"
    echo "  --skip-build   Skip building/installing the constants JARs"
    echo "                 (use if already installed in ~/.m2/repository)"
    echo "  --dry-run      Show what would be changed without modifying files"
    exit 1
fi

TARGET_PROJECT="$(cd "$1" && pwd)" || die "Target project path does not exist: $1"
shift

SKIP_BUILD=false
DRY_RUN=false

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=true ;;
        --dry-run)    DRY_RUN=true ;;
        *)            die "Unknown option: $arg" ;;
    esac
done

# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------
info "Constants project:     $CONSTANTS_PROJECT_DIR"
info "Target project:        $TARGET_PROJECT"
info "Skip build:            $SKIP_BUILD"
info "Dry run:               $DRY_RUN"
echo ""

[ -f "$TARGET_PROJECT/pom.xml" ] || die "No pom.xml found in target project: $TARGET_PROJECT"

# Check that target is a multi-module project (has <modules> in its pom.xml)
if ! grep -q '<modules>' "$TARGET_PROJECT/pom.xml"; then
    die "Target pom.xml does not contain <modules>. Is this a multi-module project?"
fi

# ---------------------------------------------------------------------------
# Phase 1: Build and install the constants JARs
# ---------------------------------------------------------------------------
if [ "$SKIP_BUILD" = false ]; then
    echo "================================================================"
    info "Phase 1: Building and installing constants JARs"
    echo "================================================================"
    echo ""

    # --- Constants Catalog (multi-module) ---
    info "Building constants catalog (core + backend)..."
    if [ -d "$CONSTANTS_PROJECT_DIR" ]; then
        (
            cd "$CONSTANTS_PROJECT_DIR"
            mvn clean package -DskipTests -q
            mvn install -DskipTests -q
        )
        ok "Installed: ${CORE_GROUP_ID}:${CORE_ARTIFACT_ID}:${CORE_VERSION}"
        ok "Installed: ${BACKEND_GROUP_ID}:${BACKEND_ARTIFACT_ID}:${BACKEND_VERSION}"
    else
        die "Constants project directory not found: $CONSTANTS_PROJECT_DIR"
    fi
    echo ""
else
    info "Skipping build (--skip-build)"
    echo ""
fi

# ---------------------------------------------------------------------------
# Phase 2: Verify JARs exist in local Maven repository
# ---------------------------------------------------------------------------
echo "================================================================"
info "Phase 2: Verifying JARs in local Maven repository"
echo "================================================================"
echo ""

JARS_OK=true
check_jar "$CORE_GROUP_ID"    "$CORE_ARTIFACT_ID"    "$CORE_VERSION"    || JARS_OK=false
check_jar "$BACKEND_GROUP_ID" "$BACKEND_ARTIFACT_ID" "$BACKEND_VERSION" || JARS_OK=false

if [ "$JARS_OK" = false ]; then
    die "Required JARs not found in local repo. Run without --skip-build."
fi
echo ""

# ---------------------------------------------------------------------------
# Phase 3: Inject dependencies into target project
# ---------------------------------------------------------------------------
echo "================================================================"
info "Phase 3: Injecting dependencies into target project"
echo "================================================================"
echo ""

PARENT_POM="$TARGET_PROJECT/pom.xml"
BACKUP_SUFFIX=".bak.$(date +%Y%m%d%H%M%S)"

# --- Helper: check if a string already exists in a file ---

# ---------------------------------------------------------------
# 3a. Add version property to parent POM <properties>
# ---------------------------------------------------------------

# ---------------------------------------------------------------
# 3b. Add <dependencyManagement> entries to parent POM
# ---------------------------------------------------------------
inject_dependency_management() {
    local pom="$1"

    # The XML block to inject
    local dm_core="            <dependency>\\
                <groupId>${CORE_GROUP_ID}</groupId>\\
                <artifactId>${CORE_ARTIFACT_ID}</artifactId>\\
                <version>\${${CONST_CATALOG_VERSION_PROP}}</version>\\
            </dependency>"

    local dm_backend="            <dependency>\\
                <groupId>${BACKEND_GROUP_ID}</groupId>\\
                <artifactId>${BACKEND_ARTIFACT_ID}</artifactId>\\
                <version>\${${CONST_CATALOG_VERSION_PROP}}</version>\\
            </dependency>"

    # Check what's already present
    local need_core=true
    local need_backend=true

    if already_present "<artifactId>${CORE_ARTIFACT_ID}</artifactId>" "$pom"; then
        ok "dependencyManagement entry for ${CORE_ARTIFACT_ID} already exists"
        need_core=false
    fi
    if already_present "<artifactId>${BACKEND_ARTIFACT_ID}</artifactId>" "$pom"; then
        ok "dependencyManagement entry for ${BACKEND_ARTIFACT_ID} already exists"
        need_backend=false
    fi

    if [ "$need_core" = false ] && [ "$need_backend" = false ]; then
        return 0
    fi

    if [ "$DRY_RUN" = true ]; then
        warn "[DRY RUN] Would add to <dependencyManagement><dependencies>:"
        [ "$need_core" = true ]    && echo -e "    ${dm_core//\\/}"
        [ "$need_backend" = true ] && echo -e "    ${dm_backend//\\/}"
        return 0
    fi

    # Backup if not already done
    [ ! -f "${pom}${BACKUP_SUFFIX}" ] && cp "$pom" "${pom}${BACKUP_SUFFIX}"

    if grep -q '<dependencyManagement>' "$pom"; then
        # <dependencyManagement> exists — inject after first <dependencies> inside it
        # Strategy: find the line with <dependencyManagement>, then find the next
        # <dependencies> tag, and insert after it
        if [ "$need_core" = true ]; then
            # Insert after the first <dependencies> that follows <dependencyManagement>
            sed -i "/<dependencyManagement>/,/<\/dependencyManagement>/{
                /<dependencies>/a\\
\\
            <!-- Constants Catalog - Core -->\\
${dm_core}
                # Only match the first <dependencies>
                }" "$pom"
            ok "Added ${CORE_ARTIFACT_ID} to <dependencyManagement>"
        fi
        if [ "$need_backend" = true ]; then
            sed -i "/<dependencyManagement>/,/<\/dependencyManagement>/{
                /<dependencies>/a\\
\\
            <!-- Constants Catalog - Backend -->\\
${dm_backend}
                }" "$pom"
            ok "Added ${BACKEND_ARTIFACT_ID} to <dependencyManagement>"
        fi
    else
        # No <dependencyManagement> exists — create the entire block
        # Insert before <build> or before </project>
        local insert_before="<\/project>"
        grep -q '<build>' "$pom" && insert_before="<build>"
        grep -q '<dependencies>' "$pom" && insert_before="<dependencies>"

        local full_block="    <dependencyManagement>\\
        <dependencies>\\
\\
            <!-- Constants Catalog - Core -->\\
${dm_core}\\
\\
            <!-- Constants Catalog - Backend -->\\
${dm_backend}\\
\\
        </dependencies>\\
    </dependencyManagement>\\
"
        sed -i "/${insert_before}/i\\
${full_block}" "$pom"

        ok "Created <dependencyManagement> block in parent POM"
    fi
}

# ---------------------------------------------------------------
# 3c. Add <dependency> entries to each child module
# ---------------------------------------------------------------
inject_child_dependency() {
    local pom="$1"
    local module_name="$2"

    local dep_core="        <!-- Constants Catalog - Core -->\\
        <dependency>\\
            <groupId>${CORE_GROUP_ID}</groupId>\\
            <artifactId>${CORE_ARTIFACT_ID}</artifactId>\\
        </dependency>"

    local dep_backend="        <!-- Constants Catalog - Backend -->\\
        <dependency>\\
            <groupId>${BACKEND_GROUP_ID}</groupId>\\
            <artifactId>${BACKEND_ARTIFACT_ID}</artifactId>\\
        </dependency>"

    local need_core=true
    local need_backend=true

    if already_present "<artifactId>${CORE_ARTIFACT_ID}</artifactId>" "$pom"; then
        ok "  ${module_name}: ${CORE_ARTIFACT_ID} dependency already exists"
        need_core=false
    fi
    if already_present "<artifactId>${BACKEND_ARTIFACT_ID}</artifactId>" "$pom"; then
        ok "  ${module_name}: ${BACKEND_ARTIFACT_ID} dependency already exists"
        need_backend=false
    fi

    if [ "$need_core" = false ] && [ "$need_backend" = false ]; then
        return 0
    fi

    if [ "$DRY_RUN" = true ]; then
        warn "[DRY RUN] Would add to ${module_name}/pom.xml <dependencies>:"
        [ "$need_core" = true ]    && echo "      ${CORE_GROUP_ID}:${CORE_ARTIFACT_ID}"
        [ "$need_backend" = true ] && echo "      ${BACKEND_GROUP_ID}:${BACKEND_ARTIFACT_ID}"
        return 0
    fi

    cp "$pom" "${pom}${BACKUP_SUFFIX}"

    if grep -q '<dependencies>' "$pom"; then
        # <dependencies> exists — insert after the opening tag
        local block=""
        [ "$need_core" = true ]    && block="${block}\\
${dep_core}"
        [ "$need_backend" = true ] && block="${block}\\
${dep_backend}"

        # Insert after the FIRST <dependencies> (not inside <dependencyManagement>)
        # In child POMs there's typically no <dependencyManagement>, so this is safe
        sed -i "0,/dependencies>/s|<dependencies>|<dependencies>${block}|" "$pom"
    else
        # No <dependencies> section — create one before </project>
        local block="    <dependencies>\\
${dep_core}\\
\\
${dep_backend}\\
    </dependencies>"

        sed -i "/<\/project>/i\\
${block}" "$pom"
    fi

    [ "$need_core" = true ]    && ok "  ${module_name}: Added ${CORE_ARTIFACT_ID}"
    [ "$need_backend" = true ] && ok "  ${module_name}: Added ${BACKEND_ARTIFACT_ID}"
}

# ---------------------------------------------------------------
# Execute the injection
# ---------------------------------------------------------------

# 3a. Add property
info "Adding version property to parent POM..."
inject_property "$PARENT_POM"
echo ""

# 3b. Add dependencyManagement to parent
info "Adding <dependencyManagement> entries to parent POM..."
inject_dependency_management "$PARENT_POM"
echo ""

# 3c. Discover child modules and inject dependencies
info "Discovering child modules..."
MODULES=$(grep '<module>' "$PARENT_POM" | sed 's|.*<module>\(.*\)</module>.*|\1|' | tr -d '[:space:]' | tr '\n' ' ')

# Parse module names more carefully (one per line)
MODULES=()
while IFS= read -r line; do
    mod=$(echo "$line" | sed 's|.*<module>\(.*\)</module>.*|\1|')
    MODULES+=("$mod")
done < <(grep '<module>' "$PARENT_POM")

info "Found ${#MODULES[@]} modules: ${MODULES[*]}"
echo ""

for module in "${MODULES[@]}"; do
    child_pom="$TARGET_PROJECT/$module/pom.xml"
    if [ -f "$child_pom" ]; then
        info "Processing module: $module"
        inject_child_dependency "$child_pom" "$module"
    else
        warn "Module pom.xml not found: $child_pom (skipping)"
    fi
done

echo ""

# ---------------------------------------------------------------------------
# Phase 4: Summary
# ---------------------------------------------------------------------------
echo "================================================================"
info "Summary"
echo "================================================================"
echo ""

if [ "$DRY_RUN" = true ]; then
    warn "DRY RUN — no files were modified"
    echo ""
    echo "Run again without --dry-run to apply changes."
else
    ok "All dependencies injected successfully."
    echo ""
    echo "Backup files created with suffix: ${BACKUP_SUFFIX}"
    echo "To restore originals:  find $TARGET_PROJECT -name '*${BACKUP_SUFFIX}' -exec bash -c 'mv \"\$1\" \"\${1%${BACKUP_SUFFIX}}\"' _ {} \\;"
    echo ""
    echo "Injected into parent POM ($PARENT_POM):"
    echo "  - Property:             <${CONST_CATALOG_VERSION_PROP}>${CONST_CATALOG_VERSION_VALUE}</${CONST_CATALOG_VERSION_PROP}>"
    echo "  - dependencyManagement: ${CORE_GROUP_ID}:${CORE_ARTIFACT_ID}:\${${CONST_CATALOG_VERSION_PROP}}"
    echo "  - dependencyManagement: ${BACKEND_GROUP_ID}:${BACKEND_ARTIFACT_ID}:\${${CONST_CATALOG_VERSION_PROP}}"
    echo ""
    echo "Injected into each child module:"
    echo "  - dependency: ${CORE_GROUP_ID}:${CORE_ARTIFACT_ID} (version from parent)"
    echo "  - dependency: ${BACKEND_GROUP_ID}:${BACKEND_ARTIFACT_ID} (version from parent)"
    echo ""
    echo "Next steps:"
    echo "  1. cd $TARGET_PROJECT"
    echo "  2. mvn compile        # verify everything compiles"
    echo "  3. mvn test           # run tests"
    echo "  4. Review the changes: git diff"
fi
echo ""
