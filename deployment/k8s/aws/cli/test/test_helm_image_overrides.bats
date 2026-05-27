#!/usr/bin/env bats
# test_helm_image_overrides.bats — regression guards for the bug that
# caused `default-helm-installer` to ImagePullBackOff with:
#   "failed to pull and unpack image
#    docker.io/migrations/migration_console:3.2.1: pull access denied"
#
# Cause: the chart's default values use bare repos like
# `migrations/migration_console`. We previously wrote
# `global.imageRegistry: ""` which the chart ignores. Without --set
# `images.<name>.repository=…` the image resolves to docker.io and 404s.
#
# These tests verify:
#   1. _helm_build_public_image_flags emits the 5 expected --set pairs
#      pointing at public.ecr.aws/opensearchproject/opensearch-migrations-*
#   2. _helm_build_mirrored_image_flags points at the operator's ECR with
#      the migrations_<name>_<version> tag layout aws-bootstrap.sh uses
#   3. _helm_extract_chart_values pulls values.yaml + valuesEks.yaml from
#      a real chart tarball into a stable directory
#   4. _write_helm_values no longer writes the bogus global.imageRegistry

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  PROJECT_ROOT="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
  export PROJECT_ROOT
  load_libs _common.sh ui.sh log.sh state.sh version.sh discover.sh \
            install_tools.sh artifacts.sh wizard.sh cfn.sh crane.sh helm.sh
  log_init
}

teardown() {
  teardown_isolated_home
}

# ---------- (1) public image flags ----------

@test "_helm_build_public_image_flags emits all five chart images" {
  local flags=()
  _helm_build_public_image_flags 3.2.1 flags

  # 5 images × 2 flag pairs (repository + tag) × 2 elements per --set = 20
  [ "${#flags[@]}" -eq 20 ]

  # Spot-check each image points at public.ecr.aws/opensearchproject.
  local f; printf '%s\n' "${flags[@]}" >/tmp/flags.$$
  grep -q 'images.captureProxy.repository=public.ecr.aws/opensearchproject/opensearch-migrations-traffic-capture-proxy' /tmp/flags.$$
  grep -q 'images.trafficReplayer.repository=public.ecr.aws/opensearchproject/opensearch-migrations-traffic-replayer'   /tmp/flags.$$
  grep -q 'images.reindexFromSnapshot.repository=public.ecr.aws/opensearchproject/opensearch-migrations-reindex-from-snapshot' /tmp/flags.$$
  grep -q 'images.migrationConsole.repository=public.ecr.aws/opensearchproject/opensearch-migrations-console' /tmp/flags.$$
  grep -q 'images.installer.repository=public.ecr.aws/opensearchproject/opensearch-migrations-console'        /tmp/flags.$$
  rm -f /tmp/flags.$$
}

@test "_helm_build_public_image_flags tags every image with the requested version" {
  local flags=()
  _helm_build_public_image_flags 3.2.1 flags
  printf '%s\n' "${flags[@]}" >/tmp/flags.$$
  grep -q 'images.captureProxy.tag=3.2.1'        /tmp/flags.$$
  grep -q 'images.trafficReplayer.tag=3.2.1'     /tmp/flags.$$
  grep -q 'images.reindexFromSnapshot.tag=3.2.1' /tmp/flags.$$
  grep -q 'images.migrationConsole.tag=3.2.1'    /tmp/flags.$$
  grep -q 'images.installer.tag=3.2.1'           /tmp/flags.$$
  rm -f /tmp/flags.$$
}

@test "_helm_build_public_image_flags handles non-3.x versions" {
  local flags=()
  _helm_build_public_image_flags 2.9.0 flags
  printf '%s\n' "${flags[@]}" | grep -q 'images.migrationConsole.tag=2.9.0'
}

# ---------- (2) mirrored image flags ----------

@test "_helm_build_mirrored_image_flags points all images at the private registry" {
  local registry='629003556176.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-default'
  local flags=()
  _helm_build_mirrored_image_flags "$registry" 3.2.1 flags

  printf '%s\n' "${flags[@]}" >/tmp/flags.$$
  grep -q "images.captureProxy.repository=$registry"        /tmp/flags.$$
  grep -q "images.trafficReplayer.repository=$registry"     /tmp/flags.$$
  grep -q "images.reindexFromSnapshot.repository=$registry" /tmp/flags.$$
  grep -q "images.migrationConsole.repository=$registry"    /tmp/flags.$$
  grep -q "images.installer.repository=$registry"           /tmp/flags.$$
  rm -f /tmp/flags.$$
}

