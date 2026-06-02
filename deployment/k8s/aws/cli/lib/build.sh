#!/usr/bin/env bash
# build.sh — build Migration Assistant images from source via gradle.
#
# Triggered by `--build`. Builds Migration Assistant images from
# source via gradle and pushes them to the per-stage ECR.
#
#   1. Resolve $base_dir (the opensearch-migrations repo root). Default
#      walks up from the CLI install ($LIB_DIR/../../../../..). Override
#      with --base-dir.
#   2. Resolve $IMAGE_TAG. Default "latest"; override with --image-tag.
#   3. ECR login: aws ecr get-login-password | docker login.
#   4. Set up the Kubernetes-hosted buildx builder (one per cluster
#      context, name "builder-<context>"). Sourced from the repo's
#      buildImages/backends/eksKubernetesBuildkit.sh, the same module the
#   5. Run ./gradlew :buildImages:buildImagesToRegistry with -PregistryEndpoint,
#      -Pbuilder, -PimageVersion, optionally -PskipTestImages=true, and -x test.
#      One automatic retry after a 10-second sleep on first failure.
#   6. Tear down the buildx builder so the buildkit pods get reaped.
#
# After `build_images_or_skip` returns, MIRROR_IMAGES is forced to N (we
# already pushed to the private ECR) and IMAGE_TAG is in state.env so the
# helm.sh image-flags builder can use the per-build tag instead of $MA_VERSION.

[[ -n "${__MIGRATE_BUILD_LOADED:-}" ]] && return 0
__MIGRATE_BUILD_LOADED=1

