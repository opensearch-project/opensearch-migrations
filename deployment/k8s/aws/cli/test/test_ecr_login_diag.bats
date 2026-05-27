#!/usr/bin/env bats
# test_ecr_login_diag.bats — verify _ecr_login surfaces each underlying
# failure reason instead of the previous catch-all "ECR login failed".
#
# This is a regression guard for an actual operator-facing failure:
# > error: ECR login failed; check AWS credentials and region
# …with no other output. The aws CLI's actual error was buried in a
# log file under STAGE_DIR; the operator had no way to see it without
# digging.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  PROJECT_ROOT="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
  load_libs _common.sh ui.sh log.sh state.sh version.sh discover.sh \
            install_tools.sh artifacts.sh wizard.sh cfn.sh crane.sh
  log_init
}

teardown() {
  teardown_isolated_home
}

# Capture stdout + stderr separately, tolerate non-zero exit (so bats's
# inherited `set -e` doesn't abort the test before assertions run).
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

# ---------- happy path ----------

@test "_ecr_login succeeds when both aws and crane succeed" {
  cat >"$STUB_DIR/aws" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"ecr get-login-password"*) printf 'fake-password' ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/aws"

  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/crane"

  run_split _ecr_login "629003556176.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-ma" us-east-1
  [ "$STATUS" -eq 0 ]
}

# ---------- aws failures: each surfaces its own error ----------

@test "_ecr_login surfaces 'You must specify a region' when aws complains" {
  cat >"$STUB_DIR/aws" <<'EOF'
#!/usr/bin/env bash
echo "You must specify a region. You can also configure your region by running \"aws configure\"." >&2
exit 1
EOF
  chmod +x "$STUB_DIR/aws"
  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
echo "should not be called"
exit 0
EOF
  chmod +x "$STUB_DIR/crane"

  run_split _ecr_login "629003556176.dkr.ecr.us-east-1.amazonaws.com/x" us-east-1
  [ "$STATUS" -ne 0 ]
  [[ "$STDERR" == *"aws ecr get-login-password failed"* ]]
  [[ "$STDERR" == *"You must specify a region"* ]]
  # The hint should mention region/registry alignment.
  [[ "$STDERR" == *"region"* ]]
  # crane was NOT invoked.
  ! grep -q 'crane auth login' "$LOG_FILE"
}

@test "_ecr_login surfaces AccessDenied when IAM principal lacks ecr:GetAuthorizationToken" {
  cat >"$STUB_DIR/aws" <<'EOF'
#!/usr/bin/env bash
echo "An error occurred (AccessDeniedException) when calling the GetAuthorizationToken operation: User: arn:aws:iam::123:user/x is not authorized to perform: ecr:GetAuthorizationToken" >&2
exit 254
EOF
  chmod +x "$STUB_DIR/aws"
  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/crane"

  run_split _ecr_login "r.example.com" us-east-1
  [ "$STATUS" -ne 0 ]
  [[ "$STDERR" == *"AccessDeniedException"* ]]
  [[ "$STDERR" == *"GetAuthorizationToken"* ]]
}

@test "_ecr_login fails when aws prints empty password (rare but possible)" {
  cat >"$STUB_DIR/aws" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/aws"
  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/crane"

  run_split _ecr_login "r.example.com" us-east-1
  [ "$STATUS" -ne 0 ]
  [[ "$STDERR" == *"aws ecr get-login-password failed"* ]]
}

# ---------- crane failures: surfaced separately ----------

@test "_ecr_login surfaces crane errors after aws succeeds" {
  cat >"$STUB_DIR/aws" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"ecr get-login-password"*) printf 'pw' ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/aws"
  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
echo "Error: GET https://wronghost.example.com/v2/: dial tcp: lookup wronghost.example.com: no such host" >&2
exit 1
EOF
  chmod +x "$STUB_DIR/crane"

  run_split _ecr_login "wronghost.example.com" us-east-1
  [ "$STATUS" -ne 0 ]
  [[ "$STDERR" == *"crane auth login failed"* ]]
  [[ "$STDERR" == *"no such host"* ]]
  [[ "$STDERR" == *"wronghost.example.com"* ]]
}

