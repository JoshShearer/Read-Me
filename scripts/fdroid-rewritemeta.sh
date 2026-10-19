#!/usr/bin/env bash
# npm run fdroid:rewritemeta -- <fdroiddata checkout>. Formats and lints the merge request's
# metadata/io.loopstring.readme.yml with the fdroidserver fdroiddata's CI runs: its
# `fdroid rewritemeta` job checks out fdroidserver master, not a release. A local fdroidserver
# 2.4.5 left the long binary: URLs on one line where master wraps them, and the 1.0.5 pipeline
# (fdroiddata !51088, 898bbc04e) failed on that. Rewrites the file in place; exits non-zero if
# lint fails. Needs docker and network.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=${1:?usage: npm run fdroid:rewritemeta -- <fdroiddata checkout>}
REPO=$(cd "$REPO" && pwd)
APPID=io.loopstring.readme
[ -f "$REPO/metadata/$APPID.yml" ] || { echo "no such file: $REPO/metadata/$APPID.yml"; exit 1; }
command -v docker >/dev/null || { echo "refused: no docker"; exit 1; }
IMAGE=registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie
docker pull -q "$IMAGE" >/dev/null
# As the caller's uid, so the rewritten file is not left owned by root.
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$REPO:/repo" -w /repo "$IMAGE" bash -c '
set -euo pipefail
git clone -q --depth 50 https://gitlab.com/fdroid/fdroidserver.git /tmp/fdroidserver
echo "fdroid:rewritemeta: fdroidserver master $(git -C /tmp/fdroidserver log -1 --format="%h %cs")"
export PATH=/tmp/fdroidserver:$PATH PYTHONPATH=/tmp/fdroidserver:/tmp/fdroidserver/examples serverwebroot=/tmp
fdroid rewritemeta '"$APPID"'
fdroid lint '"$APPID"'
'
echo "fdroid:rewritemeta: done; lint passed (git diff in $REPO shows what changed)"
