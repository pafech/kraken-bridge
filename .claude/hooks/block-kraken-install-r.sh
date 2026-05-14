#!/usr/bin/env bash
# Deny `adb install -r` for the kraken-bridge package — see project memory
# project_sideload_workflow.md. The hook reads the full bash command and
# regex-matches the actual sideload patterns the agent tends to produce
# (bare `adb`, absolute path to adb, compound commands with env exports).

cmd=$(jq -r '.tool_input.command // ""')

# Strip quotes/backticks so `"$X/adb"` and `'\''adb'\''` look the same as
# bare `adb` for the regex below. Without this, the closing quote right
# after `adb` (the common env-var-path form) breaks the match.
normalized=$(printf '%s' "$cmd" | tr -d '"`'"'")

# Match: word-boundary `adb`, then anything (flags with args, etc.) up to
# `install`, then anything up to `-r` at a token boundary. Catches:
# - adb install -r
# - adb -s SERIAL install -r
# - /path/to/adb install -r
# - export X=1 && adb install -r
if printf '%s' "$normalized" | grep -qE '(^|[[:space:]/])adb[[:space:]]+(.*[[:space:]])?install[[:space:]]+(.*[[:space:]])?-r([[:space:]]|$)'; then
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"Project sideload rule (memory: project_sideload_workflow.md). Use the clean sequence instead: adb shell am force-stop ch.fbc.krakenbridge ; adb uninstall ch.fbc.krakenbridge ; adb install app/build/outputs/apk/debug/app-debug.apk"}}'
fi
