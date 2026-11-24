#!/usr/bin/env bash
#
# Scan container images with Trivy and summarize the findings in Markdown.
#
# For each image this writes <name>.json (the full Trivy report), <name>.sarif and <name>.txt to
# the output directory, then writes summary.md there and appends it to $GITHUB_STEP_SUMMARY when
# that is set. The standard TRIVY_* environment variables (TRIVY_SEVERITY, TRIVY_IGNORE_UNFIXED,
# TRIVY_PKG_TYPES, ...) pick which findings are reported. They apply to every image.
#
# Usage: scan-images.sh <output-dir> <image-ref>...
#   e.g. scan-images.sh trivy-reports localhost:5001/migrations/capture_proxy:latest
#
# Writes findings=<count> to $GITHUB_OUTPUT when that is set. Exits non-zero only when an image
# couldn't be scanned. The caller decides whether findings should fail the build.

set -euo pipefail

# Rows listed per image in the summary. The full list is in the JSON and table reports.
readonly MAX_ROWS_PER_IMAGE="${MAX_ROWS_PER_IMAGE:-40}"

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <output-dir> <image-ref>..." >&2
  exit 2
fi
out_dir="$1"
shift
mkdir -p "${out_dir}"

total_findings=0
failed_images=()
rows=""
details=""

for ref in "$@"; do
  name="${ref##*/}"
  name="${name%%[:@]*}"
  json="${out_dir}/${name}.json"

  echo "::group::Scanning ${ref}"
  if ! trivy image --format json --output "${json}" "${ref}"; then
    echo "::endgroup::"
    echo "::error::Trivy couldn't scan ${ref}"
    failed_images+=("${name}")
    rows+="| \`${name}\` | scan failed | | | | | |"$'\n'
    continue
  fi
  trivy convert --format table --output "${out_dir}/${name}.txt" "${json}"
  trivy convert --format sarif --output "${out_dir}/${name}.sarif" "${json}"
  echo "::endgroup::"

  # One row per image: base OS, finding counts by package class and severity, unique CVE count.
  IFS=$'\t' read -r os os_count lang_count critical high unique < <(jq -r '
    [.Results[]? | .Class as $class | .Vulnerabilities[]? | {class: $class, id: .VulnerabilityID, severity: .Severity}] as $v
    | [ (if .Metadata.OS.Family then "\(.Metadata.OS.Family) \(.Metadata.OS.Name // "")" + (if .Metadata.OS.EOSL then " (end of life)" else "" end) else "not detected" end),
        ($v | map(select(.class == "os-pkgs")) | length),
        ($v | map(select(.class == "lang-pkgs")) | length),
        ($v | map(select(.severity == "CRITICAL")) | length),
        ($v | map(select(.severity == "HIGH")) | length),
        ($v | map(.id) | unique | length) ]
    | @tsv' "${json}")
  findings=$((os_count + lang_count))
  total_findings=$((total_findings + findings))
  rows+="| \`${name}\` | ${os} | ${os_count} | ${lang_count} | ${critical} | ${high} | ${unique} |"$'\n'

  if [[ "${findings}" -gt 0 ]]; then
    details+=$'\n'"<details><summary><code>${name}</code>: ${findings} findings</summary>"$'\n\n'
    details+="| Severity | Vulnerability | Package | Type | Installed | Fixed in |"$'\n'
    details+="|---|---|---|---|---|---|"$'\n'
    details+="$(jq -r --argjson max "${MAX_ROWS_PER_IMAGE}" '
      {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3} as $rank
      | [.Results[]? | .Type as $type | .Vulnerabilities[]?
          | {severity: .Severity, id: .VulnerabilityID, pkg: .PkgName, type: $type,
             installed: .InstalledVersion, fixed: (.FixedVersion // "")}]
      | unique_by([.id, .pkg, .installed])
      | sort_by(($rank[.severity] // 9), .pkg, .id) as $all
      | ($all[:$max][] | "| \(.severity) | \(.id) | \(.pkg) | \(.type) | \(.installed) | \(.fixed) |"),
        (if ($all | length) > $max then "\n\(($all | length) - $max) more in `\(input_filename | split("/") | last)` in the `trivy-image-reports` artifact." else empty end)
      ' "${json}")"$'\n\n'"</details>"$'\n'
  fi
done

summary="${out_dir}/summary.md"
{
  echo "## Image vulnerability scan"
  echo
  echo "Severities: \`${TRIVY_SEVERITY:-all}\`. Fixable only: \`${TRIVY_IGNORE_UNFIXED:-false}\`." \
    "Package types: \`${TRIVY_PKG_TYPES:-os,library}\`. Trivy $(trivy --version 2>/dev/null | sed -n 's/^Version: //p')."
  echo
  echo "| Image | Base OS | OS package findings | Language package findings | Critical | High | Unique vulnerabilities |"
  echo "|---|---|---|---|---|---|---|"
  printf '%s' "${rows}"
  echo
  echo "**${total_findings} findings in total.** Full reports are in the \`trivy-image-reports\` artifact."
  printf '%s' "${details}"
} > "${summary}"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  cat "${summary}" >> "${GITHUB_STEP_SUMMARY}"
else
  cat "${summary}"
fi
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "findings=${total_findings}" >> "${GITHUB_OUTPUT}"
fi

if [[ "${#failed_images[@]}" -gt 0 ]]; then
  echo "::error::Couldn't scan: ${failed_images[*]}"
  exit 1
fi
