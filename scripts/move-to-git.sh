#!/usr/bin/env bash
set -euo pipefail
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
target_dir="$HOME/git/nanny-app"
if [[ "$source_dir" == "$target_dir" ]]; then
    echo "Project is already at $target_dir"
    exit 0
fi
[[ -d "$target_dir/.git" ]] || { echo "Git repository missing: $target_dir" >&2; exit 1; }
# Refuse to overwrite existing project files; retain the destination's Git metadata.
if [[ -n "$(find "$target_dir" -mindepth 1 -maxdepth 1 ! -name .git -print -quit)" ]]; then
    echo "Destination contains files other than .git; transfer stopped." >&2
    exit 1
fi
rsync -a --remove-source-files \
    --exclude=/.git --exclude=/.agents --exclude=/.aws --exclude=/.codex \
    --exclude=/.gradle --exclude=/.gradle-home --exclude=/.kotlin --exclude=/.android-home \
    "$source_dir/" "$target_dir/"
echo "Project moved to $target_dir; original build caches retained."
