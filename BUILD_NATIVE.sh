#!/usr/bin/env bash
#
# Build the GraalVM native image for cheese.
#
# Requires a GraalVM / Liberica Native Image Kit for JDK 25 (the JDK 21 /
# Substrate 23.1 line silently ignores Spring Boot 4.0's reachability-metadata.json
# and produces a binary that fails at startup with AotInitializerNotFoundException).
#
# Override the kit location via:  GRAALVM_HOME=/path/to/jdk25-nik ./BUILD_NATIVE.sh
#
set -euo pipefail

# Resolve to the project directory (where this script lives) so it works from anywhere.
cd "$(dirname "$0")"

# Default to the Liberica NIK 25; allow override via GRAALVM_HOME or JAVA_HOME.
DEFAULT_NIK="/Library/Java/LibericaNativeImageKit/liberica-vm-25.0.3-openjdk25/Contents/Home"
JAVA_HOME="${GRAALVM_HOME:-${JAVA_HOME:-$DEFAULT_NIK}}"
GRAALVM_HOME="$JAVA_HOME"
PATH="$JAVA_HOME/bin:$PATH"
export JAVA_HOME GRAALVM_HOME PATH

if [ ! -x "$JAVA_HOME/bin/native-image" ]; then
  echo "ERROR: native-image not found at '$JAVA_HOME/bin/native-image'." >&2
  echo "Point GRAALVM_HOME at a GraalVM/Liberica NIK for JDK 25, e.g.:" >&2
  echo "  GRAALVM_HOME=/path/to/jdk25-nik ./BUILD_NATIVE.sh" >&2
  exit 1
fi

echo ">> Using: $("$JAVA_HOME/bin/native-image" --version | head -1)"
echo ">> JAVA_HOME=$JAVA_HOME"

# package runs Spring AOT (process-aot); compile-no-fork builds the image from the
# already AOT-processed classes without re-forking the lifecycle.
./mvnw -Pnative clean package native:compile-no-fork -DskipTests "$@"

# native-image emits a "linker-signed" ad-hoc signature (flags 0x20002). macOS
# AMFI only honors linker-signed signatures in the exact location the linker
# produced them, so *copying* the binary anywhere else makes the kernel reject
# it with SIGKILL ("Code Signature Invalid", exit 137). Re-signing with a plain
# ad-hoc signature (flags 0x2) drops the linker-signed flag and survives copying.
if [[ "$(uname)" == "Darwin" ]] && command -v codesign >/dev/null 2>&1; then
  echo ">> Re-signing ad-hoc (so the binary can be copied without AMFI killing it)"
  codesign --force --sign - target/cheese
fi

echo
echo ">> Native binary: $(pwd)/target/cheese"
ls -lh target/cheese

# Deploy a copy next to its runtime data dir. Done AFTER the ad-hoc re-sign above
# so the copy carries a plain (non-linker-signed) signature and is not killed by
# macOS AMFI.
echo ">> Copying binary to cheese-app/"
mkdir -p cheese-app
rm -f cheese-app/cheese
cp -av target/cheese cheese-app/cheese
ls -lh cheese-app/cheese

# Package the distribution into ./deploy/cheese-app-<version>.zip.
VERSION="$(grep -E '^build\.version=' target/classes/META-INF/build-info.properties | cut -d= -f2)"
ZIP="deploy/cheese-app-$VERSION.zip"
echo
echo ">> Packaging $ZIP"
mkdir -p deploy
rm -f "$ZIP"
# Exclude runtime log files; keep the binary and data/.
zip -r9 "$ZIP" cheese-app -x '*.log'

echo
echo ">> Done."
echo "   Archive: $(pwd)/$ZIP"
