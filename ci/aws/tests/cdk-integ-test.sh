#!/usr/bin/env bash
# CDK (ECS-based) end-to-end tests, in the full-es68 and rfs-default configurations. Deploys an EC2 source cluster and
# the Migration Assistant CDK stacks with test/awsE2ESolutionSetup.sh, then runs the integ tests on the migration
# console over ECS exec. Run ci/aws/shared/cdk-integ-cleanup.sh afterwards, whatever the outcome.
#
# Usage: cdk-integ-test.sh --test full-es68|rfs-default
set -euo pipefail
source "$(dirname "$0")/../shared/lib.sh"

test_name=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --test) test_name="$2"; shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done
case "$test_name" in
    full-es68) base=fes68 source_id=source-single-node-ec2 migration_id=full-migration ;;
    rfs-default) base=rfs source_id=source-single-node-ec2 migration_id=migration-rfs ;;
    *) die "--test must be full-es68 or rfs-default" ;;
esac
stage=$(stage_name "$base")

test_unique_id="integ_full_$(date +%s)_${GITHUB_RUN_ID:-0}"
source_context=sourceJenkinsContext.json migration_context=migrationJenkinsContext.json
state_set CDK_INTEG_STAGE "$stage"
state_set CDK_INTEG_SOURCE_ID "$source_id"
state_set CDK_INTEG_MIGRATION_ID "$migration_id"

cp "$CI_TESTS_DIR/contexts/$test_name-source.json" "$REPO_ROOT/test/$source_context"
if [[ "$test_name" == full-es68 ]]; then
    rfs_b64=$(jq -c . "$CI_TESTS_DIR/contexts/full-es68-rfs-transformers.json" | tr -d '\n' | base64 -w0)
    sed -e "s|<TEST_UNIQUE_ID>|$test_unique_id|g" -e "s|<RFS_TRANSFORMERS_B64>|$rfs_b64|g" \
        "$CI_TESTS_DIR/contexts/$test_name-migration.json" > "$REPO_ROOT/test/$migration_context"
    integ_command="/root/lib/integ_test/integ_test/full_tests.py --source_proxy_alb_endpoint https://alb.migration.$stage.local:9201 --target_proxy_alb_endpoint https://alb.migration.$stage.local:9202 --replayer_group_id logging-group-$test_unique_id"
else
    cp "$CI_TESTS_DIR/contexts/$test_name-migration.json" "$REPO_ROOT/test/$migration_context"
    integ_command="/root/lib/integ_test/integ_test/backfill_tests.py"
fi

cat <<INFO
================================================================
CDK Integration Test: $test_name
Stage:     $stage
Region:    $REGION
================================================================
INFO
aws sts get-caller-identity --query Arn --output text
install_ecs_exec_tools

common_args=(--source-context-file "./$source_context" --migration-context-file "./$migration_context"
             --source-context-id "$source_id" --migration-context-id "$migration_id" --stage "$stage")
cd "$REPO_ROOT/test"

log "Deploying source and migration stacks"
DOCKER_BUILD_RETRIES=3 ./awsE2ESolutionSetup.sh "${common_args[@]}"

log "Running integ tests"
test_dir=/root/lib/integ_test/integ_test
result_file="$test_dir/reports/$test_unique_id/report.xml"
command="pipenv run pytest --log-file=$test_dir/reports/$test_unique_id/pytest.log --junitxml=$result_file $integ_command --unique_id $test_unique_id --stage $stage -s"
./awsRunIntegTests.sh --command "$command" --test-result-file "$result_file" --stage "$stage"
