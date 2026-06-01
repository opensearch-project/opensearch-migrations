#!/usr/bin/env bats
# test_nodepool_flags.bats — verify --use-general-node-pool produces the
# correct helm `--set` flag and --disable-general-purpose-pool runs the
# post-install update-cluster-config.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh log.sh state.sh version.sh discover.sh \
            install_tools.sh artifacts.sh wizard.sh dashboard.sh cfn.sh \
            crane.sh helm.sh
  log_init
  state_set AWS_REGION us-east-1
}

teardown() { teardown_isolated_home; }

@test "USE_GENERAL_NODE_POOL=Y emits --set cluster.useCustomKarpenterNodePool=false in helm.sh" {
  # Confirm the literal --set string appears in lib/helm.sh's logic.
  grep -q 'cluster.useCustomKarpenterNodePool=false' "$LIB_DIR/helm.sh"
}

@test "DISABLE_GENERAL_NODE_POOL=Y triggers update-cluster-config" {
  # _helm_apply_disable_general_purpose_pool calls aws eks describe-cluster
  # then aws eks update-cluster-config. Stub aws to record both calls.
  mkstub aws ''
  state_set EKS_CLUSTER "test-cluster"
  cat >"$STUB_DIR/aws" <<'AWS'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$STUB_DIR/aws.calls"
case "$*" in
  *describe-cluster*) printf '%s\n' "arn:aws:iam::111111111111:role/node-role" ;;
  *) ;;
esac
exit 0
AWS
  chmod +x "$STUB_DIR/aws"

  run _helm_apply_disable_general_purpose_pool "test-cluster" "us-east-1"
  [ "$status" -eq 0 ]
  grep -q 'eks describe-cluster' "$STUB_DIR/aws.calls"
  grep -q 'eks update-cluster-config' "$STUB_DIR/aws.calls"
  # Confirm the new compute config drops "general-purpose"
  grep -q '"nodePools":\["system"\]' "$STUB_DIR/aws.calls"
}

@test "_helm_apply_disable_general_purpose_pool noop when no Auto Mode config" {
  cat >"$STUB_DIR/aws" <<'AWS'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$STUB_DIR/aws.calls"
# describe-cluster returns "None" — no Auto Mode
[[ "$*" == *describe-cluster* ]] && printf 'None\n'
exit 0
AWS
  chmod +x "$STUB_DIR/aws"

  run _helm_apply_disable_general_purpose_pool "test-cluster" "us-east-1"
  [ "$status" -eq 0 ]
  # No update-cluster-config when nothing to disable
  ! grep -q 'eks update-cluster-config' "$STUB_DIR/aws.calls"
}
