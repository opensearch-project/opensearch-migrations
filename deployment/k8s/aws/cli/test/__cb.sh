#!/usr/bin/env bash
# Written in [Amber](https://amber-lang.com/)
# version: 0.6.0-alpha
[ "$EUID" -ne 0 ] && { { command -v sudo >/dev/null 2>&1 && __sudo=sudo; } || { command -v doas >/dev/null 2>&1 && __sudo=doas; }; }
if [ -n "$ZSH_VERSION" ]; then
    EXEC_SHELL="zsh"
    IFS='.' read -A EXEC_SHELL_VERSION <<< "$ZSH_VERSION"
elif [ -n "$KSH_VERSION" ]; then
    EXEC_SHELL="ksh"
    __exec_shell_version="${.sh.version##*/}"
    IFS='.' read -a EXEC_SHELL_VERSION <<< "${__exec_shell_version%% *}"
else
    EXEC_SHELL="bash"
    EXEC_SHELL_VERSION=("${BASH_VERSINFO[0]}" "${BASH_VERSINFO[1]}" "${BASH_VERSINFO[2]}")
fi
# assert(condition: Bool)
assert__0_v0() {
    local condition_1040="${1}"
    if [ "$(( ! condition_1040 ))" != 0 ]; then
        echo "Assertion failed"
        ret_assert0_v0=''
        return 1
    fi
}

# split(text: Text, delimiter: Text)
split__7_v0() {
    local text_963="${1}"
    local delimiter_964="${2}"
    local result_965=()
    # zsh uses -A for array, bash uses -a, ksh is VERY bad at splitting anything
    if [ "$([ "_${EXEC_SHELL}" != "_zsh" ]; echo $?)" != 0 ]; then
        IFS="${delimiter_964}" read -rd '' -A result_965 < <(printf %s "$text_963")
        __status=$?
    elif [ "$([ "_${EXEC_SHELL}" != "_ksh" ]; echo $?)" != 0 ]; then
        if [ "$([ "_${delimiter_964}" != "_
" ]; echo $?)" != 0 ]; then
            while read -r -d $'\n'; do result_965+=("$REPLY"); done < <(echo "$text_963")
            __status=$?
        else
            IFS="${delimiter_964}" read -rd '' -a result_965 < <(printf %s "$text_963")
            __status=$?
        fi
    elif [ "$([ "_${EXEC_SHELL}" != "_bash" ]; echo $?)" != 0 ]; then
        IFS="${delimiter_964}" read -rd '' -a result_965 < <(printf %s "$text_963")
        __status=$?
    fi
    ret_split7_v0=("${result_965[@]}")
    return 0
}

# split_lines(text: Text)
split_lines__8_v0() {
    local text_962="${1}"
    split__7_v0 "${text_962}" "
"
    ret_split_lines8_v0=("${ret_split7_v0[@]}")
    return 0
}

# slice(text: Text, index: Int, length: Int)
slice__27_v0() {
    local text_967="${1}"
    local index_968="${2}"
    local length_969="${3}"
    local result_970=""
    if [ "$(( length_969 == 0 ))" != 0 ]; then
        local __length_1="${text_967}"
        length_969="$(( ${#__length_1} - index_968 ))"
    fi
    if [ "$(( length_969 <= 0 ))" != 0 ]; then
        ret_slice27_v0="${result_970}"
        return 0
    fi
    result_970="${text_967: ${index_968}: ${length_969}}"
    __status=$?
    ret_slice27_v0="${result_970}"
    return 0
}

# dir_exists(path: Text)
dir_exists__41_v0() {
    local path_118="${1}"
    [ -d "${path_118}" ]
    __status=$?
    ret_dir_exists41_v0="$(( __status == 0 ))"
    return 0
}

# file_exists(path: Text)
file_exists__42_v0() {
    local path_131="${1}"
    [ -f "${path_131}" ]
    __status=$?
    ret_file_exists42_v0="$(( __status == 0 ))"
    return 0
}

# file_read(path: Text)
file_read__43_v0() {
    local path_136="${1}"
    local command_2
    command_2="$(< "${path_136}")"
    __status=$?
    if [ "${__status}" != 0 ]; then
        ret_file_read43_v0=''
        return "${__status}"
    fi
    ret_file_read43_v0="${command_2}"
    return 0
}

# file_write(path: Text, content: Text)
file_write__44_v0() {
    local path_127="${1}"
    local content_128="${2}"
    local command_3
    command_3="$(printf '%s
' "${content_128}" > "${path_127}")"
    __status=$?
    if [ "${__status}" != 0 ]; then
        ret_file_write44_v0=''
        return "${__status}"
    fi
    ret_file_write44_v0="${command_3}"
    return 0
}

# file_append(path: Text, content: Text)
file_append__45_v0() {
    local path_1217="${1}"
    local content_1218="${2}"
    local command_4
    command_4="$(printf '%s
' "${content_1218}" >> "${path_1217}")"
    __status=$?
    if [ "${__status}" != 0 ]; then
        ret_file_append45_v0=''
        return "${__status}"
    fi
    ret_file_append45_v0="${command_4}"
    return 0
}

# dir_create(path: Text)
dir_create__47_v0() {
    local path_117="${1}"
    dir_exists__41_v0 "${path_117}"
    local ret_dir_exists41_v0__87_12="${ret_dir_exists41_v0}"
    if [ "$(( ! ret_dir_exists41_v0__87_12 ))" != 0 ]; then
        mkdir -p "${path_117}"
        __status=$?
        if [ "${__status}" != 0 ]; then
            ret_dir_create47_v0=''
            return "${__status}"
        fi
    fi
}

# file_chmod(path: Text, mode: Text)
file_chmod__50_v0() {
    local path_129="${1}"
    local mode_130="${2}"
    file_exists__42_v0 "${path_129}"
    local ret_file_exists42_v0__153_8="${ret_file_exists42_v0}"
    if [ "${ret_file_exists42_v0__153_8}" != 0 ]; then
        chmod "${mode_130}" "${path_129}"
        __status=$?
        if [ "${__status}" != 0 ]; then
            ret_file_chmod50_v0=''
            return "${__status}"
        fi
        ret_file_chmod50_v0=''
        return 0
    fi
    echo "The file ${path_129} doesn't exist"'!'""
    ret_file_chmod50_v0=''
    return 1
}

# env_var_set(name: Text, val: Text)
env_var_set__123_v0() {
    local name_119="${1}"
    local val_120="${2}"
    export $name_119="$val_120" 2> /dev/null
    __status=$?
    if [ "${__status}" != 0 ]; then
        ret_env_var_set123_v0=''
        return "${__status}"
    fi
}

# env_var_get(name: Text)
env_var_get__124_v0() {
    local name_132="${1}"
    if [ "$([ "_${EXEC_SHELL}" != "_bash" ]; echo $?)" != 0 ]; then
        local command_5
        command_5="$(printf "%s
" "${!name_132}")"
        __status=$?
        if [ "${__status}" != 0 ]; then
            ret_env_var_get124_v0=''
            return "${__status}"
        fi
        ret_env_var_get124_v0="${command_5}"
        return 0
    elif [ "$([ "_${EXEC_SHELL}" != "_zsh" ]; echo $?)" != 0 ]; then
        local command_6
        command_6="$(printf "%s
" "${(P)name_132}")"
        __status=$?
        if [ "${__status}" != 0 ]; then
            ret_env_var_get124_v0=''
            return "${__status}"
        fi
        ret_env_var_get124_v0="${command_6}"
        return 0
    elif [ "$([ "_${EXEC_SHELL}" != "_ksh" ]; echo $?)" != 0 ]; then
        local command_7
        command_7="$(eval "echo \${$name_132}")"
        __status=$?
        if [ "${__status}" != 0 ]; then
            ret_env_var_get124_v0=''
            return "${__status}"
        fi
        ret_env_var_get124_v0="${command_7}"
        return 0
    fi
}

# test/support/world.ab — per-test "world" isolation for the migration-assistant
# CLI test suite.
# 
# Why a shared module: amber runs each `test` block as its own unit (module-level
# `let` resets per block), but env vars LEAK across blocks (they share the
# process environment). So every test must mint its OWN isolated world up front
# and never rely on another block's setup. This module is the single, reusable
# way to do that — import it instead of re-deriving the mktemp/env dance in
# every file (the old suite did exactly that, ~70 duplicated lines x 20 files).
# 
# A "world" is a unique temp directory that doubles as:
# * the CLI's per-stage workspace — MIGRATE_HOME / STAGE_DIR / STAGE all point
# into it, so state.env + logs land in an isolated sandbox; AND
# * the mock fleet's control plane — <world>/mock/ holds the recorded calls and
# the per-tool response fixtures (see support/mock.ab). The mocks find it by
# reading $MIGRATE_HOME at runtime, which the CLI passes to every child
# process it shells out to — a per-test-unique channel that needs no shared
# mutable env var (which would race under the parallel runner).
# 
# Usage:
# let w = fresh_world()              // unique sandbox; env pinned to it
# world_seed(w, "STAGE_NAME", "ma")  // pre-load a state.env value
# ... drive the CLI ...
# Monotonic counter so two worlds minted in the same second (same process) still
# differ. Module-level — resets per test block, which is fine: each block makes
# its own world(s).
__wseq_3=0
# Current world's stage name (for cmd_* that take --stage). Set by fresh_world.
# fresh_world — mint a unique sandbox and wire the CLI's stage env to it the way
# the CLI itself computes paths: STAGE_DIR = MIGRATE_HOME/STAGE. We set
# MIGRATE_HOME to a fresh temp root and STAGE to a unique name; the stage dir is
# the world. Returns the WORLD (stage dir) — where state.env + log/ + mock/ live.
# 
# IMPORTANT: callers that pass `--stage <name>` to a cmd_* must use the world's
# stage name (returned via world_stage()), or the CLI recomputes a different
# STAGE_DIR and won't see the seeded state. Prefer NOT passing --stage in e2e
# tests — the world already pins STAGE.
# fresh_world()
fresh_world__166_v0() {
    __wseq_3="$(( __wseq_3 + 1 ))"
    local command_8
    command_8="$(mktemp -d)"
    __status=$?
    local base_114="${command_8}"
    local stage_115="w${__wseq_3}"
    local dir_116="${base_114}/${stage_115}"
    dir_create__47_v0 "${dir_116}"
    __status=$?
    dir_create__47_v0 "${dir_116}/log"
    __status=$?
    dir_create__47_v0 "${dir_116}/mock"
    __status=$?
    # The CLI derives STAGE_DIR = MIGRATE_HOME/STAGE; children inherit MIGRATE_HOME.
    env_var_set__123_v0 "MIGRATE_HOME" "${base_114}"
    __status=$?
    env_var_set__123_v0 "STAGE" "${stage_115}"
    __status=$?
    env_var_set__123_v0 "STAGE_DIR" "${dir_116}"
    __status=$?
    ret_fresh_world166_v0="${dir_116}"
    return 0
}

# world_stage — the current world's stage name (for cmd_* that take --stage).
# world_seed — write a KEY=VALUE line into the world's state.env so the CLI's
# state_load() picks it up at entry. Values are written raw (no quoting) — fine
# for the simple identifiers the tests seed (stage names, regions, Y/N flags).
# world_noninteractive — flip the CLI into non-interactive mode (prompts return
# their defaults; no /dev/tty reads). Most e2e flows want this.
# test/support/mock.ab — a reusable mock fleet for the external tools the CLI
# shells out to (aws / kubectl / helm / crane / jq passthrough / docker / curl).
# 
# Design goals:
# * ONE place to install fakes — replaces the ~70-line install_stubs() copied
# into 20 test files.
# * RACE-SAFE under amber's parallel runner — the shims carry NO behavior in
# their body; they read everything at runtime from <MIGRATE_HOME>/mock/,
# which is unique per test world (support/world.ab). Two parallel blocks
# never share a control file.
# * SCENARIO-DRIVEN — a test declares the world it wants ("the helm release is
# stuck", "describe-stacks returns CREATE_COMPLETE") by dropping response
# fixtures; it does not hand-write a bash case per tool.
# * CALL-RECORDING — every invocation is appended to <mock>/calls/<tool>.log so
# a test can assert WHICH commands the CLI issued (e.g. "helm uninstall ran").
# 
# The shim contract (one generic bash program, parameterized by tool name):
# <mock>/calls/<tool>.log         every arg line the CLI passed, one per call
# <mock>/resp/<tool>/<key>.out    stdout to emit when the args match <key>
# <mock>/resp/<tool>/<key>.rc     exit code for that match (default 0)
# <mock>/resp/<tool>/default.out  fallback stdout when no key matches
# <mock>/resp/<tool>/default.rc   fallback exit code (default 0)
# <key> is the first arg token that has a matching fixture (longest-first), so a
# test sets responses per subcommand ("get", "status", "deploy", …).
# mock_root — the control-plane dir, resolved the SAME way the shims do: from
# $MIGRATE_HOME (which the world pins and every CLI child inherits). The `world`
# args the public fns take are accepted for readability but the root is always
# $MIGRATE_HOME/mock, so a test's queries and the shim's writes never disagree
# even when the world (stage dir) differs from MIGRATE_HOME.
# mock_root()
mock_root__178_v0() {
    env_var_get__124_v0 "MIGRATE_HOME"
    __status=$?
    if [ "${__status}" != 0 ]; then
        :
    fi
    local h_149="${ret_env_var_get124_v0}"
    ret_mock_root178_v0="${h_149}/mock"
    return 0
}

# ---- the generic shim body (brace-free Amber Text) -------------------------
# 
# A `{` inside an Amber Text literal is interpolation, so this bash is written
# brace-free: no `${...}` (use word-splitting + positional vars), no `case {`.
# The tool name is interpolated once as {tool}. Everything else is literal bash
# the shim runs at call time.
# shim_body(tool: Text)
shim_body__179_v0() {
    local tool_125="${1}"
    # Resolve the per-test control dir from MIGRATE_HOME (inherited from the CLI).
    # Record the call, then emit the best-matching response + rc.
    local b_126="#"'!'"/usr/bin/env bash
"
    b_126="${b_126}mock_root=\"\$MIGRATE_HOME/mock\"
"
    b_126="${b_126}tool=${tool_125}
"
    b_126="${b_126}mkdir -p \"\$mock_root/calls\" \"\$mock_root/resp/\$tool\"
"
    b_126="${b_126}printf '%s\\n' \"\$*\" >> \"\$mock_root/calls/\$tool.log\"
"
    # Find the first arg token that has a <key>.out fixture; fall back to default.
    b_126="${b_126}sel=\"\"
"
    b_126="${b_126}for tok in \"\$@\"; do
"
    b_126="${b_126}  if [ -f \"\$mock_root/resp/\$tool/\$tok.out\" ] || [ -f \"\$mock_root/resp/\$tool/\$tok.rc\" ]; then sel=\"\$tok\"; break; fi
"
    b_126="${b_126}done
"
    b_126="${b_126}[ -z \"\$sel\" ] && sel=default
"
    b_126="${b_126}outf=\"\$mock_root/resp/\$tool/\$sel.out\"
"
    b_126="${b_126}rcf=\"\$mock_root/resp/\$tool/\$sel.rc\"
"
    b_126="${b_126}[ -f \"\$outf\" ] && cat \"\$outf\"
"
    b_126="${b_126}rc=0
"
    b_126="${b_126}[ -f \"\$rcf\" ] && rc=\"\$(cat \"\$rcf\")\"
"
    b_126="${b_126}exit \"\$rc\"
"
    ret_shim_body179_v0="${b_126}"
    return 0
}

