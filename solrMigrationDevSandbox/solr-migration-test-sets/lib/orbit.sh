# lib/orbit.sh — shared helpers for run_standalone.sh and run_solrcloud.sh
#
# Source this file after defining SCRIPT_DIR, TOOLS_DIR, PY_VERSION,
# ORBIT_REPO, ORBIT_BRANCH, WORKLOADS_REPO, CHOWN_BACKUPS, BACKUP_BASE,
# S3_UPLOAD_BASE, S3_UPLOAD, AWS_ACCOUNT, and the usage() function.

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mWARN: %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Arg parsing (common options; caller handles remaining args via parse_args_extra)
# ---------------------------------------------------------------------------
parse_common_args() {
    for arg in "$@"; do
        case "$arg" in
            --versions=*)    VERSIONS="${arg#*=}" ;;
            --increments=*)  INCREMENTS="${arg#*=}"; INCREMENTS_SET=1 ;;
            --test-mode)     TEST_MODE=1 ;;
            --skip-install)  SKIP_INSTALL=1 ;;
            --tools-dir=*)   TOOLS_DIR="${arg#*=}" ;;
            --s3-upload)     S3_UPLOAD=1 ;;
            --aws-account=*) AWS_ACCOUNT="${arg#*=}" ;;
            --aws-cli=*)     AWS_CLI="${arg#*=}" ;;
            --chown-backups) CHOWN_BACKUPS=1 ;;
            -h|--help)       usage; exit 0 ;;
            *) echo "Unknown option: $arg" >&2; usage; exit 1 ;;
        esac
    done

    if [[ "$S3_UPLOAD" -eq 1 && -z "$AWS_ACCOUNT" ]]; then
        echo "ERROR: --s3-upload requires --aws-account=<number>" >&2
        exit 1
    fi
}

# ---------------------------------------------------------------------------
# Prereqs — create backup and s3_upload dirs, chown for Solr uid 8983
# ---------------------------------------------------------------------------
prereqs() {
    log "Prereqs: creating ${BACKUP_BASE} and ${S3_UPLOAD_BASE}"
    mkdir -p "${BACKUP_BASE}"
    mkdir -p "${S3_UPLOAD_BASE}"
    if [[ "$CHOWN_BACKUPS" -eq 1 ]]; then
        sudo chown -R 8983:8983 backups \
            || warn "could not chown backups to 8983:8983"
    else
        chown -R 8983:8983 backups 2>/dev/null || true
    fi
}

# ---------------------------------------------------------------------------
# Clone + install solr-orbit and solr-orbit-workloads
# ---------------------------------------------------------------------------
ORBIT_DIR=""
VENV_ACTIVE=0

# clone_or_update <url> <dest> [branch]
clone_or_update() {
    local url="$1" dest="$2" branch="${3:-}"
    if [[ -d "$dest/.git" ]]; then
        log "Updating $(basename "$dest") ($dest)"
        git -C "$dest" remote set-url origin "$url"
        git -C "$dest" fetch origin
        if [[ -n "$branch" ]]; then
            git -C "$dest" checkout "$branch"
            git -C "$dest" pull --ff-only origin "$branch" \
                || warn "git pull failed for $dest; using existing checkout"
        else
            git -C "$dest" pull --ff-only || warn "git pull failed for $dest; using existing checkout"
        fi
    else
        log "Cloning $url${branch:+ (branch $branch)} -> $dest"
        if [[ -n "$branch" ]]; then
            git clone -b "$branch" "$url" "$dest"
        else
            git clone "$url" "$dest"
        fi
    fi
}

install_orbit() {
    mkdir -p "$TOOLS_DIR"
    ORBIT_DIR="$TOOLS_DIR/solr-orbit"
    local workloads_dir="$TOOLS_DIR/solr-orbit-workloads"

    clone_or_update "$ORBIT_REPO" "$ORBIT_DIR" "$ORBIT_BRANCH"
    clone_or_update "$WORKLOADS_REPO" "$workloads_dir"

    command -v uv >/dev/null 2>&1 \
        || die "uv not found. Install it first: https://docs.astral.sh/uv/ (e.g. 'curl -LsSf https://astral.sh/uv/install.sh | sh')"
    log "Installing solr-orbit into a uv virtualenv (Python ${PY_VERSION})"
    uv python install "${PY_VERSION}"
    uv venv --python "${PY_VERSION}" --clear "$ORBIT_DIR/.venv"
    # shellcheck disable=SC1091
    source "$ORBIT_DIR/.venv/bin/activate"
    VENV_ACTIVE=1
    uv pip install -e "$ORBIT_DIR"

    local confdir="$HOME/.solr-orbit"
    local ini="$confdir/benchmark.ini"
    local template="$ORBIT_DIR/solrorbit/resources/benchmark.ini"
    log "Generating $ini from template (workloads -> local clone)"
    mkdir -p "$confdir"
    [[ -f "$template" ]] || die "solr-orbit config template not found at $template"
    if [[ -f "$ini" ]]; then
        cp "$ini" "$ini.bak.$$" && warn "backed up existing config to $ini.bak.$$"
    fi
    CONFDIR="$confdir" WL="$workloads_dir" python3 - "$template" "$ini" <<'PY'
import os, re, sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src).read().replace("${CONFIG_DIR}", os.environ["CONFDIR"])
text = re.sub(r'(?m)^default\.url\s*=.*$', "default.url = " + os.environ["WL"], text, count=1)
open(dst, "w").write(text)
PY

    solr-orbit --version || warn "solr-orbit --version failed"
}

ensure_orbit() {
    # Prefer the local venv over anything already on PATH (e.g. an older system
    # install via asdf/pipx), so --skip-install always uses the expected version.
    local candidate="$TOOLS_DIR/solr-orbit/.venv/bin/activate"
    if [[ -f "$candidate" ]]; then
        # shellcheck disable=SC1090
        source "$candidate"
        VENV_ACTIVE=1
    fi
    command -v solr-orbit >/dev/null 2>&1 \
        || die "solr-orbit not found. Run without --skip-install, or activate its venv first."
}
