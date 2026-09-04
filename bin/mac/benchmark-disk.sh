#!/bin/sh

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SOURCE_FILE="${SCRIPT_DIR}/disk-throughput-benchmark.c"
BENCHMARK_BINARY="${TMPDIR:-/tmp}/peg-solitaire-disk-throughput-benchmark"

if [ "$#" -gt 4 ]; then
    echo "Usage: $0 [directory [size-MiB [trials [block-KiB]]]]" >&2
    exit 2
fi

cc -std=c11 -O2 -Wall -Wextra -Wpedantic \
    "${SOURCE_FILE}" -o "${BENCHMARK_BINARY}"

exec "${BENCHMARK_BINARY}" \
    "${1:-/Volumes/ponrava}" \
    "${2:-1024}" \
    "${3:-5}" \
    "${4:-1024}"
