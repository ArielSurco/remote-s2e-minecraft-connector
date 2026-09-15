#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="build/classes"
JAR_NAME="s2e-bridge.jar"
CONFIG_NAME="connection.properties"

# Resolve a working JDK. macOS ships /usr/bin/javac as a stub that only prints an
# error, so probe by running it rather than by checking PATH.
if ! javac -version >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1 && brew --prefix openjdk@25 >/dev/null 2>&1; then
    JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
  elif /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home)"
  else
    echo "No JDK found. Install one with: brew install openjdk" >&2
    exit 1
  fi
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

# --release 11 so the jar runs on any Java 11+ runtime, not just the build JDK.
javac --release 11 -encoding UTF-8 -d "$OUT_DIR" src/Main.java
jar --create --file "$JAR_NAME" --main-class Main -C "$OUT_DIR" .

# The jar reads this same file at runtime, so never overwrite a filled-in one.
if [ ! -f "$CONFIG_NAME" ]; then
  cat > "$CONFIG_NAME" <<'CFG'
# RCON connection to your Minecraft server.
# All three values are required. See README.md.
#
#   host      address of your RCON allocation, without the port
#   port      the allocation port, matching rcon.port in server.properties
#   password  exactly what you set as rcon.password in server.properties
host=
port=
password=
CFG
  echo "Created $CONFIG_NAME"
fi

rm -rf build
echo "Built $JAR_NAME with $(javac -version 2>&1)"