# ---- installation ----------------------------------------------------------
# __stub_dir — the single PATH-shadow dir for this process. Module-level; reset
# per test block, so each block installs into its own dir. Empty until installed.
__stub_dir_5=""
# mock_install <tools[]> — write a shim for each named tool into a PATH-shadow
# dir and prepend it to PATH. Idempotent within a block. The shims are
# content-stable (behavior lives in the world's mock/ dir), so installing the
# same set twice is harmless.
# mock_install(tools: [Text])
mock_install__180_v0() {
    local tools_122=("${!1}")
    if [ "$([ "_${__stub_dir_5}" != "_" ]; echo $?)" != 0 ]; then
        local command_9
        command_9="$(mktemp -d)"
        __status=$?
        __stub_dir_5="${command_9}"
    fi
    for tool_123 in "${tools_122[@]}"; do
        local p_124="${__stub_dir_5}/${tool_123}"
        shim_body__179_v0 "${tool_123}"
        local ret_shim_body179_v0__84_29="${ret_shim_body179_v0}"
        file_write__44_v0 "${p_124}" "${ret_shim_body179_v0__84_29}"
        __status=$?
        file_chmod__50_v0 "${p_124}" "755"
        __status=$?
    done
    env_var_get__124_v0 "PATH"
    __status=$?
    if [ "${__status}" != 0 ]; then
        :
    fi
    local old_133="${ret_env_var_get124_v0}"
    env_var_set__123_v0 "PATH" "${__stub_dir_5}:${old_133}"
    __status=$?
}

# mock_install_default — the common fleet (aws/kubectl/helm). Most e2e tests
# want at least these three.
# mock_install_default()
mock_install_default__181_v0() {
    local array_12=("aws" "kubectl" "helm")
    mock_install__180_v0 array_12[@]
}

# ---- programming responses --------------------------------------------------
# mock_respond <world> <tool> <key> <stdout> — when <tool> is called with an arg
# token == <key>, emit <stdout> (exit 0). <key>="default" sets the fallback.
# mock_respond(world: Text, tool: Text, key: Text, out: Text)
mock_respond__182_v0() {
    local world_145="${1}"
    local tool_146="${2}"
    local key_147="${3}"
    local out_148="${4}"
    mock_root__178_v0 
    local ret_mock_root178_v0__102_24="${ret_mock_root178_v0}"
    dir_create__47_v0 "${ret_mock_root178_v0__102_24}/resp/${tool_146}"
    __status=$?
    mock_root__178_v0 
    local ret_mock_root178_v0__103_24="${ret_mock_root178_v0}"
    file_write__44_v0 "${ret_mock_root178_v0__103_24}/resp/${tool_146}/${key_147}.out" "${out_148}"
    __status=$?
}

# mock_respond_rc <world> <tool> <key> <rc> — set the exit code for a match.
# mock_respond_rc(world: Text, tool: Text, key: Text, rc: Text)
mock_respond_rc__183_v0() {
    local world_1245="${1}"
    local tool_1246="${2}"
    local key_1247="${3}"
    local rc_1248="${4}"
    mock_root__178_v0 
    local ret_mock_root178_v0__108_24="${ret_mock_root178_v0}"
    dir_create__47_v0 "${ret_mock_root178_v0__108_24}/resp/${tool_1246}"
    __status=$?
    mock_root__178_v0 
    local ret_mock_root178_v0__109_24="${ret_mock_root178_v0}"
    file_write__44_v0 "${ret_mock_root178_v0__109_24}/resp/${tool_1246}/${key_1247}.rc" "${rc_1248}"
    __status=$?
}

# mock_fail <world> <tool> <key> <rc> <stderr-ish stdout> — convenience: a
# non-zero exit with an explanatory line (written to stdout, which is where the
# CLI's `failed { }` capture + log tee read it).
# ---- inspecting calls -------------------------------------------------------
# mock_calls <world> <tool> — the recorded call log for <tool> (every arg line,
# newline-joined), or "" if the tool was never called.
# mock_called <world> <tool> <needle> — Bool: did any <tool> call contain
# <needle> in its args? The primary e2e assertion ("the CLI ran helm uninstall").
# mock_called(world: Text, tool: Text, needle: Text)
mock_called__186_v0() {
    local world_1035="${1}"
    local tool_1036="${2}"
    local needle_1037="${3}"
    mock_root__178_v0 
    local ret_mock_root178_v0__134_15="${ret_mock_root178_v0}"
    local f_1038="${ret_mock_root178_v0__134_15}/calls/${tool_1036}.log"
    file_exists__42_v0 "${f_1038}"
    local ret_file_exists42_v0__135_12="${ret_file_exists42_v0}"
    if [ "$(( ! ret_file_exists42_v0__135_12 ))" != 0 ]; then
        ret_mock_called186_v0=0
        return 0
    fi
    # No `silent` — it compiles to a `2>` redirect that collides with status();
    # grep -q is already output-free. trust because no-match (rc 1) is expected.
    grep -q -- "${needle_1037}" "${f_1038}"
    __status=$?
    ret_mock_called186_v0="$(( __status == 0 ))"
    return 0
}

# mock_call_count <world> <tool> — number of times <tool> was invoked.
# std.ab — pure utilities. Port of bash lib/std.sh.
# 
# The bash file existed to replace ad-hoc patterns scattered across the CLI and
# to stay bash-3.2-safe (no declare -A, no mapfile, no ${var,,}). In Amber many
# of these are ONE-LINE wrappers over std/text + std/array — the value of the
# module is now mostly a stable, tested, CLI-flavored API surface. Where Amber's
# stdlib already nails it (trim/split/join/starts_with/ends_with/contains) we
# re-export thin wrappers so call sites read the same as the bash ones; where it
# doesn't (regex capture groups, path join, flag parse, retry) we implement.
# 
# Everything is pure (returns a value) and unit-tested in test_std.ab.
# NB: NO `import { x as y }` aliasing — it's broken in 0.6 (aliased fns return
# empty). And you can't redeclare an imported name in the same scope. So this
# module imports ONLY the std helpers whose names it does NOT itself export
# (split, join, match_regex, …) and re-implements trim/starts_with/ends_with/
# contains/array_contains natively, so the public names are ours to own and
# consumers get one consistent API + the bash std.sh arg order.
# ---- tiny shared helper ----------------------------------------------------
# index_of — first index of a single-char needle, or -1.
# ---- strings ---------------------------------------------------------------
# is_ws — single-char whitespace test (space, tab, newline, CR).
# is_ws(c: Text)
is_ws__210_v0() {
    local c_1009="${1}"
    ret_is_ws210_v0="$(( $(( $(( $([ "_${c_1009}" != "_ " ]; echo $?) || $([ "_${c_1009}" != "_	" ]; echo $?) )) || $([ "_${c_1009}" != "_
" ]; echo $?) )) || $([ "_${c_1009}" != "_" ]; echo $?) ))"
    return 0
}

# trim — strip leading + trailing whitespace (native; no std dep to own the name).
# trim(s: Text)
trim__211_v0() {
    local s_1006="${1}"
    local __length_13="${s_1006}"
    local n_1007="${#__length_13}"
    if [ "$(( n_1007 == 0 ))" != 0 ]; then
        ret_trim211_v0=""
        return 0
    fi
    local a_1008=0
    while [ "$(( a_1008 < n_1007 ))" != 0 ]; do
        slice__27_v0 "${s_1006}" "${a_1008}" 1
        local ret_slice27_v0__48_22="${ret_slice27_v0}"
        is_ws__210_v0 "${ret_slice27_v0__48_22}"
        local ret_is_ws210_v0__48_16="${ret_is_ws210_v0}"
        if [ "$(( ! ret_is_ws210_v0__48_16 ))" != 0 ]; then
            break
        fi
        a_1008="$(( a_1008 + 1 ))"
    done
    if [ "$(( a_1008 == n_1007 ))" != 0 ]; then
        ret_trim211_v0=""
        return 0
    fi
    local b_1010="$(( n_1007 - 1 ))"
    while [ "$(( b_1010 > a_1008 ))" != 0 ]; do
        slice__27_v0 "${s_1006}" "${b_1010}" 1
        local ret_slice27_v0__54_22="${ret_slice27_v0}"
        is_ws__210_v0 "${ret_slice27_v0__54_22}"
        local ret_is_ws210_v0__54_16="${ret_is_ws210_v0}"
        if [ "$(( ! ret_is_ws210_v0__54_16 ))" != 0 ]; then
            break
        fi
        b_1010="$(( b_1010 - 1 ))"
    done
    slice__27_v0 "${s_1006}" "${a_1008}" "$(( $(( b_1010 - a_1008 )) + 1 ))"
    ret_trim211_v0="${ret_slice27_v0}"
    return 0
}

# trim_quotes — strip ONE matched leading+trailing ' or ". Asymmetric input is
# left untouched (don't mangle "only-leading into a half-quoted string).
# trim_quotes(s: Text)
trim_quotes__212_v0() {
    local s_977="${1}"
    local __length_14="${s_977}"
    local n_978="${#__length_14}"
    if [ "$(( n_978 < 2 ))" != 0 ]; then
        ret_trim_quotes212_v0="${s_977}"
        return 0
    fi
    slice__27_v0 "${s_977}" 0 1
    local first_979="${ret_slice27_v0}"
    slice__27_v0 "${s_977}" "$(( n_978 - 1 ))" 1
    local last_980="${ret_slice27_v0}"
    if [ "$(( $([ "_${first_979}" != "_\"" ]; echo $?) && $([ "_${last_980}" != "_\"" ]; echo $?) ))" != 0 ]; then
        slice__27_v0 "${s_977}" 1 "$(( n_978 - 2 ))"
        ret_trim_quotes212_v0="${ret_slice27_v0}"
        return 0
    fi
    if [ "$(( $([ "_${first_979}" != "_'" ]; echo $?) && $([ "_${last_980}" != "_'" ]; echo $?) ))" != 0 ]; then
        slice__27_v0 "${s_977}" 1 "$(( n_978 - 2 ))"
        ret_trim_quotes212_v0="${ret_slice27_v0}"
        return 0
    fi
    ret_trim_quotes212_v0="${s_977}"
    return 0
}

# starts_with / ends_with / contains — Bool predicates (arg order matches bash
# std.sh: (prefix|suffix|needle, string)). Native impls — own the names.
# split_csv — split on commas into a [Text]. (The bash version wrote into a
# named array via eval; Amber returns the array directly — cleaner.)
# join_by — join args (as an array) with a separator. Multi-char seps work
# (bash's ${arr[*]} only takes IFS's first byte; std join handles full seps).
# regex_capture <string> <pattern> [group] — return capture group <group>
# (default 1) of the FIRST match, or "" with success=false semantics. Amber's
# match_regex is a Bool; to extract a group we shell to sed -nE once. Pattern is
# caller-supplied (constructor input, never user data) per the bash contract.
# regex_capture(s: Text, pat: Text, group: Int)
regex_capture__218_v0() {
    local s_1011="${1}"
    local pat_1012="${2}"
    local group_1013="${3}"
    # Build the sed backreference "\N" in Amber FIRST, then interpolate it as a
    # single token. Writing "\\{group}" inline mis-lowers: Amber turns {group}
    # into a bash var ref and the \\ leaves a literal backslash, so sed sees
    # "\${group_9}" verbatim. A pre-built Text sidesteps that. The replacement
    # prints capture group <group> of the first whole-line match (the =~-anywhere
    # shape the bash version used).
    local backref_1014="\\${group_1013}"
    local command_15
    command_15="$(printf '%s' "${s_1011}" | sed -nE "s/.*${pat_1012}.*/${backref_1014}/p" 2>/dev/null | head -1)"
    __status=$?
    local out_1015="${command_15}"
    ret_regex_capture218_v0="${out_1015}"
    return 0
}

# regex_match — Bool: does <pattern> match anywhere in <string>?
# count_lines_var — number of lines in a Text. A trailing-newline-less last
# fragment still counts as a line; empty string is 0.
# ---- collections -----------------------------------------------------------
# array_contains — Bool membership (arg order matches bash: needle, array).
# dedupe — keep first occurrence of each non-empty line in a Text, in order.
# ---- path ------------------------------------------------------------------
# path_join — join components with '/', collapsing duplicate slashes.
# ---- environment -----------------------------------------------------------
# is_macos / is_linux — OS branch via std/env uname_kernel_name() == `uname -s`
# (Darwin / Linux). NB: uname_os() is `uname -o` = "GNU/Linux" on Linux, so
# is_linux() would be false there — use the kernel name, not the OS name.
# optional_cmd — is <name> on PATH? Never fails.
# require_var — Bool: is the named env var set AND non-empty? (The bash version
# die()d; in Amber the caller decides — keeps std.ab side-effect-free.)
# ---- flag parsing ----------------------------------------------------------
# 
# parse_flag_value <flag> <argv[]> — return the value of `--flag VALUE` or
# `--flag=VALUE`, or "" if absent. (The bash parse_flag_into also returned the
# REST of argv via a ref array; callers that need the rest use parse_flag_rest.)
# parse_flag_rest <flag> <argv[]> — argv with the first `--flag VALUE` /
# `--flag=VALUE` removed.
# ---- retry -----------------------------------------------------------------
# 
# retry_cmd <attempts> <delay_secs> <command-as-Text> — run a shell command up
# to <attempts> times with <delay> between tries. Returns the last exit code.
# (Amber can't take a function pointer, so the command is a Text run via the
# shell — matching how the CLI's retried operations are external commands.)
# state.ab — per-stage state I/O.
# 
# Port of bash lib/state.sh. The public API is preserved verbatim so the rest
# of the CLI can call it unchanged:
# state_load / state_set / state_get / state_has / state_unset /
# state_save / state_archive / state_resumable_step
# 
# Two artifacts are written together for two readers (same as bash):
# state.env   ← KEY="VALUE" lines, sourceable from bash, human-greppable
# state.json  ← canonical for jq, the agent, and the tests
# 
# Storage model. Bash 3.2 has no associative arrays, so the bash version used
# the parallel-array pair STATE_KEYS[]/STATE_VALS[]. Amber forbids nested
# arrays for the same bash reason, so we keep the EXACT same shape: two
# module-level parallel arrays. Module-level `let` arrays compile to bash
# globals that persist across calls, which is what makes load → set → save →
# load round-trip in one process. They are declared with a TYPED SEED ([""]) to
# anchor the element type — a bare module-level `let xs = []` leaves the type
# Generic across function boundaries and fails when imported. state_load resets
# them to `[]`, rows grow via `+= [v]`, and `len STATE_KEYS` IS the entry count
# (no separate counter, no index<count juggling).
# 
# flock. The bash version opened fd 9 on .state.lock and flock-ed it to guard
# against two concurrent migration-assistant runs writing the same stage. The
# migration-assistant is a single-process interactive CLI (one operator, one
# stage at a time); Amber has no fd-op / `exec 9>` primitive and modeling it
# would need a raw escape for zero real benefit here. We SKIP the lock and note
# it: see state_save. (If concurrent runs ever become real, reintroduce via a
# `trust` flock escape there.)
# ---- in-memory store (parallel arrays, mirrors STATE_KEYS/VALS) ------------
# 
# Typed seed ([""]) anchors the element type; state_load resets to []. len
# STATE_KEYS is the logical entry count.
__STATE_KEYS_6=("")
__STATE_VALS_7=("")
# ---- stage directory resolution --------------------------------------------
# 
# In bash, _common.sh derived STAGE_DIR="$MIGRATE_HOME/$STAGE" at source time
# and stage_dir_init mkdir-ed the subtree. Here we resolve it lazily from the
# environment on every state op (cheap, and avoids load-order coupling). The
# fallbacks match _common.sh exactly: MIGRATE_HOME defaults to
# "$PWD/migration-assistant-workspace", STAGE defaults to "default". An
# explicit STAGE_DIR env var (set by diag.sh / agent.sh / the bats harness)
# wins, as it does in bash.
# stage_dir()
stage_dir__246_v0() {
    env_var_get__124_v0 "STAGE_DIR"
    __status=$?
    if [ "${__status}" != 0 ]; then
        :
    fi
    local explicit_955="${ret_env_var_get124_v0}"
    if [ "$([ "_${explicit_955}" == "_" ]; echo $?)" != 0 ]; then
        ret_stage_dir246_v0="${explicit_955}"
        return 0
    fi
    env_var_get__124_v0 "MIGRATE_HOME"
    __status=$?
    if [ "${__status}" != 0 ]; then
        :
    fi
    local home_956="${ret_env_var_get124_v0}"
    if [ "$([ "_${home_956}" != "_" ]; echo $?)" != 0 ]; then
        local command_18
        command_18="$(pwd)"
        __status=$?
        local pwd_957="${command_18}"
        home_956="${pwd_957}/migration-assistant-workspace"
    fi
    env_var_get__124_v0 "STAGE"
    __status=$?
    if [ "${__status}" != 0 ]; then
        :
    fi
    local stage_958="${ret_env_var_get124_v0}"
    if [ "$([ "_${stage_958}" != "_" ]; echo $?)" != 0 ]; then
        stage_958="default"
    fi
    ret_stage_dir246_v0="${home_956}/${stage_958}"
    return 0
}

