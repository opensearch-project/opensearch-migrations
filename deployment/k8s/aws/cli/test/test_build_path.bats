#!/usr/bin/env bats
# test_build_path.bats — verify --build resolves base_dir, builds the
# correct gradle invocation, and forces MIRROR_IMAGES=Y.
#
# We don't actually run gradle (that would build everything). Instead we
# stub gradlew, docker, aws, and assert on the recorded calls.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh log.sh state.sh version.sh discover.sh \
            install_tools.sh artifacts.sh wizard.sh dashboard.sh cfn.sh \
            crane.sh build.sh helm.sh
  log_init

  # Pretend we're in a checkout: lay out a fake repo root with gradlew +
  # buildImages/. Tests can override this by setting BASE_DIR via state_set.
  REPO_ROOT="$BATS_TEST_TMPDIR/fake-repo"
  mkdir -p "$REPO_ROOT/buildImages/backends"
  cat >"$REPO_ROOT/gradlew" <<'GRADLEW'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$STUB_DIR/gradlew.calls"
exit 0
GRADLEW
  chmod +x "$REPO_ROOT/gradlew"
  cat >"$REPO_ROOT/buildImages/backends/eksKubernetesBuildkit.sh" <<'BACKEND'
setup_build_backend() { echo "fake-buildkit-setup ctx=$KUBE_CONTEXT name=$BUILDER_NAME" >&2; }
BACKEND

  state_set BASE_DIR "$REPO_ROOT"
  state_set CFN_STACK_NAME "fake-stack"
  state_set AWS_REGION "us-east-1"
  state_set KUBECTL_CONTEXT "ctx-test"
  export KUBE_CONTEXT="ctx-test"
}

teardown() { teardown_isolated_home; }

# Helper: stub the AWS calls _build_ecr_login + _build_images_or_skip
# emit, and the cfn_outputs that build.sh reads.
_stub_aws_and_cfn() {
  mkstub aws 'PASSWORD'  # for `aws ecr get-login-password`
  mkstub docker ''
  mkstub helm ''
  # Override cfn_outputs (a function) to return our test ECR.
  cfn_outputs() {
    printf 'MIGRATIONS_ECR_REGISTRY=%s\n' "111111111111.dkr.ecr.us-east-1.amazonaws.com/fake-ecr"
  }
}

@test "build_images_or_skip: no-op when BUILD_FROM_SOURCE is unset" {
  state_set BUILD_FROM_SOURCE N
  run build_images_or_skip
  [ "$status" -eq 0 ]
}

@test "build_images_or_skip: short-circuits when --ma-images-source is set" {
  state_set BUILD_FROM_SOURCE Y
  state_set MA_IMAGES_SOURCE "999999999999.dkr.ecr.us-east-1.amazonaws.com/other"
  _stub_aws_and_cfn
  run build_images_or_skip
  [ "$status" -eq 0 ]
  [[ "$output" == *"--build + --ma-images-source"* ]]
  # gradle MUST NOT have been invoked.
  [ ! -f "$STUB_DIR/gradlew.calls" ] || ! grep -q '.' "$STUB_DIR/gradlew.calls"
}

@test "_build_resolve_base_dir prefers state.env BASE_DIR" {
  state_set BASE_DIR "$REPO_ROOT"
  result=$(_build_resolve_base_dir)
  [ "$result" = "$REPO_ROOT" ]
}

@test "_build_resolve_base_dir dies when state's BASE_DIR doesn't exist" {
  state_set BASE_DIR "/no/such/path"
  run _build_resolve_base_dir
  [ "$status" -ne 0 ]
  [[ "$output" == *"--base-dir does not exist"* ]]
}

@test "_build_builder_name strips invalid chars" {
  result=$(_build_builder_name "ctx.with/slashes:and:colons")
  [ "$result" = "builder-ctx-with-slashes-and-colons" ]
}

@test "build_images_or_skip: invokes gradle with right -P flags" {
  state_set BUILD_FROM_SOURCE Y
  state_set IMAGE_TAG "v1"
  _stub_aws_and_cfn
  # Make `docker buildx inspect` return non-zero so setup runs (and we
  # exercise the backend script path).
  cat >"$STUB_DIR/docker" <<'DOCKER'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$STUB_DIR/docker.calls"
case "$1 $2" in
  "buildx inspect") exit 1 ;;  # not healthy → trigger setup
  *) exit 0 ;;
esac
DOCKER
  chmod +x "$STUB_DIR/docker"

  run build_images_or_skip
  [ "$status" -eq 0 ]
  # gradle invoked with -PimageVersion=v1 and -PregistryEndpoint=…
  cat "$STUB_DIR/gradlew.calls" >&2
  grep -q -- '-PimageVersion=v1' "$STUB_DIR/gradlew.calls"
  grep -q -- '-PregistryEndpoint=111111111111.dkr.ecr.us-east-1.amazonaws.com/fake-ecr' "$STUB_DIR/gradlew.calls"
  grep -q -- '-Pbuilder=builder-ctx-test' "$STUB_DIR/gradlew.calls"
  grep -q -- ':buildImages:buildImagesToRegistry' "$STUB_DIR/gradlew.calls"
  grep -q -- '-x test' "$STUB_DIR/gradlew.calls"
  # State updates: MIRROR_IMAGES=Y, BUILD_IMAGE_TAG=v1
  # `run` ran in a subshell — reload from disk to see what was persisted.
  state_load
  [ "$(state_get MIRROR_IMAGES)" = "Y" ]
  [ "$(state_get BUILD_IMAGE_TAG)" = "v1" ]
}

@test "build_images_or_skip: --skip-test-images adds -PskipTestImages=true" {
  state_set BUILD_FROM_SOURCE Y
  state_set SKIP_TEST_IMAGES Y
  _stub_aws_and_cfn
  cat >"$STUB_DIR/docker" <<'DOCKER'
#!/usr/bin/env bash
exit 0  # buildx inspect succeeds: skip backend setup
DOCKER
  chmod +x "$STUB_DIR/docker"

  run build_images_or_skip
  [ "$status" -eq 0 ]
  grep -q -- '-PskipTestImages=true' "$STUB_DIR/gradlew.calls"
}

@test "build_images_or_skip: gradle failure retries once then fails" {
  state_set BUILD_FROM_SOURCE Y
  _stub_aws_and_cfn
  cat >"$STUB_DIR/docker" <<'DOCKER'
#!/usr/bin/env bash
exit 0
DOCKER
  chmod +x "$STUB_DIR/docker"
  # gradle fails every time
  cat >"$REPO_ROOT/gradlew" <<'GRADLEW'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$STUB_DIR/gradlew.calls"
exit 1
GRADLEW
  chmod +x "$REPO_ROOT/gradlew"

  run build_images_or_skip
  [ "$status" -ne 0 ]
  # Two invocations recorded (first + retry).
  count=$(wc -l <"$STUB_DIR/gradlew.calls")
  [ "$count" -eq 2 ]
}
