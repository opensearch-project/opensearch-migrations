#!/usr/bin/env bash
# crane.sh — mirror images from public registries to a private ECR registry.
#
# The canonical image list lives in privateEcrManifest.sh inside the
# opensearch-migrations repo. It's a tiny pinned manifest:
#     IMAGES="
#       quay.io/jetstack/cert-manager-controller:v1.17.2
#       …
#     "
#     CHARTS="…"
# We source it (or, in CI/local-build, read a vendored copy) and iterate
# `crane copy` over it.
#
# Skipped entirely when MIRROR_IMAGES=N (operator chose --use-public-images).

[[ -n "${__MIGRATE_CRANE_LOADED:-}" ]] && return 0
__MIGRATE_CRANE_LOADED=1

# Path of privateEcrManifest.sh inside the opensearch-migrations repo.
# Pulled from the tag matching MA_VERSION via raw.githubusercontent.com
# (so we match the exact image versions the chart expects).
CRANE_MANIFEST_REL_PATH='deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/privateEcrManifest.sh'

crane_mirror_or_skip() {
  local mirror; mirror=$(state_get MIRROR_IMAGES Y)
  if [[ "$mirror" != Y ]]; then
    ui_info "MIRROR_IMAGES=N → skipping crane mirror"
    state_set last_step "crane_skipped"
    state_save
    return 0
  fi

  if [[ "$(state_get CRANE_MIRRORED 0)" == "1" ]]; then
    ui_ok "images already mirrored (per state); skipping"
    return 0
  fi

  local ma_ver; ma_ver=$(state_get MA_VERSION)
  local region; region=$(state_get AWS_REGION)
  local account; account=$(state_get AWS_ACCOUNT)
  local stack_name; stack_name=$(state_get CFN_STACK_NAME)

  # Look up the registry CFN published. opensearch-migrations stacks call
  # it MIGRATIONS_ECR_REGISTRY; older/forked templates may use ECRRegistry.
  # If neither is present, synthesize the default account-level registry.
  local outputs; outputs=$(cfn_outputs "$stack_name" "$region")
  local registry; registry=$(_cfn_pick "$outputs" MIGRATIONS_ECR_REGISTRY ECRRegistry)
  [[ -z "$registry" ]] && registry="${account}.dkr.ecr.${region}.amazonaws.com"

  ui_step "Loading image manifest (MA v$ma_ver)"
  local image_list
  image_list=$(_crane_load_manifest "$ma_ver")
  if [[ -z "$image_list" ]]; then
    die "could not load image manifest for MA $ma_ver"
  fi

  local total
  total=$(printf '%s\n' "$image_list" | wc -l | tr -d ' ')
  ui_info "found $total images to mirror"

  # The CFN-published `registry` is `<host>/<single-repo>`. We mirror under
  # a `mirrored/` prefix INSIDE that account's ECR (matching upstream
  # aws-bootstrap.sh's `mirrored/<image-no-tag>` layout). Each unique
  # image-path becomes its own ECR repository (ECR repo names allow "/"
  # but each must be created before push).
  local ecr_host="${registry%%/*}"

  ui_step "Mirroring images to $ecr_host (under mirrored/)"
  if ! _ecr_login "$ecr_host" "$region"; then
    return 1
  fi

  local i=0 ok=0 failed=0
  while IFS= read -r src; do
    [[ -z "$src" ]] && continue
    ((i++))
    local dst ecr_repo
    dst=$(_dst_for "$src" "$ecr_host")
    ecr_repo=$(_ecr_repo_for "$src")
    printf '  [%d/%d] %s → %s ' "$i" "$total" "$src" "$dst"

    # Pre-create the ECR repo idempotently. RepositoryAlreadyExists is
    # tolerated; any other failure surfaces below as a crane copy failure.
    aws ecr create-repository \
      --repository-name "$ecr_repo" \
      --region "$region" \
      --image-scanning-configuration scanOnPush=false \
      >>"$STAGE_DIR/log/crane.log" 2>&1 || true

    if _crane_copy_retry "$src" "$dst"; then
      printf '%s✓%s\n' "$__UI_C_GREEN" "$__UI_C_RESET"
      ((ok++))
    else
      printf '%s✗%s\n' "$__UI_C_RED" "$__UI_C_RESET"
      ((failed++))
      log_error "crane copy failed (after retries): $src → $dst (ecr_repo=$ecr_repo)"
    fi
  done <<<"$image_list"

  if [[ $failed -gt 0 ]]; then
    ui_err "$failed/$total images failed to mirror; see $STAGE_DIR/log/crane.log"
    return 1
  fi
  ui_ok "mirrored $ok/$total public images to $ecr_host (under mirrored/)"

  # Now mirror the 5 MA-team images. They follow a different layout —
  # upstream aws-bootstrap.sh pushes them all into a SINGLE ECR repo
  # ($MIGRATIONS_ECR_REGISTRY = <host>/migration-ecr-<stage>-<region>)
  # with name-disambiguating tags like `migrations_migration_console_3.2.1`.
  # _helm_build_mirrored_image_flags assumes that exact layout, so we
  # match it here.
  if ! _crane_mirror_ma_images "$registry" "$ma_ver" "$region"; then
    return 1
  fi

  state_set CRANE_REGISTRY "$registry"   # full <host>/<repo>, for helm
  state_set CRANE_ECR_HOST "$ecr_host"   # bare host, for diagnostics
  state_set CRANE_MIRRORED "1"
  state_set last_step "crane_done"
  state_save
}