# stage_dir_init — create the stage subtree if missing. Idempotent. Mirrors
# _common.sh stage_dir_init (same subdirs) so state_save/state_archive have
# somewhere to write. dir_create is failable; we don't want a missing-parent
# race to abort, so each create is tolerated (the subsequent file_write will
# surface a real problem).
# stage_dir_init()
stage_dir_init__247_v0() {
    stage_dir__246_v0 
    local d_959="${ret_stage_dir246_v0}"
    dir_create__47_v0 "${d_959}"
    __status=$?
    dir_create__47_v0 "${d_959}/log"
    __status=$?
    dir_create__47_v0 "${d_959}/artifacts"
    __status=$?
    dir_create__47_v0 "${d_959}/artifacts/.cache"
    __status=$?
    dir_create__47_v0 "${d_959}/skills"
    __status=$?
    dir_create__47_v0 "${d_959}/plan"
    __status=$?
    dir_create__47_v0 "${d_959}/history"
    __status=$?
    dir_create__47_v0 "${d_959}/archive"
    __status=$?
}

# ---- index helper (private; defined ABOVE its callers for the tree-shaker) --
# 
# _state_index <key> → index in STATE_KEYS or -1. Bash returned "" / a number on
# stdout; -1 is the Amber-idiomatic "not found" and every caller branches on it.
# _state_index(target: Text)
_state_index__248_v0() {
    local target_989="${1}"
    i_991=0;
    for k_990 in "${__STATE_KEYS_6[@]}"; do
        if [ "$([ "_${k_990}" != "_${target_989}" ]; echo $?)" != 0 ]; then
            ret__state_index248_v0="${i_991}"
            return 0
        fi
        (( i_991++ )) || true
    done
    ret__state_index248_v0=-1
    return 0
}

# ---- load -------------------------------------------------------------------
# 
# NB: index_of_eq + unescape_quotes are defined ABOVE state_load. When this
# module is imported, Amber tree-shakes per symbol and keeps only the
# transitive deps that textually precede the entry point — a callee defined
# after its caller is silently dropped, and state_load would then fail with
# "Function 'index_of_eq' does not exist".
# index_of_eq — first index of '=' in s, or -1. (Inlined rather than reusing
# std.index_of so this module owns its one-char scan and stays import-light.)
# index_of_eq(s: Text)
index_of_eq__249_v0() {
    local s_971="${1}"
    local __length_21="${s_971}"
    local total_972="${#__length_21}"
    local __range_start_973=0
    local __range_end_973="${total_972}"
    local __dir_973=$(( ${__range_start_973} <= ${__range_end_973} ? 1 : -1 ))
    for (( i_973=${__range_start_973}; i_973 * ${__dir_973} < ${__range_end_973} * ${__dir_973}; i_973+=${__dir_973} )); do
        slice__27_v0 "${s_971}" "${i_973}" 1
        local ret_slice27_v0__107_12="${ret_slice27_v0}"
        if [ "$([ "_${ret_slice27_v0__107_12}" != "_=" ]; echo $?)" != 0 ]; then
            ret_index_of_eq249_v0="${i_973}"
            return 0
        fi
done
    ret_index_of_eq249_v0=-1
    return 0
}

# unescape_quotes — turn every \" back into ". Inverse of state_save's escape.
# Amber has no in-place ${v//\\\"/\"}; we scan and rebuild (a literal \" is two
# codepoints: backslash then quote).
# unescape_quotes(s: Text)
unescape_quotes__250_v0() {
    local s_981="${1}"
    local __length_22="${s_981}"
    local n_982="${#__length_22}"
    local out_983=""
    local i_984=0
    while [ "$(( i_984 < n_982 ))" != 0 ]; do
        slice__27_v0 "${s_981}" "${i_984}" 1
        local c_985="${ret_slice27_v0}"
        slice__27_v0 "${s_981}" "$(( i_984 + 1 ))" 1
        local ret_slice27_v0__121_40="${ret_slice27_v0}"
        if [ "$(( $(( $([ "_${c_985}" != "_\\" ]; echo $?) && $(( $(( i_984 + 1 )) < n_982 )) )) && $([ "_${ret_slice27_v0__121_40}" != "_\"" ]; echo $?) ))" != 0 ]; then
            out_983="${out_983}\""
            i_984="$(( i_984 + 2 ))"
        else
            out_983="${out_983}${c_985}"
            i_984="$(( i_984 + 1 ))"
        fi
    done
    ret_unescape_quotes250_v0="${out_983}"
    return 0
}