@test "_helm_build_mirrored_image_flags uses the migrations_<name>_<ver> tag layout" {
  local flags=()
  _helm_build_mirrored_image_flags 'r' 3.2.1 flags
  printf '%s\n' "${flags[@]}" >/tmp/flags.$$
  grep -q 'images.captureProxy.tag=migrations_capture_proxy_3.2.1'                /tmp/flags.$$
  grep -q 'images.trafficReplayer.tag=migrations_traffic_replayer_3.2.1'          /tmp/flags.$$
  grep -q 'images.reindexFromSnapshot.tag=migrations_reindex_from_snapshot_3.2.1' /tmp/flags.$$
  grep -q 'images.migrationConsole.tag=migrations_migration_console_3.2.1'        /tmp/flags.$$
  grep -q 'images.installer.tag=migrations_migration_console_3.2.1'               /tmp/flags.$$
  rm -f /tmp/flags.$$
}

# ---------- (3) values extraction ----------

@test "_helm_extract_chart_values extracts values.yaml and valuesEks.yaml" {
  # Build a tiny tarball that mimics the chart structure.
  local fake; fake=$(mktemp -d)
  mkdir -p "$fake/migration-assistant"
  printf 'foo: bar\n' >"$fake/migration-assistant/values.yaml"
  printf 'eks: true\n' >"$fake/migration-assistant/valuesEks.yaml"
  printf 'name: x\n' >"$fake/migration-assistant/Chart.yaml"
  ( cd "$fake" && tar -czf "$STAGE_DIR/fake-chart.tgz" migration-assistant/ )
  rm -rf "$fake"

  local out; out=$(_helm_extract_chart_values "$STAGE_DIR/fake-chart.tgz")
  [ -d "$out" ]
  [ -f "$out/values.yaml" ]
  [ -f "$out/valuesEks.yaml" ]
  grep -q 'foo: bar' "$out/values.yaml"
  grep -q 'eks: true' "$out/valuesEks.yaml"
}

@test "_helm_extract_chart_values dies cleanly on a malformed tarball" {
  printf 'this is not a tarball' >"$STAGE_DIR/bad-chart.tgz"
  run _helm_extract_chart_values "$STAGE_DIR/bad-chart.tgz"
  [ "$status" -ne 0 ]
  [[ "$output" == *"could not extract values"* ]]
}

# ---------- (4) values file is no longer the bogus global.imageRegistry ----------

@test "_write_helm_values does NOT contain global.imageRegistry" {
  local f="$STAGE_DIR/values.yaml"
  _write_helm_values "$f"
  ! grep -q 'global.imageRegistry' "$f"
  ! grep -q 'imageRegistry' "$f"
}

@test "_write_helm_values is a valid YAML stub (no syntax errors)" {
  local f="$STAGE_DIR/values.yaml"
  _write_helm_values "$f"
  [ -f "$f" ]
  # No tabs, no obvious YAML syntax issues.
  ! grep -P '\t' "$f"
}

# ---------- end-to-end against the real release tarball ----------

@test "_helm_extract_chart_values works on the real migration-assistant-3.2.1.tgz" {
  if ! curl -fsSI --max-time 5 'https://github.com' >/dev/null 2>&1; then
    skip "no network"
  fi
  local chart
  chart=$(artifacts_fetch "migration-assistant-3.2.1.tgz" "3.2.1")
  local out; out=$(_helm_extract_chart_values "$chart")
  [ -f "$out/values.yaml" ]
  [ -f "$out/valuesEks.yaml" ]
  # Sanity: real values.yaml has an `images:` block.
  grep -q '^images:' "$out/values.yaml"
  # Real valuesEks.yaml has the EKS-specific `useCustomKarpenterNodePool`.
  grep -q 'useCustomKarpenterNodePool' "$out/valuesEks.yaml"
}