# _crane_mirror_ma_images <ecr_repo_url> <ma_version> <region>
#
# Mirrors the 5 MA-team images. Source: public.ecr.aws/opensearchproject/
# opensearch-migrations-<suffix>:<version>. Destination: $registry as
# *single repo* with disambiguating tags so the chart's per-component
# image overrides (images.captureProxy.repository=$REGISTRY +
# tag=migrations_capture_proxy_<ver>) resolve to a real pull URL.
_crane_mirror_ma_images() {
  local registry="$1" ma_ver="$2" region="$3"

  # Source registry: --ma-images-source overrides public.ecr.aws so
  # restricted accounts can copy from another ECR they already have
  # access to. When set, legacy bootstrap reads tag "latest" rather
  # than $MA_VERSION because that flow is for already-tagged images.
  local ma_src; ma_src=$(state_get MA_IMAGES_SOURCE "")
  ui_step "Mirroring 5 MA-team images to $registry (single-repo, disambiguating tags)"
  if [[ -n "$ma_src" ]]; then
    ui_dim "  source: --ma-images-source=$ma_src"
  fi

  # build_name|public_suffix — must match upstream aws-bootstrap.sh's
  # MA_IMAGES table (lines ~1534).
  local pairs=(
    "capture_proxy|traffic-capture-proxy"
    "traffic_replayer|traffic-replayer"
    "reindex_from_snapshot|reindex-from-snapshot"
    "migration_console|console"
  )

  local pair build_name suffix src dst i=0 ok=0 failed=0 total=4
  for pair in "${pairs[@]}"; do
    ((i++))
    build_name=${pair%%|*}
    suffix=${pair##*|}
    if [[ -n "$ma_src" ]]; then
      src="${ma_src}:migrations_${build_name}_latest"
    else
      src="public.ecr.aws/opensearchproject/opensearch-migrations-${suffix}:${ma_ver}"
    fi
    dst="${registry}:migrations_${build_name}_${ma_ver}"
    printf '  [%d/%d] %s → %s ' "$i" "$total" "$src" "$dst"

    if _crane_copy_retry "$src" "$dst"; then
      printf '%s✓%s\n' "$__UI_C_GREEN" "$__UI_C_RESET"
      ((ok++))
    else
      printf '%s✗%s\n' "$__UI_C_RED" "$__UI_C_RESET"
      ((failed++))
      log_error "MA-image mirror failed: $src → $dst"
    fi
  done

  if [[ $failed -gt 0 ]]; then
    ui_err "$failed/$total MA images failed to mirror; see $STAGE_DIR/log/crane.log"
    return 1
  fi
  ui_ok "mirrored $ok/$total MA-team images"
  return 0
}

# _crane_load_manifest <ma_version> → emits one image:tag per line.
#
# Resolves privateEcrManifest.sh in this preference order:
#   1. ./skills/privateEcrManifest.sh (vendored alongside the CLI tarball)
#   2. ../../charts/aggregates/migrationAssistantWithArgo/scripts/privateEcrManifest.sh
#      (in-repo dev mode — when running from a checkout)
#   3. raw.githubusercontent.com tag <ver> (release fetch via artifacts_fetch)
#
# Then sources it in a subshell to read $IMAGES, strips blanks/comments,
# emits one entry per line.
_crane_load_manifest() {
  local ma_ver="$1"
  local cli_root manifest=""
  cli_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

  # 1. Vendored copy alongside the CLI (assemble-bootstrap.sh ships this).
  if [[ -f "$cli_root/skills/privateEcrManifest.sh" ]]; then
    manifest="$cli_root/skills/privateEcrManifest.sh"
  # 2. In-repo dev mode (when invoked from a clone).
  elif [[ -f "$cli_root/../../charts/aggregates/migrationAssistantWithArgo/scripts/privateEcrManifest.sh" ]]; then
    manifest="$cli_root/../../charts/aggregates/migrationAssistantWithArgo/scripts/privateEcrManifest.sh"
  # 3. Fetch from the release tag.
  else
    manifest=$(artifacts_fetch_raw "privateEcrManifest.sh" "$ma_ver" \
        "$CRANE_MANIFEST_REL_PATH") || return 1
  fi

  # Source in a subshell so $IMAGES doesn't leak into our state.
  ( # shellcheck disable=SC1090
    . "$manifest"
    printf '%s\n' "$IMAGES" \
      | awk 'NF>0 && $1 !~ /^#/ {print $1}'
  )
}

# _dst_for <src> <ecr-host>  →  destination URL for crane copy.
#
# Layout matches upstream aws-bootstrap.sh's mirror_image_to_ecr:
#
#   src   = quay.io/strimzi/kafka:0.50.1-kafka-4.0.0
#   dst   = <ecr-host>/mirrored/quay.io/strimzi/kafka:0.50.1-kafka-4.0.0
#
# ECR repo names allow `/`. Each unique pre-tag path becomes its own
# ECR repository (created on demand by mirror_or_skip).
_dst_for() {
  local src="$1" ecr_host="$2"
  printf '%s/mirrored/%s\n' "$ecr_host" "$src"
}

# _crane_copy_retry <src> <dst>
#
# Runs `crane copy` with exponential backoff. Mirrors aws-bootstrap.sh's
# retry policy: up to 5 attempts, sleeping 5/10/20/40s between them.
# Total worst-case wait: ~75 seconds before giving up on one image.
#
# Some failures are unambiguously fatal — retrying changes nothing — so
# we short-circuit the loop:
#   * NAME_UNKNOWN / repository does not exist  (we should have created it)
#   * UNAUTHORIZED / DENIED auth-token-expired  (re-login is needed, not retry)
#   * NotFound / manifest unknown               (image doesn't exist upstream)
#
# Tunable via env: CRANE_RETRY_ATTEMPTS (default 5), CRANE_RETRY_INITIAL_S
# (default 5). Tests can lower these to keep the suite fast.
_crane_copy_retry() {
  local src="$1" dst="$2"
  local max="${CRANE_RETRY_ATTEMPTS:-5}"
  local initial="${CRANE_RETRY_INITIAL_S:-5}"
  local attempt=0 sleep_s="$initial" out

  while (( attempt < max )); do
    attempt=$(( attempt + 1 ))
    out=$(crane copy "$src" "$dst" 2>&1)
    local rc=$?
    # Always tee the attempt's output into the log file.
    printf '%s\n' "$out" >>"$STAGE_DIR/log/crane.log"
    if (( rc == 0 )); then
      [[ $attempt -gt 1 ]] && log_info "crane copy: $src succeeded on attempt $attempt"
      return 0
    fi

    # Fatal-but-no-retry classes. Bail early so the operator gets the
    # real error sooner.
    case "$out" in
      *"NAME_UNKNOWN"*|*"repository does not exist"*)
        log_error "crane copy: $src → $dst — ECR repo missing (giving up, not retrying)"
        return $rc
        ;;
      *"UNAUTHORIZED"*|*"DENIED"*|*"authorization token has expired"*)
        log_error "crane copy: $src → $dst — auth failure (giving up, not retrying)"
        return $rc
        ;;
      *"MANIFEST_UNKNOWN"*|*"manifest unknown"*)
        log_error "crane copy: $src → $dst — source manifest does not exist (giving up)"
        return $rc
        ;;
    esac

    if (( attempt < max )); then
      log_warn "crane copy: $src attempt $attempt/$max failed (rc=$rc); sleeping ${sleep_s}s before retry"
      sleep "$sleep_s"
      sleep_s=$(( sleep_s * 2 ))
    else
      log_error "crane copy: $src exhausted $max attempts; giving up"
      return $rc
    fi
  done
  return 1
}

