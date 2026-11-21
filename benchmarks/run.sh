#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# 1. Ensure shadow-http Java classes are compiled and up to date
if [ ! -f "$ROOT_DIR/target/classes/shadow/http/server/Server.class" ] || \
   [ -n "$(find "$ROOT_DIR/src/java" -name '*.java' -newer "$ROOT_DIR/target/classes/shadow/http/server/Server.class" 2>/dev/null | head -n 1)" ]; then
  echo "Compiling shadow-http Java sources..." >&2
  (cd "$ROOT_DIR" && mvn -q compile)
fi

# 2. Compile benchmark Java load generator if missing or stale
mkdir -p "$SCRIPT_DIR/classes"
if [ ! -f "$SCRIPT_DIR/classes/bench/LoadGen.class" ] || \
   [ "$SCRIPT_DIR/java/bench/LoadGen.java" -nt "$SCRIPT_DIR/classes/bench/LoadGen.class" ]; then
  echo "Compiling benchmark load generator..." >&2
  javac --release 21 -d "$SCRIPT_DIR/classes" "$SCRIPT_DIR/java/bench/LoadGen.java"
fi

cd "$SCRIPT_DIR"
exec clojure -M:run "$@"
