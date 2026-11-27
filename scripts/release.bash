#!/usr/bin/env bash
# Never trace the password reads or the environment handed to Gradle.
set +x
set -euo pipefail

usage() {
    printf '%s\n' 'Usage: scripts/release.bash --real-release [--key-alias ALIAS]' \
        'Read the keystore password from stdin (hidden at a terminal).' \
        'An optional second line supplies a distinct key password; blank or EOF reuses the first.'
}

case "${1:-}" in
    --real-release) shift ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
esac

key_alias=""
while (($#)); do
    case "$1" in
        --key-alias)
            if (($# < 2)) || [[ -z "$2" ]]; then
                printf '%s\n' '--key-alias requires a non-empty alias.' >&2
                exit 2
            fi
            key_alias="$2"
            shift 2
            ;;
        --help|-h) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
keystore="${XTLS_REAL_RELEASE_KEYSTORE:-$HOME/.keys/boykisser-release-key.jks}"
[[ "$keystore" = /* ]] || keystore="$PWD/$keystore"
if [[ ! -f "$keystore" || ! -r "$keystore" ]]; then
    printf '%s\n' 'Release signing keystore is not a readable file.' >&2
    exit 1
fi
cd -- "$repo_root"

# Clear inherited export attributes on the temporary variables before reading credentials.
unset store_password key_password
read_password() {
    local prompt="$1" variable="$2"
    if [[ -t 0 ]]; then
        IFS= read -r -s -p "$prompt" "$variable" || :
        printf '\n' >&2
    else
        IFS= read -r "$variable" || :
    fi
}

read_password 'Release keystore password: ' store_password
if [[ -z ${store_password:-} ]]; then
    printf '%s\n' 'A non-empty keystore password is required on stdin.' >&2
    exit 1
fi
read_password 'Signing key password (Enter to reuse the keystore password): ' key_password

export XTLS_REAL_RELEASE_STORE_FILE="$keystore"
export XTLS_REAL_RELEASE_STORE_PASSWORD="$store_password"
export XTLS_REAL_RELEASE_KEY_PASSWORD="${key_password:-$store_password}"
export XTLS_REAL_RELEASE_KEY_ALIAS="$key_alias"
unset store_password key_password

exec ./gradlew --no-daemon --no-configuration-cache --no-scan :app:assembleRelease </dev/null
