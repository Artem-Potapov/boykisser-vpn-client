#!/usr/bin/env bash
# Fire-and-forget wrapper around `croc send`.
#
# Usage:
#   ./scripts/croc-send-app.bash <path-to-file>
#
# Stdout (parsed by the calling agent — one key=value per line):
#   CODE=<the receive code>          # share this with the receiver
#   RECV=croc --yes <code>           # convenience: full receive command
#   LOG=<path to log file>           # tail to watch transfer progress
#   PID=<croc background pid>        # for diagnostics
#
# Why this script exists:
#   `croc send` keeps stdin attached and the process alive until the receiver
#   pulls the file, so an agent that just calls croc directly cannot read its
#   own output or continue working. This script detaches croc into the
#   background, waits only long enough for croc to publish the code to the
#   relay, and exits — leaving croc itself running so the transfer can
#   complete normally on the user's end.
#
# Quirks worked around (see memory: croc_send_headless_nul):
#   1. croc's auto-generated code never flushes to stdout when stdout is not
#      a tty, so we generate the code ourselves and pass it via --code.
#   2. With stdin attached, headless croc thinks it's being asked to send
#      stdin as the payload and leaks croc-stdin-* temp files, so we redirect
#      stdin from /dev/null (Git Bash translates this to NUL for the native
#      Windows croc binary).
#
# Intended for Git Bash on Windows. Croc is not installed in WSL.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <file>" >&2
  exit 2
fi

FILE_ARG="$1"
if [[ ! -e "$FILE_ARG" ]]; then
  echo "Error: path not found: $FILE_ARG" >&2
  exit 2
fi

# Locate croc. Prefer PATH; otherwise fall back to known Windows install dirs
# because the calling shell may not inherit Chocolatey / Scoop / WinGet PATH
# entries (the Bash tool in this environment, for instance, doesn't).
CROC_BIN=""
if command -v croc >/dev/null 2>&1; then
  CROC_BIN="croc"
else
  for candidate in \
    "/c/ProgramData/chocolatey/bin/croc.exe" \
    "$HOME/scoop/shims/croc.exe" \
    "/c/Program Files/croc/croc.exe" \
    "${LOCALAPPDATA:-}/Microsoft/WinGet/Links/croc.exe"; do
    if [[ -x "$candidate" ]]; then
      CROC_BIN="$candidate"
      break
    fi
  done
fi
if [[ -z "$CROC_BIN" ]]; then
  echo "Error: croc not found on PATH or in known install locations" >&2
  exit 127
fi

# Generate a one-shot code as three short lowercase tokens. Croc accepts any
# string as the code; the dashes are purely for human typability.
# `/dev/urandom` is infinite, so once `head -c 5` closes its pipe end, `tr`
# gets SIGPIPE on its next write and exits 141. Under `set -o pipefail` that
# would trip `set -e`, so swallow with `|| true` and mute tr's broken-pipe
# stderr — head's bytes are what we actually consume.
gen_token() {
  LC_ALL=C tr -dc 'a-z' < /dev/urandom 2>/dev/null | head -c 5 || true
}
CODE="$(gen_token)-$(gen_token)-$(gen_token)"

LOG="$(mktemp -t croc-send-XXXXXX.log)"

# Background croc. --yes skips the interactive confirmation prompt; --code
# locks the code we just printed; stdin from /dev/null and stdout/stderr to
# the log together free us to exit without croc dying.
nohup "$CROC_BIN" --yes send --code "$CODE" "$FILE_ARG" < /dev/null > "$LOG" 2>&1 &
CROC_PID=$!
disown "$CROC_PID" 2>/dev/null || true

# Wait until croc has registered with the relay. Croc prints "Code is:" only
# after the rendezvous is open, so returning before this line could leave the
# receiver racing against an unready room.
DEADLINE=$(( $(date +%s) + 30 ))
while (( $(date +%s) < DEADLINE )); do
  if grep -q "Code is:" "$LOG" 2>/dev/null; then
    break
  fi
  if ! kill -0 "$CROC_PID" 2>/dev/null; then
    echo "Error: croc exited before registering with the relay." >&2
    echo "--- log ---" >&2
    cat "$LOG" >&2
    exit 1
  fi
  sleep 0.5
done

if ! grep -q "Code is:" "$LOG" 2>/dev/null; then
  echo "Error: croc did not register with the relay within 30s." >&2
  echo "--- log ---" >&2
  cat "$LOG" >&2
  kill "$CROC_PID" 2>/dev/null || true
  exit 1
fi

echo "CODE=$CODE"
echo "RECV=croc --yes $CODE"
echo "LOG=$LOG"
echo "PID=$CROC_PID"