#!/usr/bin/env bash
#
# Report third-party Helm charts and container images whose pinned versions are behind upstream.
#
# Dependabot can't see these pins: the charts under `charts:` in the migration-assistant chart's
# values.yaml, the charts and images in its ECR mirror manifest, and the base images in
# buildImages/build.gradle. For each pin this looks up the newest upstream version with the same
# shape. For example, v1.2.3 is only compared with v<n>.<n>.<n> tags, which skips pre-releases and
# variants. The result is a Markdown report. Nothing is changed.
#
# Tags without a dotted version, like latest, 2023 or 21-al2023-headless, float on upstream and are
# listed as not checked.
#
# Needs curl, jq, yq (mikefarah v4) and skopeo. GitHub's Ubuntu runners have all four.
#
# Usage: report-stale-pins.sh [output-file]   (default: stdout)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly REPO_ROOT
readonly CHART_DIR="deployment/k8s/charts/aggregates/migrationAssistantWithArgo"
readonly VALUES_FILE="${CHART_DIR}/values.yaml"
readonly MANIFEST_FILE="${CHART_DIR}/infra/mirror/private-ecr-manifest.yaml"
readonly BUILD_GRADLE="buildImages/build.gradle"
# Seconds allowed for one upstream lookup. A slow registry is reported as a failed lookup.
readonly LOOKUP_TIMEOUT="${LOOKUP_TIMEOUT:-90}"

for tool in curl jq yq skopeo; do
  command -v "${tool}" >/dev/null || { echo "${tool} is required" >&2; exit 2; }
done

output="${1:-/dev/stdout}"
work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
cd "${REPO_ROOT}"

stale_count=0
checked_count=0

# Turn a version into an extended regex for versions of the same shape: every run of digits
# becomes [0-9]+ and everything else must match literally.
shape_regex() {
  printf '%s' "$1" | sed -E 's/[][\.*^$+?(){}|]/\\&/g; s/[0-9]+/[0-9]+/g'
}

# Print the newest version on stdin with the same shape as $1, or nothing.
newest_like() {
  local re
  re="^$(shape_regex "$1")\$"
  { grep -E "${re}" || true; } | sort -V | tail -n 1
}

first_number() {
  [[ "$1" =~ ([0-9]+) ]] && printf '%s' "${BASH_REMATCH[1]}"
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

# Print the versions of a chart in an HTTP(S) chart repository. Each repository index is
# downloaded and reduced to "chart version" lines once per run, since some are tens of MB.
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
  # Helm treats v1.2.3 and 1.2.3 as the same version, and some repositories switched styles.
  awk -v chart="${name}" '$1 == chart { sub(/^v/, "", $2); print $2 }' "${versions}"
}

# Append one Markdown table row comparing a pin with upstream.
#   row <stale-rows-file> <other-rows-file> <label> <pinned> <lookup-command...>
row() {
  local stale_rows="$1" other_rows="$2" label="$3" pinned="$4"
  shift 4
  local compare="${pinned%%@*}" shown newest status
  shown="\`${compare}\`"
  [[ "${pinned}" == *@* ]] && shown+=" + digest"
  # list_chart_versions drops a leading v, so compare chart versions without it too.
  [[ "$1" == list_chart_versions ]] && compare="${compare#v}"
  if [[ ! "${compare}" =~ [0-9]+\.[0-9]+ ]]; then
    echo "| ${label} | ${shown} | | floating tag, not checked |" >> "${other_rows}"
    return
  fi
  checked_count=$((checked_count + 1))
  if ! newest="$("$@" | newest_like "${compare}")"; then
    echo "::warning::Couldn't look up upstream versions for ${label//\`/} ${pinned}" >&2
    echo "| ${label} | ${shown} | | lookup failed |" >> "${other_rows}"
    return
  fi
  if [[ -z "${newest}" ]]; then
    echo "| ${label} | ${shown} | | no upstream version with the same format |" >> "${other_rows}"
  elif [[ "${newest}" == "${compare}" ]]; then
    echo "| ${label} | ${shown} | \`${newest}\` | up to date |" >> "${other_rows}"
  elif [[ "$(printf '%s\n%s\n' "${compare}" "${newest}" | sort -V | tail -n 1)" == "${compare}" ]]; then
    echo "| ${label} | ${shown} | \`${newest}\` | pinned version not found upstream |" >> "${other_rows}"
  else
    status="newer available"
    [[ "$(first_number "${compare}")" != "$(first_number "${newest}")" ]] && status="**newer major available**"
    echo "| ${label} | ${shown} | \`${newest}\` | ${status} |" >> "${stale_rows}"
    stale_count=$((stale_count + 1))
  fi
}