@test "_ecr_login strips /repo path from registry before passing to crane" {
  # The registry CFN publishes is e.g. "<acct>.dkr.ecr.<region>.amazonaws.com/migration-ecr-<stage>"
  # but `crane auth login` wants the host alone.
  local calls_file="$STUB_DIR/.crane-calls"
  cat >"$STUB_DIR/aws" <<'EOF'
#!/usr/bin/env bash
printf 'pw'
EOF
  chmod +x "$STUB_DIR/aws"
  cat >"$STUB_DIR/crane" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"$calls_file"
exit 0
EOF
  chmod +x "$STUB_DIR/crane"
  : >"$calls_file"

  run_split _ecr_login "629003556176.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-ma-us-east-1" us-east-1

  # The last positional arg passed to `crane auth login` is the host alone —
  # no `/migration-ecr-…` suffix.
  grep -qE '^auth login --username AWS --password-stdin 629003556176\.dkr\.ecr\.us-east-1\.amazonaws\.com$' "$calls_file"
  ! grep -q '/migration-ecr-' "$calls_file"
}

# ---------- preflight checks ----------

@test "_ecr_login fails clearly when crane is not on PATH" {
  rm -f "$STUB_DIR/crane"
  cat >"$STUB_DIR/aws" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/aws"

  # Force a PATH that only has our stub dir + a couple basics; ensure crane
  # is genuinely missing.
  PATH="$STUB_DIR:/usr/bin:/bin" run_split _ecr_login "r.example.com" us-east-1
  [ "$STATUS" -ne 0 ]
  [[ "$STDERR" == *"crane not on PATH"* ]]
  # And the hint about brew install / --use-public-images.
  [[ "$STDERR" == *"--use-public-images"* ]]
}

@test "_ecr_login fails clearly when aws is not on PATH" {
  rm -f "$STUB_DIR/aws"
  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/crane"

  PATH="$STUB_DIR:/usr/bin:/bin" run_split _ecr_login "r.example.com" us-east-1
  [ "$STATUS" -ne 0 ]
  [[ "$STDERR" == *"aws CLI not on PATH"* ]]
}

# ---------- log-file capture ----------

@test "_ecr_login also refreshes public.ecr.aws auth when ecr-public token is available" {
  # Regression for: copies from public.ecr.aws/* failed with
  #   "DENIED: Your authorization token has expired. Reauthenticate and try again."
  # Cause: stale entry in ~/.docker/config.json. Fix: refresh the
  # public.ecr.aws auth as part of _ecr_login.
  local crane_calls="$STUB_DIR/.crane-calls"
  : >"$crane_calls"
  cat >"$STUB_DIR/aws" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"ecr get-login-password"*)         printf 'pw-private' ;;
  *"ecr-public get-login-password"*)  printf 'pw-public'  ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/aws"
  cat >"$STUB_DIR/crane" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"$crane_calls"
exit 0
EOF
  chmod +x "$STUB_DIR/crane"

  run_split _ecr_login "629003556176.dkr.ecr.us-east-1.amazonaws.com/x" us-east-1
  [ "$STATUS" -eq 0 ]

  # crane should have been called twice: once for the private host, once
  # for public.ecr.aws.
  grep -q '629003556176\.dkr\.ecr\.us-east-1\.amazonaws\.com$' "$crane_calls"
  grep -q 'public\.ecr\.aws$' "$crane_calls"
}

@test "_ecr_login keeps going when ecr-public auth is unavailable" {
  # Some accounts can't get ecr-public tokens. The private auth still
  # works; we should log a warning but return 0 so the operator can
  # try unauthenticated public.ecr.aws reads.
  cat >"$STUB_DIR/aws" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"ecr get-login-password"*)         printf 'pw-private' ;;
  *"ecr-public get-login-password"*)  echo "AccessDenied" >&2; exit 1 ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/aws"
  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/crane"

  run_split _ecr_login "r.example.com" us-east-1
  [ "$STATUS" -eq 0 ]
  # Either path is fine: empty token OR auth failed are both tolerated.
  grep -qE '(public\.ecr\.aws get-login-password returned empty|public\.ecr\.aws auth failed)' "$LOG_FILE"
}

@test "_ecr_login writes detailed errors to LOG_FILE for post-mortem" {
  cat >"$STUB_DIR/aws" <<'EOF'
#!/usr/bin/env bash
printf 'detailed aws stderr line one\ndetailed aws stderr line two\n' >&2
exit 1
EOF
  chmod +x "$STUB_DIR/aws"
  cat >"$STUB_DIR/crane" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/crane"

  run_split _ecr_login "r.example.com" us-east-1

  grep -q 'aws-ecr: detailed aws stderr line one' "$LOG_FILE"
  grep -q 'aws-ecr: detailed aws stderr line two' "$LOG_FILE"
}
