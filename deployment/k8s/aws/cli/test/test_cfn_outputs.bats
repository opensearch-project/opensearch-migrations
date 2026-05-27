#!/usr/bin/env bats
# test_cfn_outputs.bats — cfn_outputs / _cfn_extract_exports / _cfn_pick.
#
# Bug history (caught in production):
#   helm.sh did `cfn_outputs … | awk '/EKSClusterName/'` but the real
#   opensearch-migrations stack publishes a single output called
#   MigrationsExportString — a long bash `export VAR=…; export …;` blob.
#   No EKSClusterName key ever existed → helm step died with
#   "could not read EKSClusterName from CFN outputs".
#
# These tests stub `aws cloudformation describe-stacks` with the exact
# real-world payload (sanitized) and assert that:
#   1. _cfn_extract_exports parses every export into a flat KEY=VALUE
#   2. cfn_outputs emits BOTH raw OutputKey/Value pairs AND every export
#   3. _cfn_pick returns the first resolved key (key fallback chain)
#   4. helm.sh's lookup chain (MIGRATIONS_EKS_CLUSTER_NAME → EKSClusterName)
#      finds the cluster name in the real shape
#   5. crane.sh's lookup chain (MIGRATIONS_ECR_REGISTRY → ECRRegistry)
#      finds the ECR registry in the real shape

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh log.sh state.sh version.sh discover.sh \
            install_tools.sh artifacts.sh wizard.sh cfn.sh
  log_init
}

teardown() {
  teardown_isolated_home
}

# Sanitized copy of the user's real stack output. The real `aws ... --query
# 'Stacks[0].Outputs[].[OutputKey,OutputValue]' --output text` emits TWO
# tab-separated columns: <OutputKey>\t<OutputValue>. The OutputValue itself
# is one big string of bash `export VAR=…; export …;` clauses.
__REAL_PAYLOAD='MigrationsExportString\texport MIGRATIONS_APP_REGISTRY_ARN=arn:aws:servicecatalog:us-east-1:629003556176:/applications/0152ij6laz8tjhttvf5rg0jrql; export MIGRATIONS_USER_AGENT=AwsSolution/SO0290/Unknown; export MIGRATIONS_EKS_CLUSTER_NAME=migration-eks-cluster-default-us-east-1; export MIGRATIONS_ECR_REGISTRY=629003556176.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-default-us-east-1; export AWS_ACCOUNT=629003556176; export AWS_CFN_REGION=us-east-1; export VPC_ID=vpc-0bc345db0dee70e9e; export EKS_CLUSTER_SECURITY_GROUP=sg-07cc74efd34551fb1; export SNAPSHOT_ROLE=arn:aws:iam::629003556176:role/migration-eks-cluster-default-us-east-1-snapshot-role; export STAGE=default'

stub_aws_describe_stacks_with_real_payload() {
  # Build an aws stub that emits the real OutputKey/OutputValue tab-separated
  # row when describe-stacks is invoked.
  cat >"$STUB_DIR/aws" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"describe-stacks"*)
    # The aws CLI emits Description<TAB>ExportName<TAB>OutputKey<TAB>OutputValue
    # — match that real layout by passing four \\t-joined fields.
    printf '%b\n' "$__REAL_PAYLOAD"
    ;;
  *) printf '' ;;
esac
EOF
  chmod +x "$STUB_DIR/aws"
}

# ---------- _cfn_extract_exports ----------

@test "_cfn_extract_exports parses semicolon-joined exports into KEY=VALUE" {
  out=$(printf '%s' 'export A=one; export B=two; export C=three' \
        | _cfn_extract_exports)
  [[ "$out" == *"A=one"* ]]
  [[ "$out" == *"B=two"* ]]
  [[ "$out" == *"C=three"* ]]
}

@test "_cfn_extract_exports preserves '=' inside values (URLs, ARNs)" {
  out=$(printf '%s' 'export ARN=arn:aws:iam::1:role/x=y; export URL=https://h/p?q=r' \
        | _cfn_extract_exports)
  echo "$out" | grep -qx 'ARN=arn:aws:iam::1:role/x=y'
  echo "$out" | grep -qx 'URL=https://h/p?q=r'
}

@test "_cfn_extract_exports tolerates leading whitespace and missing 'export '" {
  out=$(printf '%s' '   export A=1;   B=2; export C=3' | _cfn_extract_exports)
  [[ "$out" == *"A=1"* ]]
  [[ "$out" == *"B=2"* ]]
  [[ "$out" == *"C=3"* ]]
}

