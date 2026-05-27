#!/usr/bin/env bash
# artifacts.sh — SHA-256-pinned download + content-addressable cache.
#
# Mirrors the marelease.go protocol from PR 3008 in ~80 lines.
# Resolution order per artifact:
#   1. GitHub release asset:
#        https://github.com/opensearch-project/opensearch-migrations/releases/download/<tag>/<name>
#   2. Raw repo fallback (logged as WARN):
#        https://raw.githubusercontent.com/opensearch-project/opensearch-migrations/<tag>/<name>
#   3. Hard fail. No silent fallback to main/latest.
#
# Tag convention: opensearch-migrations releases are tagged "3.2.1" (no
# leading "v"). artifacts_fetch accepts the version-as-tag verbatim. The
# ART_TAG_PREFIX hook lets us flip if upstream ever changes their mind.
#
# Cache layout:
#   $STAGE_DIR/artifacts/.cache/<sha256-of-resolved-url>/<name>
#   $STAGE_DIR/artifacts/<name>  → symlink into the cache dir
#
# Each artifact is verified against a companion <name>.sha256 file (single
# space-separated "<sha256>  <filename>" line, like sha256sum output) when
# one is published. Many opensearch-migrations assets ship without one;
# the CLI logs a WARN and proceeds rather than refusing to deploy.

[[ -n "${__MIGRATE_ARTIFACTS_LOADED:-}" ]] && return 0
__MIGRATE_ARTIFACTS_LOADED=1

ART_REPO='opensearch-project/opensearch-migrations'
ART_TAG_PREFIX=''   # opensearch-migrations tags as "3.2.1", not "v3.2.1"

# artifacts_fetch <name> <version>  →  echoes the local symlink path.
artifacts_fetch() {
  local name="$1" version="$2"
  local tag="${ART_TAG_PREFIX}${version}"
  local primary="https://github.com/${ART_REPO}/releases/download/${tag}/${name}"
  local fallback="https://raw.githubusercontent.com/${ART_REPO}/${tag}/${name}"

  local url=""
  if _http_head "$primary"; then
    url="$primary"
  elif _http_head "$fallback"; then
    log_warn "artifact $name not in releases; using raw repo fallback"
    url="$fallback"
  else
    die "could not resolve artifact $name @ v$version (tried releases + raw)"
  fi

  local key; key=$(_sha256_of_string "$url")
  local cache_dir="$STAGE_DIR/artifacts/.cache/$key"
  local cached_file="$cache_dir/$name"

  if [[ -f "$cached_file" && -f "$cache_dir/.verified" ]]; then
    log_info "artifacts: cache hit for $name@$version"
  else
    mkdir -p "$cache_dir"
    log_info "artifacts: downloading $url"
    curl -fsSL --max-time 120 -o "$cached_file" "$url"
    if _http_head "$url.sha256"; then
      local sha_file="$cache_dir/$name.sha256"
      curl -fsSL --max-time 30 -o "$sha_file" "$url.sha256"
      _verify_sha256 "$cached_file" "$sha_file" \
        || die "SHA mismatch for $name@$version (cache: $cached_file)"
    else
      log_warn "artifacts: no .sha256 published for $name@$version; skipping verification"
    fi
    : >"$cache_dir/.verified"
  fi

  local link="$STAGE_DIR/artifacts/$name"
  ln -sfn "$cached_file" "$link"
  printf '%s\n' "$link"
}

# artifacts_reset_cache — wipe the entire artifact cache for this stage.
artifacts_reset_cache() {
  rm -rf "$STAGE_DIR/artifacts/.cache"
  rm -f  "$STAGE_DIR/artifacts/"*
  log_warn "artifacts: cache reset for stage=$STAGE"
}

# Internal helpers
#
# _http_head: probe whether <url> is reachable. Suppress curl's stderr (the
# "(56) The requested URL returned error: 404" line) — a 404 here is not an
# error, it's a normal "asset doesn't exist; try fallback" branch, and the
# user shouldn't see it in the terminal.
_http_head() {
  curl -fsSI --max-time 10 -o /dev/null "$1" 2>/dev/null
}

_sha256_of_string() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  else
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  fi
}

_sha256_of_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

_verify_sha256() {
  local file="$1" sha_file="$2"
  local expected
  expected=$(awk '{print $1}' <"$sha_file")
  local actual
  actual=$(_sha256_of_file "$file")
  [[ "$expected" == "$actual" ]]
}