# state_load — reset the in-memory store and reload it from state.env. Lines
# are KEY=VALUE; comment (#…) and blank lines are skipped, exactly as the bash
# `[[ -z "$line" || "$line" == \#* ]] && continue` did. The bash value
# pipeline was: v=${line#*=}; v=$(trim_quotes "$v"); v=${v//\\\"/\"}. We
# reproduce all three steps — split on the FIRST '=', strip one matched pair of
# wrapping quotes, then unescape any \" back to ".
# state_load()
state_load__251_v0() {
    stage_dir_init__247_v0 
    __STATE_KEYS_6=()
    __STATE_VALS_7=()
    stage_dir__246_v0 
    local ret_stage_dir246_v0__142_22="${ret_stage_dir246_v0}"
    local env_file_960="${ret_stage_dir246_v0__142_22}/state.env"
    file_exists__42_v0 "${env_file_960}"
    local ret_file_exists42_v0__143_12="${ret_file_exists42_v0}"
    if [ "$(( ! ret_file_exists42_v0__143_12 ))" != 0 ]; then
        ret_state_load251_v0=''
        return 0
    fi
    file_read__43_v0 "${env_file_960}"
    __status=$?
    if [ "${__status}" != 0 ]; then
        :
    fi
    local body_961="${ret_file_read43_v0}"
    split_lines__8_v0 "${body_961}"
    local ret_split_lines8_v0__145_17=("${ret_split_lines8_v0[@]}")
    for line_966 in "${ret_split_lines8_v0__145_17[@]}"; do
        if [ "$([ "_${line_966}" != "_" ]; echo $?)" != 0 ]; then
            continue
        fi
        # comment line — bash matched "$line" == \#* (leading '#').
        slice__27_v0 "${line_966}" 0 1
        local ret_slice27_v0__148_12="${ret_slice27_v0}"
        if [ "$([ "_${ret_slice27_v0__148_12}" != "_#" ]; echo $?)" != 0 ]; then
            continue
        fi
        # k=${line%%=*}; v=${line#*=}  — split on the FIRST '='.
        index_of_eq__249_v0 "${line_966}"
        local eq_974="${ret_index_of_eq249_v0}"
        if [ "$(( eq_974 < 0 ))" != 0 ]; then
            continue
        fi
        slice__27_v0 "${line_966}" 0 "${eq_974}"
        local k_975="${ret_slice27_v0}"
        local __length_27="${line_966}"
        slice__27_v0 "${line_966}" "$(( eq_974 + 1 ))" "$(( $(( ${#__length_27} - eq_974 )) - 1 ))"
        local raw_976="${ret_slice27_v0}"
        # strip ONE wrapping pair of quotes (how state_save serializes), then
        # unescape \" → " (the escaping state_save applied to inner quotes).
        trim_quotes__212_v0 "${raw_976}"
        local ret_trim_quotes212_v0__156_33="${ret_trim_quotes212_v0}"
        unescape_quotes__250_v0 "${ret_trim_quotes212_v0__156_33}"
        local v_986="${ret_unescape_quotes250_v0}"
        __STATE_KEYS_6+=("${k_975}")
        __STATE_VALS_7+=("${v_986}")
    done
}

# ---- accessors --------------------------------------------------------------
# state_set <key> <value> — upsert into the in-memory store. Updating an
# existing key MUST NOT grow the arrays (mirrors STATE_VALS[idx]="$v").
# state_set(k: Text, v: Text)
state_set__252_v0() {
    local k_994="${1}"
    local v_995="${2}"
    _state_index__248_v0 "${k_994}"
    local idx_996="${ret__state_index248_v0}"
    if [ "$(( idx_996 >= 0 ))" != 0 ]; then
        __STATE_VALS_7["${idx_996}"]="${v_995}"
        ret_state_set252_v0=''
        return 0
    fi
    # New key — append in lockstep across the parallel arrays.
    __STATE_KEYS_6+=("${k_994}")
    __STATE_VALS_7+=("${v_995}")
}

# state_get <key> [default] — the stored value, or <default> when absent. Bash
# printed to stdout (the CLI return-value channel); in Amber callers use the
# return value directly. The two-arg bash signature with an optional default
# ("${2:-}") becomes an explicit `def` param — callers pass "" for no default.
# state_get(k: Text, def: Text)
state_get__253_v0() {
    local k_987="${1}"
    local def_988="${2}"
    _state_index__248_v0 "${k_987}"
    local idx_992="${ret__state_index248_v0}"
    if [ "$(( idx_992 >= 0 ))" != 0 ]; then
        ret_state_get253_v0="${__STATE_VALS_7[${idx_992}]?"Index out of bounds (at test/../src/./state.ab:183:37)"}"
        return 0
    fi
    ret_state_get253_v0="${def_988}"
    return 0
}

# state_has <key> — Bool: is the key present? (Bash returned exit-status via
# `[[ -n "$idx" ]]`; Amber returns a Bool the caller branches on.)
# state_unset <key> — remove a key from the in-memory store (no-op if absent).
# Not in the bash file as a named function, but the parallel-array store needs
# a compaction primitive and the port plan calls it out. Rebuild both arrays
# keeping every row except the target — surviving keys stay in order with their
# values, and len STATE_KEYS shrinks by one.
# state_count — logical entry count. Test seam (no bash equivalent; the bash
# tests inspected ${#STATE_KEYS[@]} directly).
# ---- persistence ------------------------------------------------------------
# esc_for_env — escape inner " as \" for the KEY="VALUE" env serialization.
# Mirrors bash v=${v//\"/\\\"}.
# esc_for_json — escape \ then " for the JSON string value. Mirrors the bash
# no-jq fallback: v=${v//\\/\\\\}; v=${v//\"/\\\"}. Order matters — backslashes
# first, so the backslashes we ADD for quotes are not themselves doubled.
# state_save — write state.env + state.json from the in-memory store.
# 
# flock: the bash version `exec 9>"$lock"; flock -x 9` to serialize concurrent
# runs. Skipped here on purpose — single-process CLI, and Amber has no fd-op
# primitive (see the module header). Reintroduce with a `trust` flock escape
# here if concurrent stage runs ever become a thing.
# 
# Atomicity: bash wrote to "$file.tmp.$$" then mv -f'd into place. We replicate
# the tmp-then-rename so a reader never sees a half-written file. $$ (the PID)
# is not exposed by Amber, so we tag the tmp with date_now()-derived
# uniqueness via a raw `printf` of the epoch is overkill — a fixed ".tmp"
# suffix is safe because the lock-free single writer can't collide with itself.
# 
# jq is NOT required: we always emit the JSON ourselves (the bash no-jq
# fallback path) so the artifact is identical regardless of host tooling, and
# the test that greps state.json doesn't depend on jq being installed.
# ---- archive ----------------------------------------------------------------
# state_archive — move state.env + state.json into archive/<ts>/ and clear the
# in-memory store. <ts> is a sortable timestamp (date '+%Y%m%dT%H%M%S' in bash).
# 
# The timestamp uses a raw `date` escape: Amber's std/date has date_now() (epoch
# seconds) but no strftime-style formatter, so there is no pure-Amber way to
# build the %Y%m%dT%H%M%S string. This is the one place a `trust $date$` escape
# is unavoidable. mkdir + the conditional moves likewise shell out (dir_create
# is fine, but the "move only if present" + the wildcard-free two-file loop read
# cleanest as direct mv calls guarded by file_exists).
# ---- resume -----------------------------------------------------------------
# state_resumable_step — the value of `last_step`, or "" if never set. The
# resume logic reads this to decide where to pick a migration back up.
# core.ab — terminal foundation: ESC minting, interactivity detection, stderr emit.
# 
# This is the bedrock the rest of the TUI builds on. Two hard rules, inherited
# straight from the bash term.sh contract:
# 
# 1. EVERYTHING visual goes to STDERR. Stdout is reserved for return values.
# Emitters here only ever write fd 2.
# 2. No-op gracefully when stderr is not a TTY (CI logs, pipes). Detection is
# `test -t 2` plus `TERM != dumb` — decided once, cached.
# 
# Why a real ESC byte is minted at runtime rather than embedded: Amber's `\x1b`
# hex escape only lowers to a real ESC through the `printf` builtin, while inside
# a raw `$...$` command the bytes stay literal. `printf '\x1b'` via the shell is
# locale- and target-shell-independent, so we mint once and interpolate the
# resulting Text everywhere. (Verified against amber 0.6.0-alpha.)
# ---- interactivity (decided once, cached) ---------------------------------
# -1 = undecided, 0 = not a tty, 1 = interactive. Module-level mutable state
# persists across calls in the compiled output, so this memoizes cleanly.
__interactive_8=-1
# term_detect — force a (re)detection. Honors MIGRATE_FORCE_TTY=1 so tests can
# pin interactive mode without a real terminal, mirroring the bats setup that
# sets __TERM_INTERACTIVE=1 by hand.
# term_detect()
term_detect__275_v0() {
    env_var_get__124_v0 "MIGRATE_FORCE_TTY"
    __status=$?
    if [ "${__status}" != 0 ]; then
        :
    fi
    local forced_998="${ret_env_var_get124_v0}"
    if [ "$([ "_${forced_998}" != "_1" ]; echo $?)" != 0 ]; then
        __interactive_8=1
        ret_term_detect275_v0=1
        return 0
    fi
    if [ "$([ "_${forced_998}" != "_0" ]; echo $?)" != 0 ]; then
        __interactive_8=0
        ret_term_detect275_v0=0
        return 0
    fi
    # `test -t 2` succeeds only when fd 2 is a real terminal. TERM=dumb (CI
    # runners, some IDE consoles) also disqualifies.
    env_var_get__124_v0 "TERM"
    __status=$?
    if [ "${__status}" != 0 ]; then
        :
    fi
    local term_999="${ret_env_var_get124_v0}"
    if [ "$([ "_${term_999}" != "_" ]; echo $?)" != 0 ]; then
        term_999="dumb"
    fi
    # status reflects the last command's exit code; capture it explicitly.
    # `trust` because a non-tty fd 2 makes `test -t 2` exit 1 by design — that
    # is the signal we want, not an error to handle. NB: do NOT add `silent` —
    # it compiles to `test -t 2>/dev/null 2>&1`, where the redirect swallows
    # the `2` operand and the check always fails. `test` writes nothing anyway.
    test -t 2
    __status=$?
    local rc_1000="${__status}"
    if [ "$(( rc_1000 != 0 ))" != 0 ]; then
        __interactive_8=0
    elif [ "$([ "_${term_999}" != "_dumb" ]; echo $?)" != 0 ]; then
        __interactive_8=0
    else
        __interactive_8=1
    fi
    ret_term_detect275_v0="$(( __interactive_8 == 1 ))"
    return 0
}

# term_interactive — cached predicate. Detects on first use.
# term_interactive()
term_interactive__276_v0() {
    if [ "$(( __interactive_8 == -1 ))" != 0 ]; then
        term_detect__275_v0 
        ret_term_interactive276_v0="${ret_term_detect275_v0}"
        return 0
    fi
    ret_term_interactive276_v0="$(( __interactive_8 == 1 ))"
    return 0
}

# ---- ESC byte + stderr emit -----------------------------------------------
# Minted lazily and cached. Empty until first esc() call.
__esc_9=""
# esc — a single real ESC (0x1b) byte as Text. Cached after first mint.
# esc()
esc__277_v0() {
    if [ "$([ "_${__esc_9}" != "_" ]; echo $?)" != 0 ]; then
        local command_32
        command_32="$(printf '\x1b')"
        __status=$?
        __esc_9="${command_32}"
    fi
    ret_esc277_v0="${__esc_9}"
    return 0
}

# eprint — write Text to stderr with NO trailing newline. The `%s` keeps the
# payload (which may contain ESC, %, quotes) literal — it is an argument, not
# the format string.
# eprintln — write Text to stderr followed by a newline.
# eprintln(s: Text)
eprintln__279_v0() {
    local s_1230="${1}"
    printf '%s
' "${s_1230}" >&2
    __status=$?
}

# common.ab — shell hardening, traps, helpers used by every other module.
# 
# Port of bash lib/_common.sh. Loaded first by the migration-assistant entry
# point. The bash file carried four things Amber models differently:
# 
# 1. `set -e/-u/-o pipefail/extglob/umask 077` — Amber compiles to a script
# whose header the BUILD step controls; per-module `set` is neither needed
# nor honored from a library. The umask is the one runtime-relevant bit, so
# common_harden() applies it via a raw `trust $umask 077$` once.
# 2. Globals (MIGRATE_HOME, STAGE, STAGE_DIR, MIGRATE_NONINTERACTIVE) that bash
# seeded with `: "${VAR:=default}"`. Amber can't write `${VAR:=...}` inside
# `$...$` ({ is interpolation), so each is a module-level cached scalar
# resolved from env via env_var_get with an Amber-side default.
# 3. The EXIT/INT/TERM trap chain + the background-PID registry. Amber has no
# `trap` keyword and can't invoke a function by its Text name (the bash
# `"$fn"` indirection), so:
# * the PID registry is module-level PARALLEL state (a [Text] + __count,
# the dashboard.ab pattern) and cleanup is a kill sweep, exactly what
# bash __on_signal did;
# * the trap itself is installed ONCE via a single raw `trust $trap ...$`
# (documented escape hatch) — bash's printf/kill in the trap body fire
# at signal time regardless of locale.
# 4. die/require_cmd error chrome → core.eprintln (red, stderr) + exit, since
# EVERYTHING visual goes to stderr (stdout reserved for return values).
# 
# PUBLIC CONTRACT preserved for callers: die, require_cmd, on_exit_register,
# on_signal_track_pid, on_signal_untrack_pid, on_signal_cleanup, arch_os
# (stdout), stage_dir_init, plus accessors migrate_home/stage/stage_dir/
# migrate_noninteractive.
# =====================================================================
# Private helpers FIRST. Amber tree-shakes per imported symbol and keeps only
# transitive deps that textually PRECEDE the public entry point, so every
# private helper a `pub fn` calls must be defined above it or it's silently
# dropped (→ "Function 'x' does not exist" when this module is imported).
# =====================================================================
# red_error_prefix — the bash hard-coded \033[31m error tag, but minted via
# core.esc() so the byte is a real ESC (the reason core.ab exists).
# starts_with_lit — prefix test over a (codepoint-correct) std slice. Used only
# on short uname kernel strings ("MINGW…"/"MSYS…"/"CYGWIN…").
# kill_one <pid> <sig> — guarded single kill of the leader PID. An already-dead
# PID makes kill exit non-zero, which is expected (bash used `|| true`), so the
# failure is swallowed. Returns true if the kill call itself succeeded.
# pkill_group <pid> <sig> — TERM/KILL the children of <pid> (bash signalled the
# whole pgroup via "-$pid"; pkill -P reaches the immediate children, the common
# case for our `aws … &` / spinner forks). Raw escape: std pkill matches by
# NAME, not parent PID, so the -P form is shelled directly. Guarded — no
# children is a non-zero exit, not an error.
# =====================================================================
# die / require_cmd
# =====================================================================
# die <msg> — print a red error to STDERR and exit 1. (bash: printf red >&2;
# exit 1.)
# require_cmd <name> <hint> — abort with an actionable message (exit 127) if
# <name> is not on PATH. (bash: command -v >/dev/null; printf red + hint >&2;
# exit 127.) Pass "" for hint to omit the hint line. is_command is the std
# equivalent of `command -v`. 127 ("command not found") is preserved verbatim so
# callers/CI that key on the code still match.
# =====================================================================
# background-PID registry (signal cleanup)
# =====================================================================
# 
# bash kept __SIG_TRACKED_PIDS as a plain array indexed by ${#arr[@]}. Amber:
# a seeded [Text] + a logical __sig_count (the dashboard.ab seed-slot pattern —
# `let` has no empty-typed-array literal, so index 0 is a placeholder reused by
# the first real append). PIDs are Text because modules capture them as text
# from `$!` and kill/pkill take string pids.
# __export_pids — mirror the registry into $MIGRATE_SIG_PIDS so the bash trap
# body (which runs in signal context, outside Amber's control flow) sees the
# live PID set. Space-separated, matching the trap's `for __p in $MIGRATE_SIG_PIDS`
# loop. Raw escape: env_var_set doesn't `export`, and the trap's shell needs the
# export to inherit the value.
# on_signal_track_pid <pid> — register a live background PID so the cleanup
# sweep can kill it. (bash: __SIG_TRACKED_PIDS[${#...}]="$1".) Modules that fork
# a subprocess MUST call this right after capturing the pid.
# on_signal_untrack_pid <pid> — remove a PID once reaped cleanly. (bash rebuilt
# the array skipping the match; we compact in place and shrink the logical
# count, leaving trailing physical slots as harmless dead entries the
# 0..__sig_count loops never visit.)
# on_signal_tracked_count — test seam + introspection: how many PIDs are live.
# on_signal_cleanup — TERM then KILL every tracked PID + its children. This is
# the body of bash __on_signal's kill loop, callable so tests (and an Amber-side
# caller) can invoke it without a real signal. Each kill is guarded because an
# already-dead PID exits non-zero — expected, not an error (bash: `|| true`).
# =====================================================================
# on_exit_register — cleanup-callback registry
# =====================================================================
# 
# bash stored cleanup FUNCTION NAMES and invoked them by name (`"$fn" "$rc"`).
# Amber cannot call a function by its Text name, so this keeps the NAMES (for
# introspection / a future dispatcher) but the only cleanup that actually runs
# is the PID sweep above — which is what every registered hook in the bash CLI
# ultimately did (kill a backgrounded child). Callers that registered a custom
# hook should instead call on_signal_track_pid for the PIDs they own.
# on_exit_register <fn_name> — record a cleanup callback name. (bash appended to
# __ON_EXIT_FNS.) Kept for contract compatibility; see the note above on why the
# PID sweep is the effective cleanup.
# on_exit_registered_count — test seam.
# =====================================================================
# arch_os
# =====================================================================
# arch_os — return "<os>/<arch>" using the release-binary convention:
# os:   linux | darwin | windows
# arch: amd64 | arm64
# (bash used two `case "$(uname -X)"` blocks; Amber uses an if-chain — there is
# no match/switch keyword.) Unknown OS/arch → die, matching bash `*) die`.
# uname can't meaningfully fail on a supported host; trust it.
# =====================================================================
# globals: MIGRATE_HOME / STAGE / STAGE_DIR / MIGRATE_NONINTERACTIVE
# =====================================================================
# 
# bash seeded these with `: "${VAR:=default}"`. Amber can't write that brace
# form inside `$...$`, and module scalars persist across calls, so each accessor
# resolves from the environment once and caches.
# migrate_home — per-project state root. Env MIGRATE_HOME wins; else
# "$PWD/migration-assistant-workspace" (so the cwd becomes the project root,
# exactly as bash documented).
# stage — the active stage name (env STAGE, default "default").
# stage_dir — "$MIGRATE_HOME/$STAGE". Recomputed from the two accessors so a
# test that pins env before first use sees a consistent value.
# migrate_noninteractive — Bool: headless mode? (env MIGRATE_NONINTERACTIVE ==
# "1", default 0.) Used by ui_prompt/ui_confirm callers.
# common_reset_cache — test seam: drop the cached globals so a test can re-pin
# the environment and observe new values. (No bash analogue — bash re-sources to
# reset; Amber memoizes, so we expose an explicit invalidate.)
# =====================================================================
# stage_dir_init
# =====================================================================
# mkdir_or_die <path> — dir_create (mkdir -p semantics: no error if it already
# exists) but failable; on a genuinely un-creatable path, die with a clear
# message. This keeps stage_dir_init NON-failable for callers while preserving
# the bash `set -e` behavior (abort the program on a real mkdir failure).
# stage_dir_init — create the stage subtree if missing. Idempotent.
# (bash: a single `mkdir -p` with seven paths.)
# =====================================================================
# harden + trap install
# =====================================================================
# common_harden — apply the runtime-relevant bit of the bash `set`/`umask`
# header. `set -e/-u/-o pipefail/extglob` govern the compiled bash script as a
# whole and aren't meaningfully re-settable from a sourced library, so the only
# carried-over effect is `umask 077` (private state files). One raw escape:
# Amber has no umask builtin.
# 0 = not installed, 1 = installed. The bash trap was gated on
# MIGRATE_OWNS_PROCESS=1 so it wouldn't hijack bats / sourced-only callers.
# common_install_traps — install the INT/TERM trap chain ONCE. Gated on
# MIGRATE_OWNS_PROCESS=1 exactly like bash (keeps the trap out of test harnesses
# and library-only consumers). Amber has no `trap` keyword, so this is a single
# documented raw escape. The handler prints the yellow interrupt notice, then
# TERMs/KILLs every space-separated pid in $MIGRATE_SIG_PIDS (kept in sync by
# on_signal_track/untrack via __export_pids — bash's __SIG_TRACKED_PIDS was a
# process-global the trap closed over; $MIGRATE_SIG_PIDS is its Amber analogue),
# then exits 130. The \x1b in printf is interpreted by bash at fire time
# (locale-independent); $-sigils are backslash-escaped so Amber leaves them raw.
# term.ab — terminal control primitives. Raw ANSI/VT100 only. No tput.
# 
# Port of bash lib/term.sh. The bash file existed to avoid tput's fork +
# terminfo cost and its non-zero-exit-on-minimal-terminfo crash under `set -e`.
# In Amber we keep raw VT100 escapes for the same reasons, and additionally
# gain UTF-8-correct width math for free: `len` and `slice` count CODEPOINTS,
# so the byte-vs-codepoint bugs the bash version fought (panel truncation,
# spinner indexing in the C locale) simply cannot occur here.
# 
# Contract carried over verbatim:
# * EVERYTHING goes to stderr (stdout is reserved for return values).
# * No-op gracefully when stderr isn't a TTY — gated on core.term_interactive.
# * Cursor + line-wrap are OWNED here. A dedicated EXIT trap restores both
# unconditionally on ANY exit path (Ctrl-C, errexit, exec, normal).
# * setup/reset are exact inverses.
# 
# Colors are the 8-color ANSI SGR set — sufficient for an installer and
# CI-log-safe. esc() (from core) mints the real ESC byte.
# ---- geometry (refreshed from stty; defaults when no tty) ------------------
# term_winch — refresh LINES/COLUMNS from `stty size`. Cheap. Leaves the
# cached values untouched when stty fails (no controlling tty). The bash
# version wired this to a SIGWINCH trap; we expose it as a callable that the
# render loop can poll, plus install_winch_trap below for live resize.
# parse_dim — a tiny digits-only parser. Returns 0 on anything non-numeric so
# callers can guard. (std parse_int is failable; this keeps the call sites flat.)
# Test seam: pin geometry without a real terminal (mirrors the bats setup that
# assigns __TERM_LINES / __TERM_COLUMNS by hand).
# ---- cursor management (owned here) ----------------------------------------
# 0 = shown, 1 = hidden. Tracked so hide is idempotent and the reset trap
# doesn't double-emit.
# term_hide_cursor — \e[?25l. Idempotent. No-op when not interactive.
# term_show_cursor — \e[?25h. Most callers rely on the EXIT trap instead;
# this is for flashing the cursor between render phases.
# term_save_cursor / term_restore_cursor — \e7 / \e8 (DEC). Bracket overlays.
# term_clear_line — clear current line + carriage return (\e[2K\r).
# ---- reset + trap ----------------------------------------------------------
# term_reset — single restore of cursor + line-wrap. Inverse of every state
# mutation. Idempotent; safe to call repeatedly. Emitted even when not
# interactive (harmless) so the trap path is unconditional, matching bash.
# term_install_reset_trap — install a dedicated EXIT trap that restores the
# cursor + line-wrap on ANY exit path. bash printf inside the trap interprets
# \x1b at fire time, so this works regardless of locale. Mirrors term.sh's
# "dedicated trap that runs FIRST, unconditional" design.
# term_install_winch_trap — refresh geometry on terminal resize. The handler
# re-reads stty size into our cached globals.
# ---- line-wrap control -----------------------------------------------------
# 
# Disabling autowrap (DECAWM \e[?7l) makes one logical line == one physical
# row, which is what the dashboard's cursor-up redraw math assumes. The bash
# PR's whole "dashboard re-prints every poll" bug was wrap math: long lines
# wrapped onto extra physical rows that the logical line count didn't see.
# ---- presentation: hyperlinks ----------------------------------------------
# term_link <url> <label> — OSC 8 clickable hyperlink. Falls back to
# "label (url)" plain text when not interactive (CI-log readable), and to just
# the url when label == url.
# ---- presentation: panel ---------------------------------------------------
# repeat_char — N copies of a (possibly multi-byte) glyph. rpad on an empty
# seed gives exactly N codepoints — UTF-8 correct, unlike bash's `printf %*s`
# + substitution which counted bytes.
# clip — truncate Text to width codepoints, appending … when cut. UTF-8 safe.
# term_panel <title> <lines...> — render a UTF-8 box panel to stderr. Because
# nested arrays are forbidden in Amber, lines come in as a [Text]. Width is
# clamped to a sane minimum so narrow terminals still read.
# 
# Returns the rendered block as Text too (in addition to writing stderr) so
# tests can assert on it without a TTY.
# ---- presentation: progress bar --------------------------------------------
# term_progress <cur> <total> <msg> — sticky bottom progress line. Uses
# \e7 (save) → jump to LINES → \e[2K (clear) → paint → \e8 (restore) so
# concurrent stderr above the line isn't disturbed. Plain "[cur/total] msg"
# when not interactive. Returns the emitted Text for tests.
# ---- presentation: window title --------------------------------------------
# term_set_title — OSC 0 (window + icon title). Harmless on terminals that
# ignore it; useful for long deploys ("migration-assistant: helm-install").
# ---- spinner glyph ---------------------------------------------------------
# term_spinner_frame <tick> — one braille glyph from the cycle. The glyph set
# is an array (each element its own codepoint string), so indexing is
# locale-independent — the exact bug class the bash array form was created to
# dodge, now structural.
# ui.ab — terminal UI primitives: color chrome, prompts, banners, tables.
# 
# Port of bash lib/ui.sh. The cardinal rule survives intact:
# 
# *** ALL UI CHROME GOES TO STDERR. Stdout is reserved for return values. ***
# 
# In bash this rule prevented captured-stdout pollution (mode=$(_select_mode)
# breaking when chrome leaked into the value). In Amber, functions RETURN their
# value directly, so the discipline is even cleaner: ui_prompt/ui_select return
# a Text; chrome is emitted via eprint/eprintln (stderr); the returned value is
# never contaminated. Tests assert on the returned value AND can capture stderr.
# 
# Colors come from core.esc(). No tput, no ncurses — same rationale as term.ab.
# ---- color accessors -------------------------------------------------------
# 
# Thin wrappers so call sites read `ui_red()` not `"{esc()}[31m"`. Each is ""
# when not interactive, so chrome silently de-colors in CI logs.
# sgr(code: Text)
sgr__353_v0() {
    local code_997="${1}"
    term_interactive__276_v0 
    local ret_term_interactive276_v0__26_12="${ret_term_interactive276_v0}"
    if [ "$(( ! ret_term_interactive276_v0__26_12 ))" != 0 ]; then
        ret_sgr353_v0=""
        return 0
    fi
    esc__277_v0 
    local ret_esc277_v0__27_14="${ret_esc277_v0}"
    ret_sgr353_v0="${ret_esc277_v0__27_14}${code_997}"
    return 0
}

# parse_choice — digits-only → Int, else 0 (invalid). Defined high so its
# caller (ui_select) sees it across an import boundary (Amber tree-shakes per
# imported symbol, keeping only deps that textually precede the entry point).
# ui_reset()
ui_reset__355_v0() {
    sgr__353_v0 "[0m"
    ret_ui_reset355_v0="${ret_sgr353_v0}"
    return 0
}

# ui_dim_c()
ui_dim_c__361_v0() {
    sgr__353_v0 "[2m"
    ret_ui_dim_c361_v0="${ret_sgr353_v0}"
    return 0
}

# ---- one-line chrome -------------------------------------------------------
# ui_banner <title> — boxed title with ━ rules above and below. Returns the
# rendered block (also written to stderr) for testability.
# ---- prompts ---------------------------------------------------------------
# ui_prompt <prompt> <default> → the answer (Text), printed to STDOUT only when
# called bare (mirrors the bash $(...) capture pattern); chrome/question go to
# stderr. Honors MIGRATE_NONINTERACTIVE=1 (skip read, return default) and empty
# input → default.
# 
# The bash version read from /dev/tty (so a curl-piped installer could still
# ask). std `input_prompt` reads stdin; for the non-interactive + default
# semantics we gate on the env flag first and only call input_prompt when
# genuinely interactive.
# ui_confirm <prompt> <default Y|N> → Bool (true = yes). Empty input → default.
# First-letter, case-insensitive. Unknown input → conservative no.
# ui_select <prompt> <options[]> → chosen option Text, or "" on invalid input.
# Numbered list to stderr, default 1.
# ui_table <header> <rows[]> — simple monospace table to stderr. Returns the
# rendered block for tests.
# ui_spinner_frame <tick> <msg> → a single \r-prefixed spinner line (Text). The
# bash version forked a background animator with an interruptible read; Amber
# 0.6 has no background jobs, so callers drive the frame from their own poll
# loop (the dashboard/cfn loop already ticks once per poll) and emit this.
# ui_spinner_clear — erase the spinner line.
# log.ab — append-only log to <stage>/log/migrate.log + a synchronous stream tee.
# 
# Port of bash lib/log.sh. The invariants carry over verbatim:
# * Logs to file always; mirrors to stderr only when --verbose (MIGRATE_VERBOSE=1).
# * Single rotation when the log exceeds 5 MiB → migrate.log.1 (overwrites prior .1).
# * Safe to call before the stage dir exists (LOG_FILE stays /dev/null until log_init).
# * The log path is announced on startup and on exit so the operator can
# `tail -f` it from a second terminal.
# 
# Public API (names preserved so other modules call these unchanged):
# log_init                  Initialise the log file (rotates if needed).
# log_announce / log_announce_exit
# log_info / log_warn / log_error / log_debug
# log_stream <prefix> <cmd> Run cmd; tee its combined output live to the log
# file AND the operator's stderr, returning cmd's
# exit code (an Int — Amber funcs return values, not $?).
# 
# THE BIG REDESIGN — log_stream. The bash version drove a background FIFO reader
# (`mkfifo`; `( … ) &`; `wait "$reader_pid"`) so a file logger and a stderr
# printer could both consume the child's byte stream and the parent could still
# recover the child's exit code. Amber 0.6 has NO background jobs, no `&`, no
# `wait`. We rewrite it SYNCHRONOUSLY: run the command once with 2>&1 captured to
# a temp file, recover the exit code from `status()`, then walk the captured
# output line-by-line teeing each line to BOTH file_append(LOG_FILE) and stderr.
# Same observable contract (file gets every line, operator sees every line,
# exit code propagated), minus the concurrency the single-process CLI never
# needed. The lost property is true live streaming — output now appears after
# the command finishes rather than as-it-runs — which is acceptable: this CLI
# is a one-operator interactive tool, not a log shipper.
# 
# Colors: the bash file interpolated $__UI_C_DIM / $__UI_C_RESET (ui.sh globals).
# We mint them from core.esc(), gated on term_interactive() exactly like ui.sh's
# sgr() — chrome de-colors to "" in CI logs and the file never gets ESC bytes.
# ---- module state ----------------------------------------------------------
# 
# Module-level `let` compiles to a bash global that persists across calls, so
# LOG_FILE set by log_init is visible to every later log_* call in the process
# — exactly the lifetime the bash global had. Seeded to /dev/null so calls made
# before log_init no-op to the bit bucket (the "safe before stage_dir_init"
# invariant). An explicit LOG_FILE env var (set by the bats harness / diag.sh)
# wins, mirroring bash `LOG_FILE="${LOG_FILE:-/dev/null}"`.
__LOG_FILE_21="/dev/null"
# ---- stage directory resolution (mirrors state.ab) -------------------------
# 
# bash log_init called stage_dir_init (from state.sh) and then read $STAGE_DIR.
# To stay decoupled from state.ab's load order we resolve the path lazily from
# the environment, byte-for-byte matching state.ab's stage_dir(): an explicit
# STAGE_DIR wins; else MIGRATE_HOME (default "$PWD/migration-assistant-workspace")
# joined with STAGE (default "default").
# ---- colors (gated, mirror ui.sh sgr discipline) ---------------------------
# dim_c / reset_c — ESC SGR, "" when stderr isn't a TTY so chrome stays out of
# CI logs and the log FILE never receives ESC bytes (file lines are built from
# the level/prefix only, never these).
# dim_c()
dim_c__391_v0() {
    term_interactive__276_v0 
    local ret_term_interactive276_v0__76_12="${ret_term_interactive276_v0}"
    if [ "$(( ! ret_term_interactive276_v0__76_12 ))" != 0 ]; then
        ret_dim_c391_v0=""
        return 0
    fi
    esc__277_v0 
    local ret_esc277_v0__77_14="${ret_esc277_v0}"
    ret_dim_c391_v0="${ret_esc277_v0__77_14}[2m"
    return 0
}

# reset_c()
reset_c__392_v0() {
    term_interactive__276_v0 
    local ret_term_interactive276_v0__80_12="${ret_term_interactive276_v0}"
    if [ "$(( ! ret_term_interactive276_v0__80_12 ))" != 0 ]; then
        ret_reset_c392_v0=""
        return 0
    fi
    esc__277_v0 
    local ret_esc277_v0__81_14="${ret_esc277_v0}"
    ret_reset_c392_v0="${ret_esc277_v0__81_14}[0m"
    return 0
}

# is_verbose — MIGRATE_VERBOSE=1 toggles stderr mirroring. Bash defaulted it to
# 0 via `: "${MIGRATE_VERBOSE:=0}"`; an unset/empty var is treated as off.
# is_verbose()
is_verbose__393_v0() {
    env_var_get__124_v0 "MIGRATE_VERBOSE"
    __status=$?
    if [ "${__status}" != 0 ]; then
        :
    fi
    local v_1238="${ret_env_var_get124_v0}"
    ret_is_verbose393_v0="$([ "_${v_1238}" != "_1" ]; echo $?)"
    return 0
}

# ---- pure formatters (return Text — the tested seam) -----------------------
# 
# These build the exact byte string the bash printf produced, with NO I/O, so
# tests assert the formatting contract (prefix, level, STREAM[bucket] shape)
# without a TTY or a real log file. The impure log_* / log_stream wrappers below
# just route these to file_append + stderr.
# fmt_log_line <ts> <lvl> <msg> → "[<ts>] <lvl> <msg>". Mirrors bash
# `printf '[%s] %s %s\n'` (no trailing newline here; callers add it).
# fmt_log_line(ts: Text, lvl: Text, msg: Text)
fmt_log_line__394_v0() {
    local ts_1234="${1}"
    local lvl_1235="${2}"
    local msg_1236="${3}"
    ret_fmt_log_line394_v0="[${ts_1234}] ${lvl_1235} ${msg_1236}"
    return 0
}

# fmt_stream_line <ts> <prefix> <line> → the log-FILE form of one streamed line.
# Mirrors bash `printf '[%s] STREAM[%s] %s\n'`. The literal "STREAM[<prefix>]"
# token is what test_log.bats greps for, so it must stay byte-exact.
# fmt_stream_line(ts: Text, prefix: Text, line: Text)
fmt_stream_line__395_v0() {
    local ts_1214="${1}"
    local prefix_1215="${2}"
    local line_1216="${3}"
    ret_fmt_stream_line395_v0="[${ts_1214}] STREAM[${prefix_1215}] ${line_1216}"
    return 0
}

# fmt_stream_chrome <prefix> <line> → the STDERR (operator) form of one streamed
# line: "  <prefix>│ <line>", wrapped in dim/reset. Mirrors bash
# `printf '%s  %s│%s %s\n' "$__UI_C_DIM" "$prefix" "$__UI_C_RESET" "$line"`.
# fmt_stream_chrome(prefix: Text, line: Text)
fmt_stream_chrome__396_v0() {
    local prefix_1219="${1}"
    local line_1220="${2}"
    dim_c__391_v0 
    local ret_dim_c391_v0__115_14="${ret_dim_c391_v0}"
    reset_c__392_v0 
    local ret_reset_c392_v0__115_34="${ret_reset_c392_v0}"
    ret_fmt_stream_chrome396_v0="${ret_dim_c391_v0__115_14}  ${prefix_1219}│${ret_reset_c392_v0__115_34} ${line_1220}"
    return 0
}

# ---- timestamps ------------------------------------------------------------
# 
# Two raw `date` escapes. Amber's std/date exposes date_now() (epoch seconds)
# but NO strftime-style formatter, so there is no pure-Amber way to build the
# human ('%Y-%m-%d %H:%M:%S %Z') or ISO-8601 ('%Y-%m-%dT%H:%M:%S%z') strings the
# log lines require. state.ab hit the same wall in state_archive and resolved it
# the same way. The format strings are constants, never user data.
# log_ts — ISO-8601 with timezone offset, the per-line timestamp.
# log_ts()
log_ts__397_v0() {
    local command_35
    command_35="$(date '+%Y-%m-%dT%H:%M:%S%z')"
    __status=$?
    ret_log_ts397_v0="${command_35}"
    return 0
}

# header_ts — human-readable session-header timestamp.
# ---- log_init --------------------------------------------------------------
# file_size_bytes <path> → size in bytes, or 0 on any error. The bash version
# forked `stat -f %z` on macOS vs `stat -c %s` on Linux (the BSD/GNU split),
# with `|| echo 0` so a missing file or stat error degraded to 0 rather than
# aborting the rotation check. is_macos() (from std.ab) picks the branch.
# log_init — point LOG_FILE at <stage>/log/migrate.log, rotate it once if it has
# grown past 5 MiB, then append the session-separator header. Idempotent enough
# to call once per invocation. The 5_242_880 threshold is 5 * 1024 * 1024.
# ---- log_announce ----------------------------------------------------------
# log_announce_startup — the startup form: "  log: <path>  (tail -f to follow…)".
# Stderr-only (never pollutes stdout, the return-value channel). No-ops when the
# log is /dev/null or unset, mirroring bash's guard. Split from the --exit form
# (vs bash's positional `$1 == --exit`) so each shape is a plain, testable fn.
# log_announce_exit_line — the --exit form: a blank line then "Log: <path>", plus
# a "re-run with --verbose" hint UNLESS already verbose. Mirrors the bash
# `log_announce --exit` branch.
# log_announce [--exit] — preserves the bash positional contract for callers
# that pass the flag through. Bare → startup form; "--exit" → exit form.
# ---- log_<level> -----------------------------------------------------------
# log <lvl> <msg> — append a level-prefixed, timestamped line to the file, and
# mirror it to stderr when verbose. The single private workhorse the four public
# wrappers delegate to (bash `log()` + log_info/warn/error/debug).
# log_emit(lvl: Text, msg: Text)
log_emit__404_v0() {
    local lvl_1232="${1}"
    local msg_1233="${2}"
    log_ts__397_v0 
    local ret_log_ts397_v0__241_29="${ret_log_ts397_v0}"
    fmt_log_line__394_v0 "${ret_log_ts397_v0__241_29}" "${lvl_1232}" "${msg_1233}"
    local line_1237="${ret_fmt_log_line394_v0}"
    file_append__45_v0 "${__LOG_FILE_21}" "${line_1237}
"
    __status=$?
    is_verbose__393_v0 
    local ret_is_verbose393_v0__243_8="${ret_is_verbose393_v0}"
    if [ "${ret_is_verbose393_v0__243_8}" != 0 ]; then
        eprintln__279_v0 "${line_1237}"
    fi
}

# log_info(msg: Text)
log_info__405_v0() {
    local msg_1231="${1}"
    log_emit__404_v0 "INFO" "${msg_1231}"
}

# log_debug — DEBUG lines exist only in verbose mode (bash gated the whole call
# on `[[ "$MIGRATE_VERBOSE" -eq 1 ]]`, so they never even hit the file otherwise).
# ---- log_announce_exit (on-exit hook) --------------------------------------
# dump_tail_lines <pat> <n> — read LOG_FILE and emit (to stderr) the last <n>
# lines that match ERE <pat>, in order. An empty <pat> means "match every line",
# so this serves BOTH the focused ERROR/WARN dump and the full tail. Replaces the
# bash `grep -E … | tail -n N >&2` and `tail -n N … >&2` line-extraction
# pipelines: file_read pulls the bytes (returns "" if unreadable, the `|| true`
# best-effort semantics), match_regex applies the same ERE grep used (verbatim
# inner/outer groups), and a last-N window over split_lines is `tail -n`. Each
# kept line goes out via eprintln, exactly like the piped `>&2` did.
# log_announce_exit <rc> — the on_exit hook. Prints the log path one last time;
# and when the run FAILED (rc != 0) AND we're non-interactive (MIGRATE_NONINTERACTIVE=1
# OR fd 2 is not a TTY), dumps a focused ERROR/WARN excerpt plus a full tail of
# migrate.log to stderr — a CI operator staring at a Jenkins console needs to see
# WHY it failed without rerunning. The two line dumps are pure-Amber now
# (dump_tail_lines), so no grep/tail shell-outs remain here.
# ---- log_stream (SYNCHRONOUS rewrite of the background FIFO tee) ------------
# log_stream <prefix> <cmd> — run <cmd> (a single Text run via the shell), tee
# its combined stdout+stderr to the log file AND the operator's stderr one
# prefixed line at a time, and RETURN the command's exit code as an Int.
# 
# Contract vs bash: bash took `<prefix> CMD ARGS…` as separate argv and returned
# the rc via $?. Amber can't take a varargs command or a function pointer, so the
# command arrives as one Text (the caller composes it, e.g. "aws cloudformation
# deploy …") and the rc is the function's return value. Every other observable
# is identical: the file gets one `[ts] STREAM[prefix] <line>` per output line,
# the operator's stderr gets the dim-prefixed mirror, and a final
# `stream[prefix] exit=<rc>` INFO line lands in the file (the bash log_info call).
# 
# Implementation: capture combined output to a temp file (2>&1 redirect — there
# is no FIFO + background reader in Amber), recover the rc, then walk the lines.
# log_stream(prefix: Text, cmd: Text)
log_stream__411_v0() {
    local prefix_1223="${1}"
    local cmd_1224="${2}"
    # A temp file under the log dir collects the child's combined output. mktemp
    # is a raw escape (no std/fs tempfile); $$ uniquifies it as in bash. The
    # redirect `> tmp 2>&1` merges both streams the way the bash FIFO fed both
    # readers off one byte stream.
    local command_36
    command_36="$(mktemp 2>/dev/null)"
    __status=$?
    local tmp_1225="${command_36}"
    if [ "$([ "_${tmp_1225}" != "_" ]; echo $?)" != 0 ]; then
        # No tempfile available — degrade to running the command with no capture
        # rather than aborting. Rare (mktemp essentially always works). Subshell
        # so the command's own `exit` can't terminate us (see below).
        ( eval "${cmd_1224}" )
        __status=$?
        ret_log_stream411_v0="${__status}"
        return 0
    fi
    # Run the command in a SUBSHELL, merging stdout+stderr into the temp file.
    # The subshell is load-bearing: bash log_stream ran the child as `"$@"` (a
    # separate process), so a command that ends in `exit N` set N as its status
    # WITHOUT killing the parent. We run via `eval` (the command is one Text, not
    # argv), and a bare `eval "…; exit 7"` runs in the CURRENT shell — its `exit`
    # would terminate the whole CLI/test process. Wrapping in `( … )` confines the
    # exit to the subshell, whose status `status()` then recovers — faithfully
    # reproducing bash's `"$@" >fifo 2>&1; rc=$?`. `trust` because a non-zero exit
    # is the DATA we propagate, not an error to handle.
    ( eval "${cmd_1224}" ) > "${tmp_1225}" 2>&1
    __status=$?
    local rc_1226="${__status}"
    # Read the whole capture, then tee each line: file form via file_append, and
    # operator form via stderr. file_read pulls the bytes (failed→"" if the temp
    # vanished, matching the old `cat … 2>/dev/null`); split_lines gives the
    # bash-equivalent line set (trailing-newline-less final fragment still counts;
    # no phantom empty line).
    file_read__43_v0 "${tmp_1225}"
    __status=$?
    if [ "${__status}" != 0 ]; then
        :
    fi
    local captured_1227="${ret_file_read43_v0}"
    split_lines__8_v0 "${captured_1227}"
    local ret_split_lines8_v0__375_17=("${ret_split_lines8_v0[@]}")
    for line_1228 in "${ret_split_lines8_v0__375_17[@]}"; do
        log_ts__397_v0 
        local ts_1229="${ret_log_ts397_v0}"
        fmt_stream_line__395_v0 "${ts_1229}" "${prefix_1223}" "${line_1228}"
        local ret_fmt_stream_line395_v0__377_33="${ret_fmt_stream_line395_v0}"
        file_append__45_v0 "${__LOG_FILE_21}" "${ret_fmt_stream_line395_v0__377_33}
"
        __status=$?
        fmt_stream_chrome__396_v0 "${prefix_1223}" "${line_1228}"
        local ret_fmt_stream_chrome396_v0__378_18="${ret_fmt_stream_chrome396_v0}"
        eprintln__279_v0 "${ret_fmt_stream_chrome396_v0__378_18}"
    done
    # Clean up the temp capture (rm builtin; a missing file is harmless → trust).
    local __rm_39=
    local __rm_40=
    rm ${__rm_40} ${__rm_39} "${tmp_1225}"
    # Final marker line — identical to the bash `log_info "stream[$prefix] exit=$rc"`.
    log_info__405_v0 "stream[${prefix_1223}] exit=${rc_1226}"
    ret_log_stream411_v0="${rc_1226}"
    return 0
}

# log_file — accessor for the current LOG_FILE (test seam; the bats tests read
# $LOG_FILE directly, which Amber's module-private global doesn't expose).
# log_file()
log_file__412_v0() {
    ret_log_file412_v0="${__LOG_FILE_21}"
    return 0
}

# cfn_outputs.ab — read CloudFormation stack outputs and flatten them into
# KEY=VALUE lines for the downstream tools (helm.ab, crane.ab, build.ab).
# 
# Port of the OUTPUT-PARSING half of bash lib/cfn.sh: cfn_outputs,
# cfn_output_value, _cfn_extract_exports, _cfn_pick. The deploy/dashboard half
# (cfn_deploy_or_skip, _cfn_tail_events, the background `aws cloudformation
# deploy` + live event tail) is a SEPARATE later module — the CFN status
# classifier it needs (cfn_status_class) already lives in dashboard.ab, so we
# do NOT duplicate it here.
# 
# Why this exists: the real opensearch-migrations CFN stacks publish a single
# output named MigrationsExportString — a long string of bash
# `export VAR=VALUE; export …;` clauses. Callers that naively grepped for a
# flat output key (e.g. EKSClusterName) died because that key never existed.
# We expand that blob here so callers see flat KEY=VALUE pairs PLUS every raw
# OutputKey=OutputValue (backwards compat if a future template adds a flat
# output again).
# 
# Discipline carried over from cfn.sh: the parsed VALUE lines are the
# function's return — they go to stdout via the returned [Text]. Any chrome
# would go to stderr (none here; these are pure parsers). The ONLY external
# command is `aws cloudformation describe-stacks`; everything else is pure
# in-Amber string work (the bash awk/tac pipelines are replaced by split +
# slice + regex, NO tac/awk fork).
# ---- tiny private helpers (defined FIRST: Amber tree-shakes per imported
# symbol and only keeps transitive deps that textually PRECEDE the public
# entry point across an import boundary — a callee defined after its caller is
# silently dropped) ----------------------------------------------------------
# Prefix test is std.ab's `starts_with(prefix, s)` — its arg order already
# matches how we call it (prefix first), so the old local copy is gone. (No
# import cycle: std.ab depends only on std/*.)
# first_eq_index — index of the FIRST '=' in s, or -1. Used to split a
# KEY=VALUE line at the first '=' only, so values that themselves contain '='
# (URLs with ?q=r, ARNs like role/x=y) survive intact. Mirrors the bash
# `awk -F= … sub(/^[^=]+=/, "")` trick which strips only up to the first '='.
# kv_key — the KEY part of a "KEY=VALUE" line (everything before the first '=').
# Empty if the line has no '='.
# kv_value — the VALUE part of a "KEY=VALUE" line (everything after the first
# '='). Empty if the line has no '='.
# is_export_kv — does <s> look like a shell assignment "NAME=…"? NAME must be a
# valid identifier ([A-Za-z_][A-Za-z0-9_]*) immediately followed by '='. This
# is the exact gate the bash `_cfn_extract_exports` awk used
# (`$0 ~ /^[A-Za-z_][A-Za-z0-9_]*=/`) to drop bogus fragments ("bogus line",
# empty entries) while keeping real exports.
# ---- _cfn_extract_exports --------------------------------------------------
# _cfn_extract_exports <blob> → one KEY=VALUE per matched export.
# 
# Reads the bash-export blob (a string of `export VAR=VALUE; export …;`
# clauses) and emits flat KEY=VALUE lines. Tolerates `;` separators, leading
# whitespace, a missing `export ` prefix, and values that contain `=`
# (URLs, ARNs) or `:` (ARNs).
# 
# Port note: the bash version set awk's RS=";" to record-split on semicolons,
# then per record trimmed leading whitespace, stripped a leading "export ",
# and kept records matching the identifier= pattern. Amber has no awk RS, so
# we split on ";" ourselves (no fork) and apply the same three transforms.
# ---- cfn_outputs -----------------------------------------------------------
# cfn_outputs <stack> <region> → [Text] of KEY=VALUE lines (stdout contract).
# 
# Runs `aws cloudformation describe-stacks … --query
# 'Stacks[0].Outputs[].[OutputKey,OutputValue]' --output text`, which emits one
# TAB-separated <OutputKey>\t<OutputValue> row per output.
# 
# Pass A: every raw OutputKey=OutputValue (so a future flat output like
# EKSClusterName still surfaces — preserves backwards compat).
# Pass B: expand the MigrationsExportString blob into its embedded exports.
# 
# An empty/failed AWS response yields [] (the bash `[[ -z "$raw" ]] && return 0`
# — no error, no output).
# ---- cfn_output_value ------------------------------------------------------
# cfn_output_value <stack> <region> <key> → the value of <key>, or "".
# 
# Convenience for callers wanting a single named field. Mirrors the bash
# `cfn_outputs … | awk -F= -v k=key '$1==k { sub(/^[^=]+=/,""); print; exit }'`
# — first KEY=VALUE line whose key matches exactly wins; split on the first '='
# so values with embedded '=' survive.
# ---- _cfn_pick -------------------------------------------------------------
# _cfn_pick <outputs> <keys> → first key that resolves to a NON-EMPTY value.
# 
# <outputs> is the [Text] of KEY=VALUE lines produced by cfn_outputs; <keys> is
# the fallback chain. The first key present with a non-empty value wins; absent
# or empty keys silently advance. Returns "" when none match.
# 
# Used by helm.ab / crane.ab / build.ab to tolerate template renames between
# MA releases (e.g. MIGRATIONS_EKS_CLUSTER_NAME → EKSClusterName). Port note:
# bash took the keys as positional `"$@"` and the outputs as a single newline
# string; Amber takes them as [Text] arrays directly — cleaner, no re-split.
# helm_ctx.ab — kubeconfig + context binding for the helm install flow.
# 
# Port of the CONTEXT-BINDING slice of bash lib/helm.sh:
# HELM=()/KUBECTL=() command arrays, helm_kctx_init, helm_kubeconfig_setup,
# HELM_CHART_NAME / HELM_RELEASE_NAME / HELM_NAMESPACE constants.
# 
# The rest of helm.sh is split across sibling modules (helm_recover /
# helm_diag / helm_install own recovery, diagnostics, and the install/upgrade
# flow). This file owns ONLY the context plumbing those modules build on.
# 
# ============================================================================
# KEY DESIGN — command arrays → a stored Text flag prefix.
# ============================================================================
# 
# Bash bound context with COMMAND ARRAYS:
# HELM=(helm --kube-context X)
# KUBECTL=(kubectl --context X)
# then splatted them: `"${HELM[@]}" upgrade --install …`. Amber 0.6 cannot
# splat an array as the head of a `$ … $` command, so we model the SAME binding
# as a stored Text FLAG PREFIX:
# * helm_ctx_init() reads KUBECTL_CONTEXT from state and, when set, stores
# "--kube-context {ctx}" (helm) and "--context {ctx}" (kubectl) in
# module-level globals.
# * Every later call interpolates the prefix UNQUOTED so the shell word-splits
# it back into the two flag tokens (or into NOTHING when the context is
# unset — the bare `HELM=(helm)` / `KUBECTL=(kubectl)` default):
# trust $ helm {helm_ctx_flags()} upgrade --install … $
# trust $ kubectl {kubectl_ctx_flags()} wait … $
# * Context values are k8s context names ([A-Za-z0-9_.-], no spaces), so the
# unquoted word-split is safe — exactly the assumption the bash `"${HELM[@]}"`
# expansion relied on for its element safety.
# 
# helm_ctx_flags() / kubectl_ctx_flags() are PUBLIC so helm_recover / helm_diag /
# helm_install (and cleanup / console / resume) build their commands off the
# same single binding site — the bash one-definition-site contract.
# 
# ============================================================================
# MODULE-INSTANCE / STATE note (carried over from cfn_deploy.ab + crane.ab).
# ============================================================================
# 
# In Amber 0.6 each importer gets its OWN copy of an imported module's globals,
# so helm_ctx.ab's state.ab store is a DIFFERENT instance from helm_install's.
# The stored CTX flags therefore live in THIS module's own module-level globals
# (set by helm_ctx_init / helm_kubeconfig_setup, read by helm_ctx_flags /
# kubectl_ctx_flags) — every module that needs the binding calls helm_ctx_init()
# to populate its own copy from the shared on-disk state.env (KUBECTL_CONTEXT),
# exactly the way bash's helm_kctx_init refreshed each sourcing module's
# HELM=()/KUBECTL=() from the saved KUBECTL_CONTEXT.
# ---- pinned names (mirror the bash constants verbatim) ---------------------
# 
# The Migration Assistant chart hardcodes namespace=ma in its rendered
# resources AND uses .Release.Name to write the namespace env var into its
# post-install argo-templates Job. To keep things consistent we pin BOTH the
# helm release name AND the kubernetes namespace to "ma" regardless of the
# operator's chosen stage. The stage name is the CFN-stack / ECR-repo /
# state-directory namer; it does NOT flow through to k8s.
# ---- context binding (module-level — the HELM=()/KUBECTL=() globals) -------
# 
# Module-level `let` compiles to a bash global that persists across calls, so a
# binding set by helm_ctx_init / helm_kubeconfig_setup is visible to every later
# helm_ctx_flags / kubectl_ctx_flags call in the process — the lifetime the bash
# HELM=()/KUBECTL=() arrays had. Seeded empty (bare `helm` / `kubectl`, no
# context) so pre-context callers and tests still resolve a sane command.
__helm_ctx_flags_v_25=""
__kubectl_ctx_flags_v_26=""
# ---- helm_ctx_init ---------------------------------------------------------
# helm_ctx_init — populate THIS module's stored CTX flags from the saved
# KUBECTL_CONTEXT. The Amber re-expression of bash helm_kctx_init's
# `HELM=(helm --kube-context X) / KUBECTL=(kubectl --context X)` rebind:
# instead of rebuilding command arrays we store the two flag-prefix Texts (and
# mirror them into state HELM_CTX / KUBECTL_CTX so a sibling module can read
# them off disk too). When KUBECTL_CONTEXT is unset we clear the flags →
# bare `helm` / `kubectl` (the bash default arrays).
# 
# Idempotent + safe to call from cleanup / console / resume (the bash
# helm_kctx_init contract: "refresh my HELM/KUBECTL from the saved context").
# helm_ctx_init()
helm_ctx_init__429_v0() {
    state_get__253_v0 "KUBECTL_CONTEXT" ""
    local ctx_993="${ret_state_get253_v0}"
    if [ "$([ "_${ctx_993}" == "_" ]; echo $?)" != 0 ]; then
        __helm_ctx_flags_v_25="--kube-context ${ctx_993}"
        __kubectl_ctx_flags_v_26="--context ${ctx_993}"
        state_set__252_v0 "HELM_CTX" "${__helm_ctx_flags_v_25}"
        state_set__252_v0 "KUBECTL_CTX" "${__kubectl_ctx_flags_v_26}"
    else
        __helm_ctx_flags_v_25=""
        __kubectl_ctx_flags_v_26=""
    fi
}

# helm_kctx_init — bash compatibility alias. The bash file exported this exact
# name for cleanup.sh / console.sh to refresh their HELM/KUBECTL copies; keep
# it so those callers (and any resume fast-path) bind unchanged. It first loads
# state from disk (so a sibling module's separate state instance picks up the
# orchestrator's saved KUBECTL_CONTEXT), then delegates to helm_ctx_init.
# helm_kctx_init()
helm_kctx_init__430_v0() {
    state_load__251_v0 
    helm_ctx_init__429_v0 
}

# ---- flag accessors (the binding read-site for every helm/kubectl call) ----
# helm_ctx_flags — the helm context flag prefix ("--kube-context X" or "").
# Interpolate UNQUOTED into a `$ helm {helm_ctx_flags()} … $` so the shell
# word-splits it into the two flag tokens (or nothing when unbound).
# helm_ctx_flags()
helm_ctx_flags__431_v0() {
    ret_helm_ctx_flags431_v0="${__helm_ctx_flags_v_25}"
    return 0
}

# kubectl_ctx_flags — the kubectl context flag prefix ("--context X" or "").
# kubectl_ctx_flags()
kubectl_ctx_flags__432_v0() {
    ret_kubectl_ctx_flags432_v0="${__kubectl_ctx_flags_v_26}"
    return 0
}

# ---- helm_kubeconfig_setup -------------------------------------------------
# helm_kubeconfig_setup — read the EKS cluster name from CFN outputs, run
# `aws eks update-kubeconfig` on it, bind the context, and persist. Idempotent.
# Must run after CFN deploy, before anything that touches the cluster (build,
# crane, helm). Split out of the install flow so the build path (`--build`) and
# any resume fast-path reuse it.
# 
# Port notes vs bash:
# * The `HELM=(…) / KUBECTL=(…)` rebind at the end becomes a helm_ctx_init()
# call after KUBECTL_CONTEXT is persisted — the one definition site.
# * `aws eks update-kubeconfig … >/dev/null` and `kubectl config use-context …
# >/dev/null 2>&1 || true` stay raw `trust $…$` escapes (external cloud /
# cluster calls, no stdlib equivalent). The use-context failure is tolerated
# exactly as the bash `|| true`.
# * `export KUBE_CONTEXT="$kube_ctx"` → env_var_set so the `--build` path
# (build.ab reads KUBE_CONTEXT) sees it in-process, matching bash.
# * state is loaded at entry so this module's own state instance carries the
# orchestrator's CFN_STACK_NAME / AWS_REGION / KUBECTL_CONTEXT.
# helm_diag.ab — the diagnostics-dump half of bash lib/helm.sh.
# 
# helm.sh is split into helm_ctx / helm_recover / helm_diag / helm_install.
# This module owns helm_diag — everything that, on a failed (or slow) helm
# install/upgrade, gathers the evidence an operator (or an agent reading the
# log) would otherwise collect by hand:
# 
# helm_dump_diagnostics        helm status + pods + per-unhealthy-pod describe
# + recent events + stuck-hook-Job describe
# _helm_explain_failure        the WHY behind a failed/pending release
# _helm_dump_failed_job        a failed Job's events + (best-effort) pod logs
# _helm_dump_install_notes     stream the installation-notes ConfigMap
# _helm_inst_log               the "installer│" channel line (file + stderr)
# _helm_inst_dump_pending_pod  a Pending pod's container statuses + events
# _helm_pods_log               the "pods│" channel line (file + stderr)
# 
# Public fn names are preserved verbatim so the other helm_* files +
# console/cleanup/resume that call them keep working unchanged.
# 
# ============================================================================
# KEY DESIGN — context-bound helm/kubectl WITHOUT array splat
# ============================================================================
# The bash file used command-ARRAYS: HELM=(helm --kube-context X) and
# KUBECTL=(kubectl --context X), then splatted `"${HELM[@]}" status …`. Amber
# cannot splat an array as a command, so the binding is modeled as a stored
# Text flag PREFIX, owned by sibling module helm_ctx.ab:
# 
# helm_ctx_flags()    → "--kube-context X" (or "" when no context).
# kubectl_ctx_flags() → "--context X"      (or "" when no context).
# 
# Every external call here is `trust $helm {helm_ctx_flags()} status …$`, the
# flags interpolated UNQUOTED so the shell word-splits "--kube-context X" back
# into two argv tokens — the same expansion the bash `"${HELM[@]}"` relied on.
# "helm" / "kubectl" are the bare binary words.
# 
# helm_ctx.ab owns the binding (helm_ctx_init / helm_kctx_init / the *_ctx_flags
# accessors + the HELM_RELEASE_NAME / HELM_NAMESPACE constants); helm_diag
# IMPORTS the accessors — it does NOT re-define them (a duplicate would split the
# symbol across two module instances). MODULE-INSTANCE caveat (verified against 0.6):
# each importer gets its OWN copy of an imported module's globals, so
# helm_ctx.ab's binding set by the install flow's instance is NOT visible to
# helm_diag's instance. The public dump entry points therefore call helm_ctx's
# helm_kctx_init() ONCE at entry to refresh THEIR copy from the shared on-disk
# state.env (KUBECTL_CONTEXT) — exactly the "refresh my HELM/KUBECTL from the
# saved context" contract bash's helm_kctx_init existed for. After that refresh,
# helm_ctx_flags()/kubectl_ctx_flags() read the right binding within this
# instance.
# 
# ============================================================================
# BACKGROUND WATCHERS — replaced by post-hoc dumps
# ============================================================================
# The bash file spawned helm_watch_pods + helm_watch_installer_logs as
# BACKGROUND processes that polled the cluster while `helm --wait` blocked, so
# the operator saw live movement. Amber 0.6 DOES support background jobs
# (`$ cmd & $` + pid()/await()), but for the install flow we prefer the simpler
# model the port plan calls for: run `helm upgrade --install --wait` to
# completion, then do ONE post-hoc pod-status + install-notes dump. This module
# therefore implements the SYNCHRONOUS dump primitives (helm_dump_diagnostics,
# _helm_dump_install_notes, the pending-pod dump) that the install flow invokes
# AFTER helm returns. The operator-visible contract is preserved (the tests
# assert on the dumped CONTENT, not real-time interleave); only the live-tail
# concurrency — which a single-operator interactive CLI never strictly needed —
# is dropped. (Documented in escapeHatches.)
# 
# ============================================================================
# Other porting notes
# ============================================================================
# * eval "$out_name=(…)" indirect arrays → not needed here (diag dumps return
# Text / Null and stream via log_stream).
# * <<< here-strings (while read … <<<"$x") → split_lines(x) + loop.
# * awk multi-pass tallies (helm_watch_pods' one-awk-pass counter) → an
# in-Amber split_lines + per-row if-chain that tallies total/running/
# pending/failed + collects the name lists — no awk fork, no eval.
# * heredoc → not in this module (the values file lives in helm_install).
# * set +e / set -e guards → per-command `trust`/`failed{}` + status(); every
# diag command is best-effort (bash `|| true`), so each is `trust`-ed.
# * `${HELM[@]} status … -o json | jq` → raw `trust $…$` (jq has no stdlib
# equivalent); the helm failure-description Job name is pulled with
# regex_capture (std.ab), matching the bash `name: (…), kind: Job` shape.
# * STREAM[installer]/STREAM[pods] file lines + dim "installer│"/"pods│"
# chrome are rebuilt via log.ab's fmt_stream_line/fmt_stream_chrome so they
# stay byte-identical to log_stream's output (and log_announce_exit's
# failure-tail grep still matches them). The file path comes from
# log.ab's log_file() accessor (its LOG_FILE global is module-private).
# 
# Imports: helm_ctx (the binding + pinned names), log, ui, std (+ std/text,
# std/fs, std/env for primitives).
# ============================================================================
# Channel log lines (file + operator stderr), byte-identical to log_stream's
# per-line shape. Reused by the installer / pods watchers' synchronous dumps.
# ============================================================================
# log_ts — ISO-8601 with timezone offset, the per-line timestamp. Raw `date`
# escape: Amber's std/date has date_now() (epoch) but no strftime formatter, so
# there is no pure-Amber way to build '%Y-%m-%dT%H:%M:%S%z' — the same wall
# log.ab/state.ab hit. The format string is a constant, never user data.
# log_ts()
log_ts__445_v0() {
    local command_41
    command_41="$(date '+%Y-%m-%dT%H:%M:%S%z')"
    __status=$?
    ret_log_ts445_v0="${command_41}"
    return 0
}

# _helm_channel_log <bucket> <msg> — write ONE "[ts] STREAM[<bucket>] <msg>"
# line to the log file AND the dim "  <bucket>│ <msg>" chrome to stderr, exactly
# as log_stream tees each line. The file form uses fmt_stream_line so it stays
# byte-identical (and log_announce_exit's failure-tail grep for STREAM[installer]
# / STREAM[pods] still matches). log_file() supplies the path (log.ab's LOG_FILE
# global is module-private). The shared workhorse for _helm_inst_log /
# _helm_pods_log (bash had two near-identical printf pairs).
# _helm_channel_log(bucket: Text, msg: Text)
_helm_channel_log__446_v0() {
    local bucket_1212="${1}"
    local msg_1213="${2}"
    log_file__412_v0 
    local ret_log_file412_v0__122_17="${ret_log_file412_v0}"
    log_ts__445_v0 
    local ret_log_ts445_v0__122_47="${ret_log_ts445_v0}"
    fmt_stream_line__395_v0 "${ret_log_ts445_v0__122_47}" "${bucket_1212}" "${msg_1213}"
    local ret_fmt_stream_line395_v0__122_31="${ret_fmt_stream_line395_v0}"
    file_append__45_v0 "${ret_log_file412_v0__122_17}" "${ret_fmt_stream_line395_v0__122_31}
"
    __status=$?
    fmt_stream_chrome__396_v0 "${bucket_1212}" "${msg_1213}"
    local ret_fmt_stream_chrome396_v0__123_28="${ret_fmt_stream_chrome396_v0}"
    printf '%s
' "${ret_fmt_stream_chrome396_v0__123_28}" >&2
    __status=$?
}

# _helm_inst_log <msg> — the "installer" channel line. Lets the operator
# visually distinguish helm-output from helm-installer-pod-output from
# pod-summary lines. (bash _helm_inst_log.)
# _helm_inst_log(msg: Text)
_helm_inst_log__447_v0() {
    local msg_1211="${1}"
    _helm_channel_log__446_v0 "installer" "${msg_1211}"
}

# _helm_pods_log <msg> — the "pods" channel line. (bash _helm_pods_log.)
# ============================================================================
# _helm_dump_failed_job — a failed Job's events + (best-effort) pod logs.
# ============================================================================
# _helm_dump_failed_job <job> <ns> — when a post-install Job fails, its pod is
# often already deleted (job-controller GC, the chart's hook-delete-policy, or
# Karpenter consolidation), so `kubectl logs <pod>` 404s. This prints what's
# still discoverable AND tells the operator how to capture logs by re-running
# the Job. Stderr-only dim chrome (informational, not an error). (bash
# _helm_dump_failed_job.)
# _helm_dump_failed_job(job: Text, ns: Text)
_helm_dump_failed_job__449_v0() {
    local job_1017="${1}"
    local ns_1018="${2}"
    # Refresh THIS instance's context binding from the saved KUBECTL_CONTEXT
    # (per-importer globals aren't shared; see the KEY DESIGN header). Idempotent.
    helm_kctx_init__430_v0 
    ui_dim_c__361_v0 
    local dim_1019="${ret_ui_dim_c361_v0}"
    ui_reset__355_v0 
    local reset_1020="${ret_ui_reset355_v0}"
    printf '%s  failed job: %s%s
' "${dim_1019}" "${job_1017}" "${reset_1020}" >&2
    __status=$?
    kubectl_ctx_flags__432_v0 
    local kf_1021="${ret_kubectl_ctx_flags432_v0}"
    # Job events (deadline / backoff / image-pull failures). tail -8 keeps the
    # most-recent few (the bash `| tail -8`).
    local command_42
    command_42="$(kubectl ${kf_1021} get events --namespace "${ns_1018}" --field-selector "involvedObject.kind=Job,involvedObject.name=${job_1017}" --sort-by=.lastTimestamp -o custom-columns='LAST:.lastTimestamp,REASON:.reason,MESSAGE:.message' --no-headers 2>/dev/null | tail -8)"
    __status=$?
    local events_1022="${command_42}"
    trim__211_v0 "${events_1022}"
    local ret_trim211_v0__161_8="${ret_trim211_v0}"
    if [ "$([ "_${ret_trim211_v0__161_8}" == "_" ]; echo $?)" != 0 ]; then
        printf '%s    job events:%s
' "${dim_1019}" "${reset_1020}" >&2
        __status=$?
        split_lines__8_v0 "${events_1022}"
        local ret_split_lines8_v0__163_21=("${ret_split_lines8_v0[@]}")
        for line_1023 in "${ret_split_lines8_v0__163_21[@]}"; do
            printf '      %s
' "${line_1023}" >&2
            __status=$?
        done
    fi
    # Pod logs (best-effort — pod is often GC'd). Resolve the Job's pods by
    # label, then tail each one's logs. The jsonpath braces are LITERAL k8s
    # syntax, not Amber interpolation — built as a separate Text with escaped
    # `\{` so the braces survive (gotcha: `{` in `$…$` starts interpolation).
    local pods_jp_1024="jsonpath={range .items[*]}{.metadata.name}{\"\\n\"}{end}"
    local command_45
    command_45="$(kubectl ${kf_1021} get pods --namespace "${ns_1018}" --selector "job-name=${job_1017}" -o "${pods_jp_1024}" 2>/dev/null)"
    __status=$?
    local pods_1025="${command_45}"
    trim__211_v0 "${pods_1025}"
    local ret_trim211_v0__174_8="${ret_trim211_v0}"
    if [ "$([ "_${ret_trim211_v0__174_8}" == "_" ]; echo $?)" != 0 ]; then
        split_lines__8_v0 "${pods_1025}"
        local ret_split_lines8_v0__175_20=("${ret_split_lines8_v0[@]}")
        for pod_1026 in "${ret_split_lines8_v0__175_20[@]}"; do
            trim__211_v0 "${pod_1026}"
            local ret_trim211_v0__176_16="${ret_trim211_v0}"
            if [ "$([ "_${ret_trim211_v0__176_16}" != "_" ]; echo $?)" != 0 ]; then
                continue
            fi
            printf '%s    pod %s logs:%s
' "${dim_1019}" "${pod_1026}" "${reset_1020}" >&2
            __status=$?
            # --all-containers --tail=50; combined stdout+stderr (2>&1) so a
            # logs-unavailable error surfaces inline, each line indented.
            local command_48
            command_48="$(kubectl ${kf_1021} logs "${pod_1026}" --namespace "${ns_1018}" --all-containers --tail=50 2>&1)"
            __status=$?
            local plog_1027="${command_48}"
            split_lines__8_v0 "${plog_1027}"
            local ret_split_lines8_v0__181_25=("${ret_split_lines8_v0[@]}")
            for line_1028 in "${ret_split_lines8_v0__181_25[@]}"; do
                printf '      %s
' "${line_1028}" >&2
                __status=$?
            done
        done
    else
        # Pod was GC'd — tell the operator how to re-run the Job to capture logs.
        printf "%s    pod logs unavailable (pod was GC'd after failure)%s
" "${dim_1019}" "${reset_1020}" >&2
        __status=$?
        printf '%s    re-run the job to capture logs:%s
' "${dim_1019}" "${reset_1020}" >&2
        __status=$?
        printf '%s      kubectl get job %s -n %s -o yaml > /tmp/%s.yaml%s
' "${dim_1019}" "${job_1017}" "${ns_1018}" "${job_1017}" "${reset_1020}" >&2
        __status=$?
        printf '%s      kubectl delete job %s -n %s%s
' "${dim_1019}" "${job_1017}" "${ns_1018}" "${reset_1020}" >&2
        __status=$?
        printf '%s      kubectl create -f /tmp/%s.yaml%s
' "${dim_1019}" "${job_1017}" "${reset_1020}" >&2
        __status=$?
        printf '%s      kubectl logs -f -l job-name=%s -n %s --all-containers%s
' "${dim_1019}" "${job_1017}" "${ns_1018}" "${reset_1020}" >&2
        __status=$?
    fi
}

# ============================================================================
# _helm_explain_failure — the WHY behind a failed / pending-upgrade release.
# ============================================================================
# _helm_explain_failure <release> <ns> — print the WHY behind a failed /
# pending-upgrade release so the operator can decide between rollback,
# uninstall, and "leave it alone" without a second terminal. Surfaces:
# * helm's own DESCRIPTION line (often names the failed hook resource)
# * the failed Job named in the description (via _helm_dump_failed_job)
# * migration-console-0's pod phase + container readiness
# Stderr only, dim color — informational, not an error. (bash _helm_explain_failure.)
# _helm_explain_failure(release: Text, ns: Text)
_helm_explain_failure__450_v0() {
    local release_953="${1}"
    local ns_954="${2}"
    # Refresh this instance's context binding from saved KUBECTL_CONTEXT.
    helm_kctx_init__430_v0 
    ui_dim_c__361_v0 
    local dim_1001="${ret_ui_dim_c361_v0}"
    ui_reset__355_v0 
    local reset_1002="${ret_ui_reset355_v0}"
    helm_ctx_flags__431_v0 
    local hf_1003="${ret_helm_ctx_flags431_v0}"
    kubectl_ctx_flags__432_v0 
    local kf_1004="${ret_kubectl_ctx_flags432_v0}"
    # helm status -o json | jq .info.description. jq has no stdlib equivalent;
    # both helm + jq legitimately fail (no release / no jq) → trust with an ""
    # fallback so a missing description is just an empty desc.
    local command_51
    command_51="$(helm ${hf_1003} status "${release_953}" --namespace "${ns_954}" -o json 2>/dev/null | jq -r '.info.description // empty' 2>/dev/null)"
    __status=$?
    local desc_1005="${command_51}"
    trim__211_v0 "${desc_1005}"
    local ret_trim211_v0__219_8="${ret_trim211_v0}"
    if [ "$([ "_${ret_trim211_v0__219_8}" == "_" ]; echo $?)" != 0 ]; then
        printf '%s  why: %s%s
' "${dim_1001}" "${desc_1005}" "${reset_1002}" >&2
        __status=$?
        # "name: <job>, kind: Job" is the canonical helm failure shape — pull the
        # Job name out and dump it. regex_capture group 1 = the job name.
        regex_capture__218_v0 "${desc_1005}" "name: ([A-Za-z0-9_.-]+), kind: Job" 1
        local job_1016="${ret_regex_capture218_v0}"
        if [ "$([ "_${job_1016}" == "_" ]; echo $?)" != 0 ]; then
            _helm_dump_failed_job__449_v0 "${job_1016}" "${ns_954}"
        fi
    fi
    # migration-console-0 phase + per-container readiness, so a "release failed
    # but pod is Running/Ready" case is visible up front. The jsonpath braces are
    # literal k8s syntax (escaped `\{`), not Amber interpolation.
    local console_jp_1029="jsonpath={.status.phase}|{range .status.containerStatuses[*]}{.ready},{end}"
    local command_52
    command_52="$(kubectl ${kf_1004} get pod migration-console-0 --namespace "${ns_954}" -o "${console_jp_1029}" 2>/dev/null)"
    __status=$?
    local console_1030="${command_52}"
    trim__211_v0 "${console_1030}"
    local ret_trim211_v0__234_8="${ret_trim211_v0}"
    if [ "$([ "_${ret_trim211_v0__234_8}" == "_" ]; echo $?)" != 0 ]; then
        printf '%s  migration-console-0: %s%s
' "${dim_1001}" "${console_1030}" "${reset_1002}" >&2
        __status=$?
    fi
    printf '%s  full diagnostics: migration-assistant diag%s
' "${dim_1001}" "${reset_1002}" >&2
    __status=$?
}

# ============================================================================
# helm_dump_diagnostics — the big on-failure capture.
# ============================================================================
# ---- whitespace field extractors (defined ABOVE their first users: the
# _dump_unhealthy_pods / _dump_stuck_hook_jobs phase/completion checks and
# helm_pods_summary's tally — the tree-shaker keeps only deps textually
# preceding their use across an import boundary). These replace awk's $1/$2/$3
# field extraction over kubectl custom-columns rows (runs of spaces between
# columns). -------------------------------------------------------------------
# _first_field <row> — the first whitespace-delimited token of a row, or "".
# Replaces awk `{print $1}`.
# _second_field <row> — the second whitespace-delimited token, or "". Skip the
# first token, skip the inter-field whitespace, then take up to the next run of
# whitespace. Replaces awk `{print $2}`.
# _third_field <row> — the third whitespace-delimited token (the READY column in
# the pods snapshot), or "". Same scan, advanced past two fields. awk `{print $3}`.
# _dump_unhealthy_pods <ns> — per-pod `describe` for every pod NOT in
# Running/Succeeded. Resolves the name/phase columns, then describes each
# unhealthy one through log_stream (brief on-screen + full content in the log).
# Replaces the bash awk `$2!="Running" && $2!="Succeeded" {print $1}` + the
# `while read … <<<` loop with split_lines + an in-Amber phase check.
# _dump_stuck_hook_jobs <ns> — `describe` any helm-hook Job that has not
# completed (no successful completion). Common culprits: default-helm-installer,
# default-helm-uninstaller. Mirrors the bash awk `$2=="" || $2=="0" {print $1}`
# over NAME,COMPLETIONS.
# helm_dump_diagnostics <release> <ns> — called on helm failure. Captures
# everything the operator (or a log-reading agent) would normally gather by
# hand, each section through log_stream (brief on-screen render + full content
# in the log file):
# * helm status
# * kubectl get pods -o wide
# * kubectl describe of any pod NOT in Running/Succeeded
# * recent events (last 30, by lastTimestamp)
# * kubectl describe of any stuck helm-hook Job
# Every command is best-effort (bash `|| true`); the cmd Texts are composed
# here and run via log_stream (which itself swallows the command's rc). (bash
# helm_dump_diagnostics.)
# ============================================================================
# _helm_inst_dump_pending_pod — a Pending pod's container statuses + events.
# ============================================================================
# _helm_inst_dump_pending_pod <pod> <ns> — when a Job's Pod has been Pending
# past the warn threshold, surface the actual reason. Two streams of evidence:
# * the pod's container statuses (waiting reason — ImagePullBackOff,
# CrashLoopBackOff, ErrImageNeverPull, …)
# * the pod's recent Events (e.g. "Failed to pull image …")
# Both go through log_stream so the operator sees them live AND they land in the
# main log for post-mortem. (bash _helm_inst_dump_pending_pod.)
# ============================================================================
# _helm_dump_install_notes — stream the installation-notes ConfigMap.
# ============================================================================
# _helm_dump_install_notes <release> <ns> — poll for the <release>-installation-
# notes ConfigMap (written by the chart's pre-install Job at the end of its run)
# and stream its all-notes.txt key into the operator log. Bounded by
# INSTALLER_NOTES_TIMEOUT_S (default 60) / INSTALLER_NOTES_POLL_S (default 2).
# 
# Port note: the bash version took an optional parent_pid and bailed when the
# parent went away (it ran inside the backgrounded watcher). In the synchronous
# post-hoc model this runs in the foreground after helm returns, so there is no
# parent to watch — the parent_pid liveness guard is dropped (documented). The
# ConfigMap survives after the Job's pod is GC'd, so this is the durable way to
# recover the per-sub-chart success NOTES. (bash _helm_dump_install_notes.)
# _helm_dump_install_notes(release: Text, ns: Text)
_helm_dump_install_notes__458_v0() {
    local release_1200="${1}"
    local ns_1201="${2}"
    # Refresh this instance's context binding from saved KUBECTL_CONTEXT.
    helm_kctx_init__430_v0 
    local cm_1202="${release_1200}-installation-notes"
    kubectl_ctx_flags__432_v0 
    local kf_1203="${ret_kubectl_ctx_flags432_v0}"
    env_var_get__124_v0 "INSTALLER_NOTES_TIMEOUT_S"
    __status=$?
    if [ "${__status}" != 0 ]; then
        :
    fi
    local timeout_s_1204="${ret_env_var_get124_v0}"
    if [ "$([ "_${timeout_s_1204}" != "_" ]; echo $?)" != 0 ]; then
        timeout_s_1204="60"
    fi
    env_var_get__124_v0 "INSTALLER_NOTES_POLL_S"
    __status=$?
    if [ "${__status}" != 0 ]; then
        :
    fi
    local poll_s_1205="${ret_env_var_get124_v0}"
    if [ "$([ "_${poll_s_1205}" != "_" ]; echo $?)" != 0 ]; then
        poll_s_1205="2"
    fi
    local timeout_1206="${timeout_s_1204}"
    local poll_1207="${poll_s_1205}"
    # Poll for the ConfigMap to exist. Track time by wall clock so we don't have
    # to do float arithmetic on sub-second poll intervals (bash used `date +%s`).
    local command_53
    command_53="$(date +%s)"
    __status=$?
    local started_1208="${command_53}"
    local found_1209=0
    while :
    do
        kubectl ${kf_1203} get configmap "${cm_1202}" --namespace "${ns_1201}" >/dev/null 2>&1
        __status=$?
        if [ "$(( __status == 0 ))" != 0 ]; then
            found_1209=1
            break
        fi
        sleep "${poll_1207}"
        __status=$?
        local command_54
        command_54="$(date +%s)"
        __status=$?
        local now_1210="${command_54}"
        if [ "$(( $(( now_1210 - started_1208 )) >= timeout_1206 ))" != 0 ]; then
            break
        fi
    done
    if [ "$(( ! found_1209 ))" != 0 ]; then
        _helm_inst_log__447_v0 "no ConfigMap/${cm_1202} appeared within ${timeout_1206}s; skipping notes dump"
        ret__helm_dump_install_notes458_v0=''
        return 0
    fi
    _helm_inst_log__447_v0 "streaming installation notes from ConfigMap/${cm_1202}"
    # Pull the all-notes.txt key. The jsonpath escapes the '.' in the key name
    # (data.all-notes\.txt). Falls back to dumping the whole CM if the key is
    # missing. The braces are literal k8s jsonpath syntax (escaped `\{`) and the
    # `\.` survives as a separate Text so both pass through interpolation intact
    # (gotchas 6 + 9).
    local jp_1221="jsonpath={.data.all-notes\\.txt}"
    local command_55
    command_55="$(kubectl ${kf_1203} get configmap "${cm_1202}" --namespace "${ns_1201}" -o "${jp_1221}" 2>/dev/null)"
    __status=$?
    local notes_1222="${command_55}"
    if [ "$([ "_${notes_1222}" != "_" ]; echo $?)" != 0 ]; then
        _helm_inst_log__447_v0 "ConfigMap/${cm_1202} has no all-notes.txt key; falling back to describe"
        log_stream__411_v0 "install-notes" "kubectl ${kf_1203} describe configmap ${cm_1202} --namespace ${ns_1201}"
        ret__helm_dump_install_notes458_v0=''
        return 0
    fi
    # Stream each line through the same install-notes channel so the file log
    # gets per-line timestamps and the operator gets per-line dim chrome — the
    # bash `while read … <<<"$notes"` loop, now split_lines + _helm_channel_log.
    split_lines__8_v0 "${notes_1222}"
    local ret_split_lines8_v0__504_17=("${ret_split_lines8_v0[@]}")
    for line_1239 in "${ret_split_lines8_v0__504_17[@]}"; do
        _helm_channel_log__446_v0 "install-notes" "${line_1239}"
    done
}

# ============================================================================
# pods-watch summary formatter (the one-awk-pass tally, now pure Amber).
# The whitespace field extractors (_first/_second/_third_field) live ABOVE,
# near _dump_unhealthy_pods — their first user — so the tree-shaker keeps them.
# ============================================================================
# helm_pods_summary <snapshot> — build the one-line pods summary the bash
# helm_watch_pods emitted per cycle, from a `kubectl get pods` custom-columns
# snapshot (NAME, PHASE, READY). Replaces the seven-fork awk-+-tr-+-sed tally
# with one pure pass: tallies total/running/pending/failed and collects the
# pending/failed/not-ready name lists. An empty snapshot → "" (caller emits the
# "waiting for pods…" line instead). Pure (returns Text) — directly testable.
# 
# Shape (byte-matched to bash):
# "pods total=N running=R pending=P failed=F [pending=[a,b]] [failed=[c]] [not_ready=[d]]"
# helm_dump_pods <ns> — the SYNCHRONOUS post-hoc pod-status dump that replaces
# the backgrounded helm_watch_pods. Takes one `kubectl get pods` snapshot and
# emits the summary line through the "pods" channel (file + operator stderr),
# or the "waiting for pods…" line when the namespace has no pods yet. Used by
# the install flow after helm returns to give the operator the final pod state.
# diag_world()
diag_world__464_v0() {
    fresh_world__166_v0 
    local w_121="${ret_fresh_world166_v0}"
    mock_install_default__181_v0 
    ret_diag_world464_v0="${w_121}"
    return 0
}