@test "_cfn_extract_exports drops malformed entries silently" {
  out=$(printf '%s' '   ; ; bogus line; export OK=yes; ' | _cfn_extract_exports)
  [[ "$out" == *"OK=yes"* ]]
  # Bogus line ("bogus line") should NOT appear as a KEY=VALUE.
  ! echo "$out" | grep -qE '^bogus'
}

# ---------- cfn_outputs full-cycle with the real payload ----------

@test "cfn_outputs emits the raw OutputKey AND every embedded export" {
  stub_aws_describe_stacks_with_real_payload

  out=$(cfn_outputs "MigrationAssistant-default" "us-east-1")

  # Raw OutputKey/Value still emitted (so older callers don't regress).
  echo "$out" | grep -q '^MigrationsExportString='

  # Every important export resolved into a flat KEY=VALUE line.
  echo "$out" | grep -qx 'MIGRATIONS_EKS_CLUSTER_NAME=migration-eks-cluster-default-us-east-1'
  echo "$out" | grep -qx 'MIGRATIONS_ECR_REGISTRY=629003556176.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-default-us-east-1'
  echo "$out" | grep -qx 'AWS_ACCOUNT=629003556176'
  echo "$out" | grep -qx 'AWS_CFN_REGION=us-east-1'
  echo "$out" | grep -qx 'VPC_ID=vpc-0bc345db0dee70e9e'
  echo "$out" | grep -qx 'STAGE=default'
}

@test "cfn_output_value retrieves a single named export" {
  stub_aws_describe_stacks_with_real_payload

  v=$(cfn_output_value "MigrationAssistant-default" "us-east-1" \
        MIGRATIONS_EKS_CLUSTER_NAME)
  [ "$v" = "migration-eks-cluster-default-us-east-1" ]

  v=$(cfn_output_value "MigrationAssistant-default" "us-east-1" \
        MIGRATIONS_ECR_REGISTRY)
  [ "$v" = "629003556176.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-default-us-east-1" ]

  v=$(cfn_output_value "MigrationAssistant-default" "us-east-1" \
        DOES_NOT_EXIST)
  [ -z "$v" ]
}

# ---------- _cfn_pick fallback chain ----------

@test "_cfn_pick picks the first key that resolves" {
  outputs=$(printf '%s\n' \
    "MIGRATIONS_EKS_CLUSTER_NAME=cluster-A" \
    "EKSClusterName=cluster-B")

  v=$(_cfn_pick "$outputs" MIGRATIONS_EKS_CLUSTER_NAME EKSClusterName)
  [ "$v" = "cluster-A" ]

  # Reverse the order in the fallback chain — second key wins because
  # the first isn't present:
  outputs=$(printf '%s\n' "EKSClusterName=cluster-B")
  v=$(_cfn_pick "$outputs" MIGRATIONS_EKS_CLUSTER_NAME EKSClusterName)
  [ "$v" = "cluster-B" ]
}

@test "_cfn_pick returns nothing when no key matches" {
  outputs=$(printf '%s\n' "OTHER=foo")
  v=$(_cfn_pick "$outputs" A B C)
  [ -z "$v" ]
}

# ---------- end-to-end: helm/crane lookup chains against the real shape ----------

@test "helm lookup chain finds the cluster name in real CFN payload" {
  stub_aws_describe_stacks_with_real_payload

  outputs=$(cfn_outputs "MigrationAssistant-default" "us-east-1")

  # The exact expression helm.sh uses:
  cluster_name=$(_cfn_pick "$outputs" MIGRATIONS_EKS_CLUSTER_NAME EKSClusterName)
  [ "$cluster_name" = "migration-eks-cluster-default-us-east-1" ]
}

@test "crane lookup chain finds the ECR registry in real CFN payload" {
  stub_aws_describe_stacks_with_real_payload

  outputs=$(cfn_outputs "MigrationAssistant-default" "us-east-1")

  registry=$(_cfn_pick "$outputs" MIGRATIONS_ECR_REGISTRY ECRRegistry)
  [ "$registry" = "629003556176.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-default-us-east-1" ]
}

# ---------- regression: empty payload doesn't crash ----------

@test "cfn_outputs handles empty AWS response without error" {
  cat >"$STUB_DIR/aws" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/aws"

  run cfn_outputs "Stack" "region"
  [ "$status" -eq 0 ]
  [ -z "$output" ]
}
