#!/usr/bin/env bash
# Writes the data for the About section of the compat app into its assets, before Gradle builds it:
# - about.json: the repository, whether it is a fork (and of which repository), the commit, the
#   build year and the contributors
# - avatars/<login>.png: each contributor's GitHub avatar
#
# Contributors are listed by commit count, as GitHub's contributors API returns them, except that
# the original author and the fork maintainer always come first. Bot accounts are left out.
#
# Needs GH_TOKEN, GITHUB_REPOSITORY and GITHUB_SHA (set in GitHub Actions), gh, jq and curl.
set -euo pipefail

out="probe/app/src/main/assets/about"
fixed=(jabe1122 Lucas04-nhr)

rm -rf "$out"
mkdir -p "$out/avatars"
people="$(mktemp)"
trap 'rm -f "$people"' EXIT

repo="$(gh api "repos/$GITHUB_REPOSITORY")"
logins=("${fixed[@]}")
while read -r login; do
  skip=false
  for f in "${fixed[@]}"; do
    [ "${login,,}" = "${f,,}" ] && skip=true
  done
  $skip || logins+=("$login")
done < <(gh api --paginate "repos/$GITHUB_REPOSITORY/contributors?per_page=100" \
  --jq '.[] | select(.type != "Bot") | .login')

for login in "${logins[@]}"; do
  if ! user="$(gh api "users/$login")"; then
    echo "::warning title=About page::Could not read the GitHub user $login; leaving it out."
    continue
  fi
  login="$(jq -r .login <<< "$user")"
  avatar="avatars/$login.png"
  # avatar_url already has a query string (?v=4)
  if ! curl -fsSL --retry 3 -o "$out/$avatar" "$(jq -r .avatar_url <<< "$user")&s=128"; then
    echo "::warning title=About page::Could not download the avatar of $login."
    rm -f "$out/$avatar"
    avatar=""
  fi
  jq -c --arg avatar "$avatar" \
    '{login, name: (.name // .login), url: .html_url, avatar: $avatar}' \
    <<< "$user" >> "$people"
done

jq -n --argjson repo "$repo" --arg commit "$GITHUB_SHA" --argjson year "$(date +%Y)" \
  --slurpfile people "$people" '{
    repository: $repo.full_name,
    url: $repo.html_url,
    fork: $repo.fork,
    parent: ($repo.parent.full_name // null),
    parentUrl: ($repo.parent.html_url // null),
    commit: $commit,
    year: $year,
    contributors: $people
  }' > "$out/about.json"

jq -r '"\(.repository) (fork: \(.fork), parent: \(.parent // "none")): \(.contributors | map(.login) | join(", "))"' "$out/about.json"
