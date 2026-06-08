#!/usr/bin/env bash
# Build the SAP connector bundle, copy it (and SAP JCo, if provided) into the
# running mp_server container, and restart it so the connector is picked up.
#
# Why not bind-mount? Single-file bind mounts on Colima / Docker Desktop / macOS
# are flaky once symlinks or named-volume nesting enter the picture. docker cp +
# restart is unambiguous and works the same on every host.
#
# SAP JCo (Linux build) is optional for the script but REQUIRED for the connector
# to actually instantiate. Place sapjco3.jar and libsapjco3.so in docker/jco/
# (see docker/README.md). They are copied into $MIDPOINT_HOME/lib.
#
# Pass --no-build to skip the Maven build.
#
# Usage (from any directory):
#   docker/deploy-connector.sh
#   docker/deploy-connector.sh --no-build

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
PROJECT_DIR=$(cd "${SCRIPT_DIR}/.." && pwd -P)
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
SERVICE=mp_server
TARGET_CONNECTORS=/opt/midpoint/var/icf-connectors
TARGET_LIB=/opt/midpoint/var/lib

build=true
if [[ "${1:-}" == "--no-build" ]]; then
    build=false
fi

if $build; then
    echo "==> Building connector bundle"
    (cd "${PROJECT_DIR}" && mvn -ntp -B package -DskipTests)
fi

# The assembly (appendAssemblyId=false) makes target/connector-sap-<version>.jar
# the bundle, while connector-sap-<version>-bundle.jar would be a secondary one.
BUNDLE=$(ls -1t "${PROJECT_DIR}"/target/connector-sap-*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1)
if [[ -z "${BUNDLE}" ]]; then
    echo "ERROR: no built connector bundle found under ${PROJECT_DIR}/target/" >&2
    exit 1
fi
echo "==> Using ${BUNDLE}"

CONTAINER=$(docker compose -f "${COMPOSE_FILE}" ps -q "${SERVICE}")
if [[ -z "${CONTAINER}" ]]; then
    echo "ERROR: ${SERVICE} container is not running. Start the stack first:" >&2
    echo "       docker compose -f ${COMPOSE_FILE} up -d" >&2
    exit 1
fi

echo "==> Deploying connector bundle into ${TARGET_CONNECTORS}"
docker exec "${CONTAINER}" sh -c "mkdir -p '${TARGET_CONNECTORS}' && rm -f '${TARGET_CONNECTORS}'/connector-sap-*.jar"
docker cp "${BUNDLE}" "${CONTAINER}:${TARGET_CONNECTORS}/$(basename "${BUNDLE}")"

# SAP JCo (Linux): jar onto the classpath, native lib where LD_LIBRARY_PATH points.
JCO_DIR="${SCRIPT_DIR}/jco"
if [[ -f "${JCO_DIR}/sapjco3.jar" ]]; then
    echo "==> Deploying SAP JCo jar into ${TARGET_LIB}"
    docker exec "${CONTAINER}" sh -c "mkdir -p '${TARGET_LIB}'"
    docker cp "${JCO_DIR}/sapjco3.jar" "${CONTAINER}:${TARGET_LIB}/sapjco3.jar"
else
    echo "WARN: ${JCO_DIR}/sapjco3.jar not found - the SAP connector will not load until JCo is provided (see docker/README.md)" >&2
fi
if [[ -f "${JCO_DIR}/libsapjco3.so" ]]; then
    echo "==> Deploying SAP JCo native library into ${TARGET_LIB}"
    docker cp "${JCO_DIR}/libsapjco3.so" "${CONTAINER}:${TARGET_LIB}/libsapjco3.so"
else
    echo "WARN: ${JCO_DIR}/libsapjco3.so (Linux native lib) not found - SAP connectivity will fail until provided" >&2
fi

echo "==> Restarting ${SERVICE}"
docker compose -f "${COMPOSE_FILE}" restart "${SERVICE}"

echo "==> Done. Tail logs with:"
echo "    docker compose -f ${COMPOSE_FILE} logs -f ${SERVICE}"