# Print a section: a heading and a table with the pins that are behind first.
#   section <title> <source-description> <rows-prefix>
section() {
  echo
  echo "### $1"
  echo
  echo "Source: $2"
  echo
  echo "| Name | Pinned | Newest upstream | Status |"
  echo "|---|---|---|---|"
  cat "$3.stale" "$3.other" 2>/dev/null || true
}

# 1. Charts that the migration-assistant chart installs. For HTTP repositories the chart name is
#    the key; an oci:// repository is the full chart reference.
rows="${work_dir}/values-charts"
while IFS=$'\t' read -r name version repository; do
  [[ -z "${version}" || -z "${repository}" ]] && continue
  if [[ "${repository}" == oci://* ]]; then
    row "${rows}.stale" "${rows}.other" "${name}" "${version}" list_tags "${repository#oci://}"
  else
    row "${rows}.stale" "${rows}.other" "${name}" "${version}" list_chart_versions "${name}" "${repository}"
  fi
done < <(yq '.charts | to_entries | .[] | [.key, (.value.version // ""), (.value.repository // "")] | @tsv' "${VALUES_FILE}")

# 2. Charts in the mirror manifest, as "name|version|repository". An oci:// repository is the
#    parent of the chart.
rows="${work_dir}/manifest-charts"
while IFS='|' read -r name version repository; do
  if [[ "${repository}" == oci://* ]]; then
    row "${rows}.stale" "${rows}.other" "${name}" "${version}" list_tags "${repository#oci://}/${name}"
  else
    row "${rows}.stale" "${rows}.other" "${name}" "${version}" list_chart_versions "${name}" "${repository}"
  fi
done < <(yq '.charts[]' "${MANIFEST_FILE}")

# 3. Images in the mirror manifest.
rows="${work_dir}/manifest-images"
while read -r image; do
  row "${rows}.stale" "${rows}.other" "\`${image%:*}\`" "${image##*:}" list_tags "${image%:*}"
done < <(yq '.images[]' "${MANIFEST_FILE}" | sort -u)

# 4. Base images that buildImages/build.gradle passes to the image builds, e.g.
#    ptc.rewrite("docker.io/apache/kafka:3.9.2") and
#    ptc.baseImageConfig("docker.io", "library", "amazoncorretto", "21-al2023-headless").
rows="${work_dir}/build-images"
while read -r image; do
  without_digest="${image%%@*}"
  row "${rows}.stale" "${rows}.other" "\`${without_digest%:*}\`" "${image#"${without_digest%:*}":}" \
    list_tags "${without_digest%:*}"
done < <(
  tr '\n' ' ' < "${BUILD_GRADLE}" |
    grep -oE 'ptc\.rewrite\([[:space:]]*"[^"]+"|ptc\.baseImageConfig\([^)]*\)' |
    sed -E 's/^ptc\.rewrite\([[:space:]]*"//; s/"$//' |
    sed -E 's/^ptc\.baseImageConfig\("([^"]+)", *"([^"]+)", *"([^"]+)", *"([^"]+)"\)$/\1\/\2\/\3:\4/' |
    sort -u
)

{
  echo "## Dependency staleness report"
  echo
  echo "${stale_count} of ${checked_count} versioned pins have a newer upstream version." \
    "Generated $(date -u '+%Y-%m-%d %H:%M UTC') from \`$(git rev-parse --short HEAD 2>/dev/null || echo unknown)\`."
  echo
  echo "These pins aren't in a format Dependabot reads. Charts in values.yaml and the mirror manifest" \
    "have to be updated together, along with the images each chart version uses."
  section "Charts installed by the migration-assistant chart" "\`${VALUES_FILE}\` (\`charts:\`)" "${work_dir}/values-charts"
  section "Charts in the ECR mirror manifest" "\`${MANIFEST_FILE}\`" "${work_dir}/manifest-charts"
  section "Images in the ECR mirror manifest" "\`${MANIFEST_FILE}\`" "${work_dir}/manifest-images"
  section "Base images for the image builds" "\`${BUILD_GRADLE}\`" "${work_dir}/build-images"
} > "${output}"
