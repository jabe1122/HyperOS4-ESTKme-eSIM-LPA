#!/usr/bin/env bash
# Send a ServerChan3 push from GitHub Actions.
#
# Environment: SERVERCHAN_SENDKEY, TITLE, SHORT, DESP (Markdown).
# A push is best effort: without a valid send key, or when the push fails,
# the script adds a warning annotation and exits successfully.
set -euo pipefail

warn() {
  echo "::warning title=$1::$2"
  exit 0
}

if [[ -z "${SERVERCHAN_SENDKEY:-}" ]]; then
  warn "ServerChan push skipped" "Set the SERVERCHAN_SENDKEY secret to enable pushes."
fi
if [[ ! "$SERVERCHAN_SENDKEY" =~ ^sctp([0-9]+)t ]]; then
  warn "ServerChan push skipped" "SERVERCHAN_SENDKEY is not a ServerChan3 key (sctp...). Check the secret."
fi
uid="${BASH_REMATCH[1]}"
echo "::add-mask::$uid"

run_url="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
desp="${DESP:-}"$'\n\n'"Check the [workflow run]($run_url) for detailed logs."

if ! response=$(curl -sS --fail-with-body --retry 3 \
  --data-urlencode "title=$TITLE" \
  --data-urlencode "desp=$desp" \
  --data-urlencode "short=${SHORT:-$TITLE}" \
  --data-urlencode "tags=GitHub" \
  "https://$uid.push.ft07.com/send/$SERVERCHAN_SENDKEY.send"); then
  warn "ServerChan push failed" "The ServerChan request failed. Check that SERVERCHAN_SENDKEY is correct."
fi
# The response may carry a read key and the logs are public, so print only the status.
status=$(jq -r '"code=\(.code // "none") message=\(.message // "")"' <<< "$response" 2>/dev/null) \
  || warn "ServerChan push failed" "ServerChan returned a response that is not JSON."
echo "$status"
if ! jq -e '(.code // 0) == 0' <<< "$response" > /dev/null; then
  warn "ServerChan push failed" "ServerChan rejected the push ($status). Check that SERVERCHAN_SENDKEY is correct."
fi
