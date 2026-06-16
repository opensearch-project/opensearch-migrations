#!/usr/bin/env bash
# Downloads all schema versions from GitHub Releases into schemas/ for local development.
# Mirrors the fetch step in .github/workflows/deploy-schema-viewer.yml.
# Requires: gh CLI (authenticated), curl, node
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCHEMAS_DIR="$SCRIPT_DIR/schemas"
mkdir -p "$SCHEMAS_DIR"

echo "Fetching schema release list..."
gh api repos/opensearch-project/opensearch-migrations/releases \
  --paginate \
  --jq '.[] | select(.assets | map(.name) | contains(["workflowMigration.schema.json"]))
            | .tag_name + " " + (.assets[] | select(.name == "workflowMigration.schema.json") | .browser_download_url)' \
  > /tmp/schema-releases.txt

declare -a versions
while IFS=' ' read -r tag url; do
  echo "  Downloading $tag..."
  curl -fsSL "$url" -o "$SCHEMAS_DIR/${tag}.json"
  versions+=("$tag")
done < /tmp/schema-releases.txt

if [ ${#versions[@]} -eq 0 ]; then
  echo "No schema versions found." >&2
  exit 1
fi

node - "$SCHEMAS_DIR/versions.json" "${versions[@]}" <<'EOF'
const fs = require('fs')
const [outFile, ...versions] = process.argv.slice(2)
const meta = { latest: versions[0], versions }
fs.writeFileSync(outFile, JSON.stringify(meta, null, 2) + '\n')
console.log(`✓ ${versions.length} schemas ready. Latest: ${meta.latest}`)
EOF
