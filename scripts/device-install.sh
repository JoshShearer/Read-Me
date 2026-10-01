#!/usr/bin/env bash
# npm run device:install - install the current release build on the one attached phone.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take interactive
device_install_release
