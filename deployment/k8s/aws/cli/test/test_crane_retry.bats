#!/usr/bin/env bats
# test_crane_retry.bats — _crane_copy_retry exponential-backoff retry.
#
# Mirrors upstream aws-bootstrap.sh's policy: per image, up to N attempts
# (default 5) with exponential backoff. Bail early on unambiguously fatal
# errors (auth / manifest-unknown / repo-not-found).
#
# Tests use CRANE_RETRY_INITIAL_S=0 to keep the suite snappy — the
# important behaviour is the attempt count + the early-bail logic, not
# the wall-clock sleep duration.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  PROJECT_ROOT="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
  load_libs _common.sh ui.sh log.sh state.sh version.sh discover.sh \
            install_tools.sh artifacts.sh wizard.sh cfn.sh crane.sh
  log_init

  # Make retries instant for the test suite.
  export CRANE_RETRY_INITIAL_S=0

  # `sleep` is invoked by the retry loop. Override it to a no-op so the
  # tests don't actually wait.
  cat >"$STUB_DIR/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/sleep"
}

teardown() {
  teardown_isolated_home
}

run_split() {
  local out_file err_file
  out_file=$(mktemp); err_file=$(mktemp)
  set +e
  ( "$@" ) >"$out_file" 2>"$err_file"
  local rc=$?
  set -e
  STDOUT=$(cat "$out_file")
  STDERR=$(cat "$err_file")
  rm -f "$out_file" "$err_file"
  STATUS=$rc
}

# Helper: write a stub `crane` that fails its first <fail> invocations
# then succeeds.
_make_crane_fail_then_pass() {
  local fail="$1"
  cat >"$STUB_DIR/crane" <<EOF
#!/usr/bin/env bash
counter_file="$STUB_DIR/.crane.count"
n=\$(cat "\$counter_file" 2>/dev/null || echo 0)
n=\$((n + 1))
echo "\$n" >"\$counter_file"
if (( n <= $fail )); then
  echo "transient error" >&2
  exit 1
fi
echo "Copied"
exit 0
EOF
  chmod +x "$STUB_DIR/crane"
  : >"$STUB_DIR/.crane.count"
}

# ---------- (1) succeeds on first attempt ----------

@test "_crane_copy_retry succeeds on first try without sleeping" {
  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/crane"

  CRANE_RETRY_ATTEMPTS=5 run_split _crane_copy_retry src dst
  [ "$STATUS" -eq 0 ]
  # Crane was invoked exactly once.
  count=$(grep -c 'STREAM\|crane' "$LOG_FILE" 2>/dev/null || echo 0)
  # No retry warning means no retry happened.
  ! grep -q 'attempt 2/' "$LOG_FILE"
}

# ---------- (2) succeeds after transient failures ----------

@test "_crane_copy_retry succeeds after 2 transient failures" {
  _make_crane_fail_then_pass 2
  CRANE_RETRY_ATTEMPTS=5 run_split _crane_copy_retry quay.io/x:1 r/x:1
  [ "$STATUS" -eq 0 ]
  # Should have logged retries 1/5 and 2/5 then succeeded.
  grep -q 'attempt 1/5 failed' "$LOG_FILE"
  grep -q 'attempt 2/5 failed' "$LOG_FILE"
  grep -q 'succeeded on attempt 3' "$LOG_FILE"
}

@test "_crane_copy_retry exhausts attempts on persistent transient failure" {
  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
echo "transient (always)" >&2
exit 1
EOF
  chmod +x "$STUB_DIR/crane"

  CRANE_RETRY_ATTEMPTS=3 run_split _crane_copy_retry src dst
  [ "$STATUS" -ne 0 ]
  grep -q 'exhausted 3 attempts' "$LOG_FILE"
}

# ---------- (3) bails early on unambiguously fatal errors ----------

@test "_crane_copy_retry does NOT retry on NAME_UNKNOWN (ECR repo missing)" {
  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
echo "Error: POST .../v2/...: NAME_UNKNOWN: The repository does not exist" >&2
exit 1
EOF
  chmod +x "$STUB_DIR/crane"

  CRANE_RETRY_ATTEMPTS=5 run_split _crane_copy_retry src dst
  [ "$STATUS" -ne 0 ]
  grep -q 'ECR repo missing' "$LOG_FILE"
  # Ensure we did NOT log any retry warning.
  ! grep -q 'attempt 2/' "$LOG_FILE"
}

@test "_crane_copy_retry does NOT retry on auth DENIED (token expired)" {
  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
echo "Error: GET .../v2/...: DENIED: Your authorization token has expired. Reauthenticate and try again." >&2
exit 1
EOF
  chmod +x "$STUB_DIR/crane"

  CRANE_RETRY_ATTEMPTS=5 run_split _crane_copy_retry src dst
  [ "$STATUS" -ne 0 ]
  grep -q 'auth failure' "$LOG_FILE"
  ! grep -q 'attempt 2/' "$LOG_FILE"
}

@test "_crane_copy_retry does NOT retry on UNAUTHORIZED" {
  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
echo "Error: GET .../v2/...: UNAUTHORIZED: authentication required" >&2
exit 1
EOF
  chmod +x "$STUB_DIR/crane"

  CRANE_RETRY_ATTEMPTS=5 run_split _crane_copy_retry src dst
  [ "$STATUS" -ne 0 ]
  grep -q 'auth failure' "$LOG_FILE"
  ! grep -q 'attempt 2/' "$LOG_FILE"
}

@test "_crane_copy_retry does NOT retry on MANIFEST_UNKNOWN (image absent upstream)" {
  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
echo "Error: fetching: GET .../manifests/v9.99: MANIFEST_UNKNOWN: manifest unknown" >&2
exit 1
EOF
  chmod +x "$STUB_DIR/crane"

  CRANE_RETRY_ATTEMPTS=5 run_split _crane_copy_retry src dst
  [ "$STATUS" -ne 0 ]
  grep -q 'source manifest does not exist' "$LOG_FILE"
  ! grep -q 'attempt 2/' "$LOG_FILE"
}

# ---------- (4) tunable via env ----------

@test "_crane_copy_retry respects CRANE_RETRY_ATTEMPTS=1 (no retry)" {
  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
echo "transient" >&2
exit 1
EOF
  chmod +x "$STUB_DIR/crane"

  CRANE_RETRY_ATTEMPTS=1 run_split _crane_copy_retry src dst
  [ "$STATUS" -ne 0 ]
  grep -q 'exhausted 1 attempts' "$LOG_FILE"
  ! grep -q 'attempt 2/' "$LOG_FILE"
}