# build_images_or_skip — entry point. Returns 0 (no-op) when --build was
# not passed; otherwise builds + pushes images to the private ECR and
# returns 0 on success or non-zero on failure.
#
# Idempotent: re-running after a successful build re-pushes the images
# (gradle's own up-to-date checks decide whether to skip layers).
build_images_or_skip() {
  local build; build=$(state_get BUILD_FROM_SOURCE N)
  if [[ "$build" != "Y" ]]; then
    return 0
  fi

  # When --ma-images-source is set, the operator wants images mirrored
  # from another ECR — that's crane.sh's job, not ours.
  local ma_src; ma_src=$(state_get MA_IMAGES_SOURCE "")
  if [[ -n "$ma_src" ]]; then
    ui_info "--build + --ma-images-source: skipping local build, crane will mirror from $ma_src"
    return 0
  fi

  local stack_name; stack_name=$(state_get CFN_STACK_NAME)
  local region;     region=$(state_get AWS_REGION)
  [[ -z "$stack_name" ]] && die "--build: CFN_STACK_NAME not in state (CFN deploy must run first)"
  [[ -z "$region" ]] && die "--build: AWS_REGION not in state"

  # Resolve the destination ECR registry from CFN outputs.
  local outputs registry
  outputs=$(cfn_outputs "$stack_name" "$region")
  registry=$(_cfn_pick "$outputs" MIGRATIONS_ECR_REGISTRY ECRRegistry)
  [[ -z "$registry" ]] && die "--build: could not resolve MIGRATIONS_ECR_REGISTRY from CFN outputs"
  state_set CRANE_REGISTRY "$registry"

  local base_dir; base_dir=$(_build_resolve_base_dir)
  local image_tag; image_tag=$(state_get IMAGE_TAG "latest")

  # Validate the repo root has the gradle bits we need before doing any
  # AWS-side work.
  [[ -x "$base_dir/gradlew" ]] \
    || die "--build: $base_dir/gradlew not found or not executable. Pass --base-dir <repo-root>."
  [[ -d "$base_dir/buildImages" ]] \
    || die "--build: $base_dir/buildImages/ not found (this CLI was installed standalone). Pass --base-dir <repo-root>."

  # Need docker for buildx and the ECR login.
  require_cmd docker "install Docker Desktop / Docker Engine; --build needs docker buildx"

  # Need a kube context for the Kubernetes-hosted buildkit builder.
  local kube_ctx="${KUBE_CONTEXT:-}"
  [[ -z "$kube_ctx" ]] && kube_ctx=$(state_get KUBECTL_CONTEXT "")
  [[ -z "$kube_ctx" ]] && die "--build: kube context not set; helm_kctx_init must run first"

  ui_step "Building MA images from source ($base_dir, tag=$image_tag)"
  ui_dim "  registry: $registry"
  ui_dim "  kube context: $kube_ctx"

  _build_ecr_login "$registry" "$region" || return 1

  local builder; builder=$(_build_builder_name "$kube_ctx")
  if ! _build_setup_buildkit "$builder" "$base_dir"; then
    ui_err "buildkit setup failed; see $LOG_FILE"
    return 1
  fi

  local skip_test_images; skip_test_images=$(state_get SKIP_TEST_IMAGES N)
  local skip_test_arg=()
  [[ "$skip_test_images" == "Y" ]] && skip_test_arg=(-PskipTestImages=true)

  ui_step "Running gradle :buildImages:buildImagesToRegistry"
  ui_dim "  this is the long part — push to ECR for ~5 images, ~5-15 min on a cold builder"

  local rc=0
  if ! _build_gradle_invoke "$base_dir" "$registry" "$builder" "$image_tag" "${skip_test_arg[@]+"${skip_test_arg[@]}"}"; then
    ui_warn "gradle build failed; sleeping 10s and retrying once"
    sleep 10
    if ! _build_gradle_invoke "$base_dir" "$registry" "$builder" "$image_tag" "${skip_test_arg[@]+"${skip_test_arg[@]}"}"; then
      rc=1
    fi
  fi

  # Always tear down the builder, even on failure — leaving it behind
  # leaks buildkit pods on the cluster.
  ui_dim "  cleaning up buildkit builder ($builder) to free buildkit pods"
  docker buildx rm "$builder" >>"$LOG_FILE" 2>&1 || true

  if [[ $rc -ne 0 ]]; then
    ui_err "image build failed after retry; see $LOG_FILE"
    return 1
  fi

  # Now we have the images at $registry tagged migrations_<name>_<image_tag>.
  # Force MIRROR_IMAGES=Y (the helm path that uses CRANE_REGISTRY+per-image
  # tags) and pin the tag for that path.
  state_set MIRROR_IMAGES Y
  state_set BUILD_IMAGE_TAG "$image_tag"

  # When the operator builds images from source, the chart's CRDs MUST
  # come from the same source tree — otherwise the locally-built console
  # submits CRDs with fields the released chart's CRDs don't recognize
  # (e.g. spec.compressionEnabled on DataSnapshot, added after 3.2.1).
# --build implies use the
  # in-repo chart unless the operator explicitly passed --ma-chart-dir.
  if [[ -z "$(state_get MA_CHART_DIR "")" ]]; then
    local chart_dir="$base_dir/deployment/k8s/charts/aggregates/migrationAssistantWithArgo"
    if [[ -d "$chart_dir" && -f "$chart_dir/Chart.yaml" ]]; then
      state_set MA_CHART_DIR "$chart_dir"
      ui_dim "  --build: using in-repo chart at $chart_dir"
    else
      ui_warn "--build: in-repo chart not found at $chart_dir; falling back to release tarball (CRD/code skew possible)"
    fi
  fi

  state_set last_step "build_done"
  state_save
  ui_ok "MA images built and pushed to $registry (tag=$image_tag)"
  return 0
}

# _build_resolve_base_dir — print the repo root.
#
# Order of resolution:
#   1. state.env BASE_DIR (set by --base-dir).
#   2. The directory the CLI was installed from (only valid when running
#      out of a checkout: $LIB_DIR/../../../../.. is the repo root from
#      deployment/k8s/aws/cli/lib/).
#   3. $PWD if it has gradlew + buildImages/.
#   4. die.
_build_resolve_base_dir() {
  local explicit; explicit=$(state_get BASE_DIR "")
  if [[ -n "$explicit" ]]; then
    [[ -d "$explicit" ]] || die "--base-dir does not exist: $explicit"
    (cd "$explicit" && pwd)
    return 0
  fi

  # CLI install layout (5 hops): cli/lib → cli → aws → k8s → deployment → repo-root.
  local cli_repo_root="$LIB_DIR/../../../../.."
  if [[ -x "$cli_repo_root/gradlew" && -d "$cli_repo_root/buildImages" ]]; then
    (cd "$cli_repo_root" && pwd)
    return 0
  fi

  if [[ -x "$PWD/gradlew" && -d "$PWD/buildImages" ]]; then
    printf '%s\n' "$PWD"
    return 0
  fi

  die "--build: could not locate opensearch-migrations repo root (no gradlew + buildImages/ found). Pass --base-dir <repo-root>."
}

