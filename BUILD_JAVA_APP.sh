#!/usr/bin/env bash
#
# Assemble a runnable Java (JVM) distribution of cheese under ./cheese-java-app:
#
#   cheese-java-app/
#     lib/cheese.jar     <- the executable Spring Boot jar
#     data/              <- copy of ./data (runtime config, secret.bin)
#     cheese.sh          <- self-locating launcher wrapper (requires Java 21+)
#
# Run it from anywhere afterwards, e.g.:  ./cheese-java-app/cheese.sh list-zones
#
set -euo pipefail

# Resolve to the project directory (where this script lives) so it works from anywhere.
cd "$(dirname "$0")"

APP_DIR="cheese-java-app"
JAR="target/cheese.jar"

# 1. Build the executable jar.
echo ">> Building cheese.jar"
./mvnw clean package -DskipTests

if [ ! -f "$JAR" ]; then
  echo "ERROR: expected jar not found at '$JAR' after build." >&2
  exit 1
fi

# 2. Lay out the distribution directory.
echo ">> Assembling $APP_DIR/"
mkdir -p "$APP_DIR/lib"
cp -f "$JAR" "$APP_DIR/lib/cheese.jar"

# Do NOT Refresh the data directory (runtime config + secret).
# rm -rf "$APP_DIR/data"
# cp -a data "$APP_DIR/data"

# 3. Generate the self-locating launcher wrapper.
echo ">> Writing $APP_DIR/cheese.sh"
cat > "$APP_DIR/cheese.sh" <<'WRAPPER'
#!/usr/bin/env bash
#
# cheese launcher: cd into this app directory (so lib/ and data/ resolve no
# matter where it is invoked from), verify Java 21+, then run the jar.
#
set -euo pipefail

# Switch to the directory this wrapper lives in, following symlinks.
SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
  DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"
  SOURCE="$(readlink "$SOURCE")"
  [[ "$SOURCE" != /* ]] && SOURCE="$DIR/$SOURCE"
done
APP_DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"
cd "$APP_DIR"

# Require Java on the PATH.
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: 'java' not found on PATH. Java 21 or newer is required." >&2
  exit 1
fi

# Parse the major version. Handles both "21.0.3" and legacy "1.8.0" schemes.
version_string="$(java -version 2>&1 | head -n1 | sed -E 's/.*version "([^"]+)".*/\1/')"
major="${version_string%%.*}"
if [ "$major" = "1" ]; then
  major="$(printf '%s' "$version_string" | cut -d. -f2)"
fi

if ! [ "$major" -ge 21 ] 2>/dev/null; then
  echo "ERROR: Java 21 or newer is required, but found Java ${version_string:-unknown}." >&2
  exit 1
fi

# --enable-native-access silences JLine's restricted-method warning on JDK 24+.
exec java --enable-native-access=ALL-UNNAMED -jar lib/cheese.jar "$@"
WRAPPER

chmod +x "$APP_DIR/cheese.sh"

echo
echo ">> Distribution layout:"
echo "   $(pwd)/$APP_DIR/lib/cheese.jar"
echo "   $(pwd)/$APP_DIR/data/"
echo "   $(pwd)/$APP_DIR/cheese.sh"

# 4. Package the distribution into ./deploy/cheese-java-app-<version>.zip.
VERSION="$(grep -E '^build\.version=' target/classes/META-INF/build-info.properties | cut -d= -f2)"
ZIP="deploy/$APP_DIR-$VERSION.zip"
echo
echo ">> Packaging $ZIP"
mkdir -p deploy
rm -f "$ZIP"
# Exclude runtime log files; keep lib/, data/ and the wrapper.
zip -r9 "$ZIP" "$APP_DIR" -x '*.log'

echo
echo ">> Done."
echo "   Archive:      $(pwd)/$ZIP"
echo "   Run it with:  ./$APP_DIR/cheese.sh <command>"
