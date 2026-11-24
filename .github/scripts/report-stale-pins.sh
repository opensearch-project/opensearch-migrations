#!/usr/bin/env bash
#
# Report third-party Helm charts and images whose pinned versions are behind upstream.
#
# Covers the `charts:` entries in the migration-assistant chart's values.yaml and the images in its
# ECR mirror manifest, which Dependabot can't read. Each pin is compared with the newest upstream
# version of the same shape: v1.2.3 is only compared with v<n>.<n>.<n>, so pre-releases and variants
# are skipped. Tags without a dotted version (latest, 2023, 21-al2023-headless) aren't checked.
#
# Needs curl, jq, yq (mikefarah v4) and skopeo, which GitHub's Ubuntu runners have.
#
# Usage: report-stale-pins.sh [output-file]   (default: stdout)

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
readonly CHART_DIR="deployment/k8s/charts/aggregates/migrationAssistantWithArgo"
readonly VALUES_FILE="${CHART_DIR}/values.yaml"
readonly MANIFEST_FILE="${CHART_DIR}/infra/mirror/private-ecr-manifest.yaml"
# Seconds allowed for one upstream lookup. A slow registry is reported as a failed lookup.
readonly LOOKUP_TIMEOUT="${LOOKUP_TIMEOUT:-90}"

output="${1:-/dev/stdout}"
work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
stale_count=0
checked_count=0

# Print the newest version on stdin with the same shape as $1: every run of digits in $1 matches
# any number, and everything else must match literally.
newest_like() {
  local re
  re="^$(printf '%s' "$1" | sed -E 's/[][\.*^$+?(){}|]/\\&/g; s/[0-9]+/[0-9]+/g')\$"
  { grep -E "${re}" || true; } | sort -V | tail -n 1
}

# Print the tags of an image or OCI chart repository (registry/path, no tag).
list_tags() {
  local repo="$1"
  # mirror.gcr.io mirrors Docker Hub but doesn't list tags. reg.kyverno.io serves ghcr.io/kyverno
  # but lists tags far more slowly.
  repo="${repo/#mirror.gcr.io\//docker.io/}"
  repo="${repo/#reg.kyverno.io\//ghcr.io/}"
  timeout "${LOOKUP_TIMEOUT}" skopeo list-tags --retry-times 2 "docker://${repo}" | jq -r '.Tags[]'
}

# Print the versions of a chart in an HTTP(S) chart repository, without a leading v (Helm treats
# v1.2.3 and 1.2.3 as the same version). Each index is fetched once, since some are tens of MB.
list_chart_versions() {
  local name="$1" repo="${2%/}" versions
  versions="${work_dir}/index-$(printf '%s' "${repo}" | cksum | cut -d' ' -f1).txt"
  # Explicit returns: this runs in a command substitution under `if`, where set -e doesn't apply.
  if [[ ! -s "${versions}" ]]; then
    timeout "${LOOKUP_TIMEOUT}" curl -fsSL --retry 2 --compressed "${repo}/index.yaml" -o "${versions}.yaml" ||
      return 1
    yq -o json '.entries' "${versions}.yaml" |
      jq -r 'to_entries[] | .key as $chart | .value[] | "\($chart) \(.version)"' > "${versions}" ||
      return 1
  fi
  awk -v chart="${name}" '$1 == chart { sub(/^v/, "", $2); print $2 }' "${versions}"
}

# Append a Markdown table row for one pin to <rows>.stale or <rows>.other.
#   row <rows> <label> <pinned> <lookup-command...>
row() {
  local rows="$1" label="$2" pinned="$3" compare="$3" newest status
  shift 3
  [[ "$1" == list_chart_versions ]] && compare="${compare#v}"
  if [[ ! "${compare}" =~ [0-9]+\.[0-9]+ ]]; then
    echo "| ${label} | \`${pinned}\` | | floating tag, not checked |" >> "${rows}.other"
    return
  fi
  checked_count=$((checked_count + 1))
  if ! newest="$("$@" | newest_like "${compare}")"; then
    echo "::warning::Couldn't look up upstream versions for ${label//\`/} ${pinned}" >&2
    status="lookup failed"
  elif [[ -z "${newest}" ]]; then
    status="no upstream version with the same format"
  elif [[ "$(printf '%s\n%s\n' "${compare}" "${newest}" | sort -V | tail -n 1)" == "${compare}" ]]; then
    status="up to date"
  else
    status="newer available"
    [[ "${compare%%[!0-9v]*}" != "${newest%%[!0-9v]*}" ]] && status="**newer major available**"
    echo "| ${label} | \`${pinned}\` | \`${newest}\` | ${status} |" >> "${rows}.stale"
    stale_count=$((stale_count + 1))
    return
  fi
  echo "| ${label} | \`${pinned}\` | ${newest:+\`${newest}\`} | ${status} |" >> "${rows}.other"
}

# Charts the migration-assistant chart installs. For HTTP repositories the chart name is the key;
# an oci:// repository is the full chart reference.
while IFS=$'\t' read -r name version repository; do
  [[ -z "${version}" || -z "${repository}" ]] && continue
  if [[ "${repository}" == oci://* ]]; then
    row "${work_dir}/charts" "${name}" "${version}" list_tags "${repository#oci://}"
  else
    row "${work_dir}/charts" "${name}" "${version}" list_chart_versions "${name}" "${repository}"
  fi
done < <(yq '.charts | to_entries | .[] | [.key, (.value.version // ""), (.value.repository // "")] | @tsv' "${VALUES_FILE}")

# Images in the mirror manifest.
while read -r image; do
  row "${work_dir}/images" "\`${image%:*}\`" "${image##*:}" list_tags "${image%:*}"
done < <(yq '.images[]' "${MANIFEST_FILE}" | sort -u)

{
  echo "## Dependency staleness report"
  echo
  echo "${stale_count} of ${checked_count} versioned pins have a newer upstream version."
  for section in "charts|Charts in \`${VALUES_FILE}\` (\`charts:\`)" "images|Images in \`${MANIFEST_FILE}\`"; do
    echo
    echo "### ${section#*|}"
    echo
    echo "| Name | Pinned | Newest upstream | Status |"
    echo "|---|---|---|---|"
    cat "${work_dir}/${section%%|*}.stale" "${work_dir}/${section%%|*}.other" 2>/dev/null || true
  done
} > "${output}"