# _ecr_repo_for <src>  →  ECR repository name (no host, no tag).
# Strips the `:tag` suffix and prepends `mirrored/` to match the layout
# above. Used to pre-create the target repo via aws ecr create-repository.
#
#   src      = quay.io/strimzi/kafka:0.50.1-kafka-4.0.0
#   ecr_repo = mirrored/quay.io/strimzi/kafka
_ecr_repo_for() {
  local src="$1"
  # Strip ":tag" — guard against URLs without a tag (rare, but possible).
  local no_tag="${src%:*}"
  if [[ "$no_tag" == "$src" ]]; then
    no_tag="$src"
  fi
  printf 'mirrored/%s\n' "$no_tag"
}

# _ecr_login <registry> <region>
#
# Authenticates `crane` against the operator's private ECR. Common failure
# modes (and what we surface to help the operator fix them):
#
#   * AWS_REGION unset or wrong               → aws prints UnrecognizedClient or
#                                                You must specify a region
#   * IAM principal lacks ecr:GetAuthorizationToken
#                                              → AccessDenied
#   * Registry hostname is wrong / typo       → crane prints DNS / 404
#   * Registry is in a different region       → 401 InvalidSignatureException
#   * crane not on PATH                       → command -v fails up front
#
# Each failure produces a clear stderr line for the operator AND a
# detailed log entry. We do NOT bury the real error inside a generic
# "ECR login failed" string.
_ecr_login() {
  local registry="$1" region="$2"
  local registry_host="${registry%%/*}"  # strip any /repo path

  log_info "ecr-login: registry=$registry_host region=$region"

  if ! command -v crane >/dev/null 2>&1; then
    ui_err "crane not on PATH; run \`brew install crane\` or rerun with --use-public-images"
    return 1
  fi
  if ! command -v aws >/dev/null 2>&1; then
    ui_err "aws CLI not on PATH"
    return 1
  fi

  # Step 1: get the password. Surface the exact aws CLI error if this fails.
  local pw aws_err
  aws_err="$STAGE_DIR/log/.ecr-login.err.$$"
  pw=$(aws ecr get-login-password --region "$region" 2>"$aws_err")
  local aws_rc=$?
  if [[ $aws_rc -ne 0 || -z "$pw" ]]; then
    ui_err "aws ecr get-login-password failed (rc=$aws_rc):"
    while IFS= read -r line; do
      printf '    %s%s%s\n' "$__UI_C_DIM" "$line" "$__UI_C_RESET" >&2
      log_error "aws-ecr: $line"
    done <"$aws_err"
    rm -f "$aws_err"
    ui_dim "  hint: verify \`aws sts get-caller-identity\` works and your region matches your registry"
    ui_dim "        registry: $registry_host (expects region: $region)"
    return 1
  fi
  rm -f "$aws_err"

  # Step 2: log crane in to the operator's private ECR. Capture both
  # rc + stderr.
  local crane_err
  crane_err="$STAGE_DIR/log/.crane-login.err.$$"
  if ! printf '%s' "$pw" \
    | crane auth login --username AWS --password-stdin "$registry_host" \
        >>"$STAGE_DIR/log/crane.log" 2>"$crane_err"; then
    ui_err "crane auth login failed:"
    while IFS= read -r line; do
      printf '    %s%s%s\n' "$__UI_C_DIM" "$line" "$__UI_C_RESET" >&2
      log_error "crane-login: $line"
    done <"$crane_err"
    rm -f "$crane_err"
    ui_dim "  hint: confirm registry hostname is reachable: $registry_host"
    ui_dim "        and that crane writes to \$HOME/.docker/config.json (HOME=$HOME)"
    return 1
  fi
  rm -f "$crane_err"

  # Step 3: ALSO log in to public.ecr.aws. Many sub-charts pull from
  # public.ecr.aws (ack-controllers, aws-privateca-issuer, …) and crane
  # will reuse a stale ~/.docker/config.json entry if one exists, producing
  #   DENIED: Your authorization token has expired. Reauthenticate and try again.
  # Refresh with a fresh ecr-public token. ecr-public is us-east-1-only.
  # Tolerated-failure: restricted accounts / networks may block ecr-public.
  # We carry on with the existing config.json (often fine for the
  # unauthenticated public.ecr.aws read path).
  local pubpw=""
  set +e
  pubpw=$(aws ecr-public get-login-password --region us-east-1 2>/dev/null)
  set -e
  if [[ -n "$pubpw" ]]; then
    set +e
    printf '%s' "$pubpw" \
      | crane auth login --username AWS --password-stdin public.ecr.aws \
          >>"$STAGE_DIR/log/crane.log" 2>&1
    local pubrc=$?
    set -e
    if [[ $pubrc -eq 0 ]]; then
      log_info "ecr-login: also authenticated against public.ecr.aws"
    else
      log_warn "ecr-login: public.ecr.aws auth failed; will rely on unauthenticated reads"
    fi
  else
    log_warn "ecr-login: aws ecr-public get-login-password returned empty; skipping public.ecr.aws auth"
  fi

  log_info "ecr-login: OK"
}
