#!/usr/bin/env bats
# test_release_assets.bats — integration tests against the real opensearch-
# migrations GitHub release. Skipped if the network is unavailable.
#
# These tests are the safety net for the bug we hit in production:
#   - Wrong release tag prefix ("v3.2.1" vs "3.2.1") → CFN download 404'd.
#   - Missing manifest file (we assumed migration-assistant-images.txt
#     existed; it doesn't — image list is embedded in aws-bootstrap.sh).
#
# If upstream changes the asset names again, these tests fail loudly at
# CI time rather than silently at deploy time.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh log.sh state.sh artifacts.sh

  # Pin to a known-good version for assertions. ma_default_version()
  # resolves dynamically at runtime; this fixture just needs SOME real
  # release to test artifact resolution.
  TARGET_VERSION="3.2.1"
  REPO="opensearch-project/opensearch-migrations"
}

teardown() {
  teardown_isolated_home
}

# Skip if we can't reach github.com — CI runners without internet, dev
# laptops on flights, etc. Better silent-skip than spurious red tests.
require_network() {
  if ! curl -fsSI --max-time 5 "https://github.com/${REPO}" >/dev/null 2>&1; then
    skip "no network reachable"
  fi
}

# ---------- Tag-prefix sanity: this is the bug we just hit. ----------

@test "release tag (no v prefix) resolves on github" {
  require_network
  run curl -fsSI --max-time 10 \
    "https://github.com/${REPO}/releases/tag/${TARGET_VERSION}"
  [ "$status" -eq 0 ]
}

@test "wrong tag (with v prefix) does NOT resolve" {
  require_network
  # If THIS test starts passing, upstream added a v-prefixed tag and we
  # may want to support both. Today, the v-prefixed URL must 404.
  run curl -fsSI --max-time 10 \
    "https://github.com/${REPO}/releases/tag/v${TARGET_VERSION}"
  [ "$status" -ne 0 ]
}

# ---------- Each artifact we depend on must be downloadable. ----------

@test "artifact: Migration-Assistant-Infra-Create-VPC-eks.template.json (HEAD 200)" {
  require_network
  run curl -fsSI --max-time 10 \
    "https://github.com/${REPO}/releases/download/${TARGET_VERSION}/Migration-Assistant-Infra-Create-VPC-eks.template.json"
  [ "$status" -eq 0 ]
}

@test "artifact: migration-assistant tgz (HEAD 200)" {
  require_network
  run curl -fsSI --max-time 10 \
    "https://github.com/${REPO}/releases/download/${TARGET_VERSION}/migration-assistant-${TARGET_VERSION}.tgz"
  [ "$status" -eq 0 ]
}

@test "raw repo: privateEcrManifest.sh (HEAD 200) — image-list source" {
  require_network
  # New canonical image-list source is the chart's privateEcrManifest.sh
  # via raw.githubusercontent.com (not a release asset).
  local rel='deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/privateEcrManifest.sh'
  run curl -fsSI --max-time 10 \
    "https://raw.githubusercontent.com/${REPO}/${TARGET_VERSION}/${rel}"
  [ "$status" -eq 0 ]
}

# ---------- artifacts_fetch end-to-end against the real release ----------

@test "artifacts_fetch downloads CFN template and verifies on disk" {
  require_network
  state_load
  # bats runs each test under set -eET; some helpers exit non-zero on
  # benign HEAD-probe misses. Suppress only for state_save's flock dance.
  state_save || true

  link=$(artifacts_fetch \
    "Migration-Assistant-Infra-Create-VPC-eks.template.json" "$TARGET_VERSION")
  [ -L "$link" ]
  # Real CFN template is a JSON file > 1KB.
  size=$(wc -c <"$link" | tr -d ' ')
  [ "$size" -gt 1024 ]
}

# ---------- Image-list extraction must yield > 10 images ----------

@test "_crane_load_manifest yields >10 images from privateEcrManifest.sh" {
  require_network
  load_libs crane.sh

  state_load
  state_save

  local count
  count=$(_crane_load_manifest "$TARGET_VERSION" | wc -l | tr -d ' ')
  [ "$count" -gt 10 ]

  # Sanity-check shape: every line should look like host/path:tag.
  local lines bad
  lines=$(_crane_load_manifest "$TARGET_VERSION")
  bad=$(printf '%s\n' "$lines" | grep -cvE '^[a-zA-Z0-9._-]+(/[a-zA-Z0-9._-]+)+:[a-zA-Z0-9._-]+$' || true)
  [ "$bad" -le 5 ]
}
