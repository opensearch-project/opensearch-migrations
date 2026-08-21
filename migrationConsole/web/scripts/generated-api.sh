#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
WEB_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
CONSOLE_LINK_DIR="$(cd -- "${WEB_DIR}/../lib/console_link" && pwd)"
OPENAPI_PATH="${WEB_DIR}/openapi.json"
CLIENT_PATH="${WEB_DIR}/src/api/schema.generated.ts"

can_import_web_app() {
    (cd "${CONSOLE_LINK_DIR}" && "$@" -c "import console_link.workflow.web.app") \
        >/dev/null 2>&1
}

generate_openapi() {
    local -a python_command
    if [[ -n "${MANAGE_WEB_PYTHON:-}" ]]; then
        python_command=("${MANAGE_WEB_PYTHON}")
    elif [[ -x "${CONSOLE_LINK_DIR}/.venv/bin/python" ]]; then
        python_command=("${CONSOLE_LINK_DIR}/.venv/bin/python")
    elif [[ -n "${VIRTUAL_ENV:-}" && -x "${VIRTUAL_ENV}/bin/python" ]]; then
        python_command=("${VIRTUAL_ENV}/bin/python")
    elif command -v python3 >/dev/null && can_import_web_app python3; then
        python_command=(python3)
    elif command -v python >/dev/null && can_import_web_app python; then
        python_command=(python)
    elif command -v pipenv >/dev/null && can_import_web_app pipenv run python; then
        python_command=(pipenv run python)
    else
        echo "Unable to generate OpenAPI. Install console-link dependencies with:" >&2
        echo "  cd migrationConsole/lib/console_link && pipenv install --deploy" >&2
        echo "or set MANAGE_WEB_PYTHON to a configured Python interpreter." >&2
        return 1
    fi

    (
        cd "${CONSOLE_LINK_DIR}"
        "${python_command[@]}" -m console_link.workflow.web.openapi \
            --output "${OPENAPI_PATH}"
    )
}

needs_generation() {
    [[ ! -f "${OPENAPI_PATH}" || ! -f "${CLIENT_PATH}" ]] && return 0

    local input
    for input in \
        "${CONSOLE_LINK_DIR}/console_link/workflow/web/app.py" \
        "${CONSOLE_LINK_DIR}/console_link/workflow/web/contracts.py" \
        "${CONSOLE_LINK_DIR}/console_link/workflow/web/openapi.py" \
        "${CONSOLE_LINK_DIR}/Pipfile.lock" \
        "${WEB_DIR}/package.json" \
        "${BASH_SOURCE[0]}"; do
        [[ "${input}" -nt "${OPENAPI_PATH}" || "${input}" -nt "${CLIENT_PATH}" ]] && return 0
    done
    return 1
}

case "${1:-}" in
    openapi)
        generate_openapi
        ;;
    ensure)
        if needs_generation; then
            (cd "${WEB_DIR}" && npm run generate:api)
        fi
        ;;
    *)
        echo "Usage: $0 {openapi|ensure}" >&2
        exit 2
        ;;
esac
