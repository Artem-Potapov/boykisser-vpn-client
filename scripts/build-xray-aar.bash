#!/usr/bin/env bash
# Build app/libs/xray.aar via gomobile.
#
# Usage:
#   ./scripts/build-xray-aar.bash
#   ./scripts/build-xray-aar.bash "app/libs/xray.aar" 26
#   OUTPUT=dist/xray.aar ANDROID_API=26 ./scripts/build-xray-aar.bash
#
NO_ARMV7="${NO_ARMV7:-false}"
NO_X86="${NO_X86:-false}"
NO_AMD64="${NO_AMD64:-false}"
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_REL="${OUTPUT:-app/libs/xray.aar}"
ANDROID_API="${ANDROID_API:-26}"
XRAY_CORE_REF="${XRAY_CORE_REF:-main}"

show_help() {
    echo "This is a script to generate the XRAY AAR file via gomobile."
    echo ""
    echo "Positional arguments:"
    echo "  1. Output directory (can be set with OUTPUT=)"
    echo "     Default location: {WORKSPACE_DIR}/app/libs/xray.aar"
    echo "  2. Android API (can be set with ANDROID_API=)"
    echo "     Android API to use for the build. Default: 26"
    echo "  3. XRAY Core reference version (can be set with XRAY_CORE_REF=)"
    echo "     Tells go which version of Xray-Core to pull."
    echo ""
    echo "Flags:"
    echo "  --no-armv7  Disables building for 32-bit arm-eabi-v7 (older phones)"
    echo "  --no-x86    Disables building for 32-bit x86 architecture (emulators)"
    echo "  --no-amd64  Disables building for 64-bit x86-64 architecture (newer emulators)"
}

# Parse flags first; collect positional args separately so flags and
# positionals can be intermixed in any order.
positional=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-armv7)
            NO_ARMV7=true
            shift
            ;;
        --no-x86)
            NO_X86=true
            shift
            ;;
        --no-amd64)
            NO_AMD64=true
            shift
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        -*)
            echo "Unknown option: $1."
            echo "Do -h or --help for help."
            exit 1
            ;;
        *)
            positional+=("$1")
            shift
            ;;
    esac
done

# Apply positional arguments now that flags have been consumed.
[[ ${#positional[@]} -ge 1 ]] && OUTPUT_REL="${positional[0]}"
[[ ${#positional[@]} -ge 2 ]] && ANDROID_API="${positional[1]}"
[[ ${#positional[@]} -ge 3 ]] && XRAY_CORE_REF="${positional[2]}"

if ! command -v go >/dev/null 2>&1; then
  echo "go is required." >&2
  exit 1
fi

OUTPUT_PATH="$WORKSPACE/$OUTPUT_REL"
OUTPUT_DIR="$(dirname "$OUTPUT_PATH")"
mkdir -p "$OUTPUT_DIR"
cd "$WORKSPACE/xray-go"

# Bypass checksum DB for Xray-core (module path lacks the required /vN suffix).
export GONOSUMDB="github.com/xtls/xray-core"

echo "Resolving xray-core@$XRAY_CORE_REF..."
go get "github.com/xtls/xray-core@$XRAY_CORE_REF"

# Tidy after resolving xray-core so its transitive deps are pruned correctly.
go mod tidy

# Add x/mobile AFTER tidy so tidy doesn't remove it (nothing in our source imports it directly).
echo "Resolving golang.org/x/mobile..."
go get golang.org/x/mobile@latest

# Extract the exact x/mobile version now in go.mod.
# gomobile and gobind MUST be installed at this exact version — a mismatch causes
# gobind to fail with "no Go package in golang.org/x/mobile/bind".
MOBILE_VERSION="$(go list -m golang.org/x/mobile | awk '{print $2}')"
if [[ -z "$MOBILE_VERSION" ]]; then
  echo "Failed to resolve golang.org/x/mobile version." >&2
  exit 1
fi

echo "Installing gomobile and gobind at $MOBILE_VERSION..."
go install "golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION"
go install "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"

# Just in case it didn't register.
if ! command -v gomobile >/dev/null 2>&1; then
  echo "Looks like gomobile didn't register. Trying to add it to PATH automatically..."
  echo "Exported PATH at $HOME/go/bin/ (gomobile is most likely there)"
  export PATH="$PATH:$HOME/go/bin/"
  if ! command -v gomobile >/dev/null 2>&1; then
    echo "gomobile is required after installation." >&2
    echo "Looks like gomobile isn't there and you need to add it to PATH manually."
    exit 1
  fi
fi

echo "Initializing gomobile..."
gomobile init

targets="android/arm64"
if [[ "$NO_ARMV7" != "true" ]]; then
  targets="$targets,android/arm"
fi
if [[ "$NO_X86" != "true" ]]; then
  targets="$targets,android/386"
fi
if [[ "$NO_AMD64" != "true" ]]; then
  targets="$targets,android/amd64"
fi

echo "Running gomobile bind for targets: $targets..."
gomobile bind \
  "-target=$targets" \
  "-androidapi=$ANDROID_API" \
  -ldflags="-checklinkname=0" \
  -o "$OUTPUT_PATH" \
  .

if [[ ! -f "$OUTPUT_PATH" ]]; then
  echo "gomobile bind completed without producing $OUTPUT_PATH" >&2
  exit 1
fi

echo "AAR generated at $OUTPUT_PATH"
