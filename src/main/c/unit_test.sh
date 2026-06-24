#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"

BUILD_TYPE="Release"
CMAKE_DEFINES=()
for arg in "$@"; do
    case "$arg" in
        --debug)
            BUILD_TYPE="Debug"
            ;;
        --debug-timings)
            CMAKE_DEFINES+=("-DDEBUG_TIMINGS=ON")
            ;;
    esac
done

cmake -S "$SCRIPT_DIR" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE="$BUILD_TYPE" "${CMAKE_DEFINES[@]}"
cmake --build "$BUILD_DIR" --target xsmtest

pushd "$BUILD_DIR" > /dev/null
"$BUILD_DIR/xsmtest" "$@"
popd > /dev/null
