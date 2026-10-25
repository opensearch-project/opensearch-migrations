#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "${TEST_ROOT}"' EXIT

WEB_DIR="${TEST_ROOT}/migrationConsole/web"
CONSOLE_LINK_DIR="${TEST_ROOT}/migrationConsole/lib/console_link"
BIN_DIR="${TEST_ROOT}/bin"
mkdir -p "${WEB_DIR}/scripts" "${CONSOLE_LINK_DIR}" "${BIN_DIR}"
cp "${SCRIPT_DIR}/generated-api.sh" "${WEB_DIR}/scripts/generated-api.sh"

cat >"${BIN_DIR}/pipenv" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >"${PIPENV_CALLS}"
mkdir -p .venv/bin
cp "${FAKE_PYTHON}" .venv/bin/python
chmod +x .venv/bin/python
EOF

cat >"${BIN_DIR}/fake-python" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "-c" ]]; then
    exit 0
fi

while [[ $# -gt 0 ]]; do
    if [[ "$1" == "--output" ]]; then
        touch "$2"
        exit 0
    fi
    shift
done

exit 1
EOF

chmod +x "${BIN_DIR}/pipenv" "${BIN_DIR}/fake-python"
export PIPENV_CALLS="${TEST_ROOT}/pipenv-calls"
export FAKE_PYTHON="${BIN_DIR}/fake-python"
PATH="${BIN_DIR}:/usr/bin:/bin" bash "${WEB_DIR}/scripts/generated-api.sh" openapi

[[ "$(<"${PIPENV_CALLS}")" == "sync" ]]
[[ -f "${WEB_DIR}/openapi.json" ]]
