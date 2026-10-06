#!/bin/bash
# Runs inside registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie for npm run fdroid:build
# (REA-38): the "fdroid build" job of fdroiddata's .gitlab-ci.yml (2026-10-03), for our builds,
# with fdroidserver as that job takes it: the pinned commit as the root of trust, then master
# fast-forwarded, so each run uses fdroidserver master of its day (logged below). $CI_PROJECT_DIR is an fdroiddata checkout holding
# our recipe; the source to build is the local clone the recipe's Repo names.
set -ex
[ $# -gt 0 ] || { echo "usage: ci-build.sh <appid:versionCode>..."; exit 1; }
cd "$CI_PROJECT_DIR"
# The clone is mounted from the host and owned by another uid.
git config --system --add safe.directory '*'
chown -R "$(whoami)" .
test -d build || mkdir build
apt-get update -qq
test -n "$fdroidserver" || source /etc/profile.d/bsenv.sh
sdkmanager "platform-tools" "build-tools;31.0.0" >/dev/null
rm -rf "$fdroidserver"
git clone -q --shallow-since=2026-07-13 https://gitlab.com/fdroid/fdroidserver.git "$fdroidserver"
git -C "$fdroidserver" checkout -q -B master a35fdfddd9c66823987a410566a6101186e39c84
git -C "$fdroidserver" pull -q origin master --ff-only
echo "fdroidserver $(git -C "$fdroidserver" rev-parse HEAD)"
export PATH="$fdroidserver:$PATH" PYTHONPATH="$fdroidserver:$fdroidserver/examples" PYTHONUNBUFFERED=true serverwebroot=/tmp
git -C "$home_vagrant/gradlew-fdroid" pull -q || true
git -C /tmp/ clone -q https://gitlab.com/fdroid/fdroid-bootstrap-buildserver.git
git -C /tmp/fdroid-bootstrap-buildserver checkout -q -B master b33507043e6750065745dd2d8c35d3bb5c633c11
cp /tmp/fdroid-bootstrap-buildserver/roles/production_hardening/files/gitconfig "$CI_PROJECT_DIR/../.gitconfig"
for d in logs tmp unsigned "$home_vagrant/.android" "$home_vagrant/.gradle" "$home_vagrant/metadata"; do
  test -d "$d" || mkdir "$d"; chown -R vagrant "$d"
done
ln -sfn "$CI_PROJECT_DIR/tmp" "$home_vagrant/tmp"
ln -sfn "$CI_PROJECT_DIR/srclibs" "$home_vagrant/srclibs"
export GRADLE_USER_HOME=$home_vagrant/.gradle
fdroid="sudo --preserve-env --user vagrant env PATH=$fdroidserver:$PATH env PYTHONPATH=$fdroidserver:$fdroidserver/examples env PYTHONUNBUFFERED=true env TERM=$TERM env HOME=$home_vagrant fdroid"
apt-get install -y -qq sudo openjdk-21-jdk-headless >/dev/null
update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java
appid=${1%:*}
cp -R "$CI_PROJECT_DIR/build" "$home_vagrant/build"
cp -R "metadata/$appid.yml" "$home_vagrant/metadata"
chown -R vagrant "$home_vagrant" "$CI_PROJECT_DIR"
cd "$home_vagrant"
ln -s "$CI_PROJECT_DIR" "$home_vagrant/fdroiddata"
ln -s "$CI_PROJECT_DIR/../.gitconfig" "$home_vagrant/.gitconfig"
$fdroid fetchsrclibs "$@" --verbose
rm "$home_vagrant/fdroiddata" "$home_vagrant/.gitconfig"
(unset CI; $fdroid build --verbose --test --refresh-scanner --on-server --no-tarball "$@")
