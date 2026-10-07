#!/usr/bin/env bash
# Fetches EXACTLY the pinned upstream commit (and its submodules at the recorded SHAs) into ./upstream-src and
# verifies both SHAs from UPSTREAM.lock. Never a floating branch, never a tag alone.
set -euo pipefail
here=$(cd "$(dirname "$0")/../.." && pwd)
# shellcheck disable=SC1091
source <(grep -E '^[A-Z_]+=' "$here/UPSTREAM.lock")
rm -rf upstream-src
git init -q upstream-src
git -C upstream-src remote add origin "$UPSTREAM_REPO"
git -C upstream-src fetch -q --depth 1 origin "$UPSTREAM_COMMIT"
git -C upstream-src -c advice.detachedHead=false checkout -q FETCH_HEAD
git -C upstream-src submodule update -q --init --recursive --depth 1
head=$(git -C upstream-src rev-parse HEAD)
hbb=$(git -C upstream-src/libs/hbb_common rev-parse HEAD)
echo "upstream $head  hbb_common $hbb"
[ "$head" = "$UPSTREAM_COMMIT" ] || { echo "UPSTREAM COMMIT MISMATCH"; exit 1; }
[ "$hbb" = "$HBB_COMMON_COMMIT" ] || { echo "HBB_COMMON COMMIT MISMATCH"; exit 1; }
if [ -n "${UPSTREAM_TAG:-}" ]; then
  tagc=$(git ls-remote "$UPSTREAM_REPO" "refs/tags/$UPSTREAM_TAG^{}" "refs/tags/$UPSTREAM_TAG" | awk '{print $1}' | tail -1)
  echo "tag $UPSTREAM_TAG -> $tagc (informational; the build uses the SHA)"
fi
