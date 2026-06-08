#!/bin/bash
set -e

# Post-merge setup for the Codex AI for Android project.
# Rebuilds the web-chat frontend and syncs it into the Android app assets.

cd "$(dirname "$0")/.."

cd web-chat
npm install --no-audit --no-fund
npm run build
npm run sync:android-assets
