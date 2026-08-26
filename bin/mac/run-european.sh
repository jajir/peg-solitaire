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

#    -agentpath:/Applications/YourKit-Java-Profiler.app/Contents/Resources/bin/mac/libyjpagent.dylib=exceptions=disable,delay=10000,listen=all \


cd "${PROJECT_DIR}"
java \
    -Xmx10g \
    -jar "${APP_JAR}" \
    count \
    --board european \
    --directory /Volumes/ponrava/peg-solitaire-senku-index/european-center \
    --workers 8 \
    --queue-capacity 32

printf '\a'
