#!/bin/sh

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
APP_JAR="${PROJECT_DIR}/target/peg-solitaire-jar-with-dependencies.jar"

if [ "$#" -ne 0 ]; then
    echo "Usage: $0" >&2
    exit 2
fi

if [ ! -f "${APP_JAR}" ]; then
    echo "Application JAR not found: ${APP_JAR}" >&2
    echo "Build it first with ./bin/mac/build.sh" >&2
    exit 1
fi

cd "${PROJECT_DIR}"
java -Xmx10g -jar "${APP_JAR}" \
    count \
    --board english \
    --directory /Volumes/ponrava/peg-solitaire-senku-index/english-center \
    --workers 8 \
    --queue-capacity 32

printf '\a'