# _build_builder_name <kube-context> → docker buildx builder name. Strips
# characters docker buildx rejects in builder names.
_build_builder_name() {
  local ctx="$1"
  printf 'builder-%s\n' "${ctx//[^a-zA-Z0-9_-]/-}"
}

# _build_ecr_login <registry> <region> — `docker login` against the ECR
# domain. Strips any /repo path from the registry first.
_build_ecr_login() {
  local registry="$1" region="$2"
  local domain="${registry%%/*}"
  ui_step "Logging into ECR: $domain"
  local pw
  if ! pw=$(aws ecr get-login-password --region "$region" 2>>"$LOG_FILE"); then
    ui_err "aws ecr get-login-password failed; see $LOG_FILE"
    return 1
  fi
  if ! printf '%s' "$pw" | docker login --username AWS --password-stdin "$domain" >>"$LOG_FILE" 2>&1; then
    ui_err "docker login --username AWS failed; see $LOG_FILE"
    return 1
  fi
  return 0
}

# _build_setup_buildkit <builder-name> <base-dir>
#
# Reuses an already-healthy builder; otherwise removes any stale entry
# and runs the repo's eksKubernetesBuildkit.sh `setup_build_backend`
# function so buildx pod placement / tolerations stay consistent.
_build_setup_buildkit() {
  local builder="$1" base_dir="$2"
  if docker buildx inspect "$builder" --bootstrap >>"$LOG_FILE" 2>&1; then
    ui_dim "  buildkit builder '$builder' already healthy"
    return 0
  fi

  ui_dim "  setting up Kubernetes-hosted buildkit builder: $builder"
  docker buildx rm "$builder" >>"$LOG_FILE" 2>&1 || true

  local backend_script="$base_dir/buildImages/backends/eksKubernetesBuildkit.sh"
  if [[ ! -f "$backend_script" ]]; then
    ui_err "missing $backend_script (in-repo buildkit backend)"
    return 1
  fi

  # The backend script defines `setup_build_backend` and depends on
  # KUBE_CONTEXT + BUILDER_NAME being exported.
  export KUBE_CONTEXT="${KUBE_CONTEXT:-$(state_get KUBECTL_CONTEXT "")}"
  export BUILDER_NAME="$builder"
  # shellcheck disable=SC1090
  if ! ( source "$backend_script" && setup_build_backend ) >>"$LOG_FILE" 2>&1; then
    ui_err "setup_build_backend failed; see $LOG_FILE"
    return 1
  fi
  return 0
}

# _build_gradle_invoke <base-dir> <registry> <builder> <image-tag> [-Pskip…]
#
# One gradle invocation, output streamed to log_stream so `tail -f` shows
# it and the operator gets the build log inline. Returns gradle's exit
# code.
_build_gradle_invoke() {
  local base_dir="$1" registry="$2" builder="$3" image_tag="$4"
  shift 4
  local extra=("$@")

  # MULTI_ARCH_NATIVE toggles per-arch builds in :buildImages.
  export MULTI_ARCH_NATIVE=true

  local rc=0
  set +e
  log_stream "gradle-build" "$base_dir/gradlew" -p "$base_dir" \
    :buildImages:buildImagesToRegistry \
    -PregistryEndpoint="$registry" \
    -Pbuilder="$builder" \
    -PimageVersion="$image_tag" \
    "${extra[@]+"${extra[@]}"}" \
    -x test
  rc=$?
  set -e
  return "$rc"
}
