#!/usr/bin/env bash
# crane.sh — mirror images from public registries to a private ECR registry.
#
# The opensearch-migrations release does NOT publish a standalone image
# manifest — instead, the canonical image list lives inside aws-bootstrap.sh
# as a heredoc-style IMAGES="…" variable. We download the bootstrap script
# (which IS published as a release asset), extract that list with awk, and
# iterate `crane copy` over it.
#
# Skipped entirely when MIRROR_IMAGES=N (operator chose --use-public-images).

[[ -n "${__MIGRATE_CRANE_LOADED:-}" ]] && return 0
__MIGRATE_CRANE_LOADED=1

CRANE_BOOTSTRAP_NAME='aws-bootstrap.sh'

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

  ui_step "Downloading aws-bootstrap.sh (image list source, MA v$ma_ver)"
  local bootstrap
  bootstrap=$(artifacts_fetch "$CRANE_BOOTSTRAP_NAME" "$ma_ver")

  ui_step "Extracting image list from aws-bootstrap.sh"
  local image_list
  image_list=$(_extract_images "$bootstrap")
  if [[ -z "$image_list" ]]; then
    die "could not extract IMAGES list from $bootstrap (release format may have changed)"
  fi

  local total
  total=$(printf '%s\n' "$image_list" | wc -l | tr -d ' ')
  ui_info "found $total images to mirror"

  ui_step "Mirroring images to $registry"
  _ecr_login "$registry" "$region" \
    || die "ECR login failed; check AWS credentials and region"

  local i=0 ok=0 failed=0
  while IFS= read -r src; do
    [[ -z "$src" ]] && continue
    ((i++))
    local dst
    dst=$(_dst_for "$src" "$registry")
    printf '  [%d/%d] %s → %s ' "$i" "$total" "$src" "$dst"
    if crane copy "$src" "$dst" >>"$STAGE_DIR/log/crane.log" 2>&1; then
      printf '%s✓%s\n' "$__UI_C_GREEN" "$__UI_C_RESET"
      ((ok++))
    else
      printf '%s✗%s\n' "$__UI_C_RED" "$__UI_C_RESET"
      ((failed++))
      log_error "crane copy failed: $src → $dst"
    fi
  done <<<"$image_list"

  if [[ $failed -gt 0 ]]; then
    ui_err "$failed/$total images failed to mirror; see $STAGE_DIR/log/crane.log"
    return 1
  fi
  ui_ok "mirrored $ok/$total images to $registry"
  state_set CRANE_REGISTRY "$registry"
  state_set CRANE_MIRRORED "1"
  state_set last_step "crane_done"
  state_save
}

# _extract_images <path-to-bootstrap.sh> → emits one image:tag per line.
#
# Awk-based: scan lines between `IMAGES="` and the closing `"` line, drop
# blanks and comments, keep anything that looks like a registry/path:tag.
# Robust to upstream comment churn and section headers.
_extract_images() {
  local path="$1"
  awk '
    /^IMAGES="/   { in_block=1; next }
    in_block && /^"$/ { in_block=0; exit }
    in_block && /^[[:space:]]*$/  { next }
    in_block && /^[[:space:]]*#/  { next }
    in_block { sub(/^[[:space:]]+/, ""); print }
  ' "$path"
}

# _dst_for <src> <registry> → translate a public image URL into the private
# mirror destination. Strategy: keep the path-after-host so the same image
# tree exists under the operator's ECR (e.g. quay.io/strimzi/kafka:X →
# <registry>/strimzi/kafka:X). This matches the convention aws-bootstrap.sh
# uses for crane copy.
_dst_for() {
  local src="$1" registry="$2"
  local path="${src#*/}"            # strip first segment (the host)
  printf '%s/%s\n' "$registry" "$path"
}

_ecr_login() {
  local registry="$1" region="$2"
  aws ecr get-login-password --region "$region" 2>/dev/null \
    | crane auth login --username AWS --password-stdin "$registry" \
        >>"$STAGE_DIR/log/crane.log" 2>&1
}
