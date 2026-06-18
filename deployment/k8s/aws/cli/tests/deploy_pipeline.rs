//! End-to-end deploy-pipeline tests.
//!
//! These drive the orchestrator ([`app::App`]) through a full Manual deploy
//! against a scripted [`MockRunner`] — no AWS, no network — and assert on the
//! command sequence and the resulting state transitions. This is the
//! integration-level counterpart to the per-module unit tests: it exercises the
//! modules together the way a real `migration-assistant` run does.

use migration_assistant::app::App;
use migration_assistant::config::Env;
use migration_assistant::runner::MockRunner;

/// A realistic CFN `describe-stacks --output text` payload: the single
/// `MigrationsExportString` output whose value is a blob of `export VAR=...`
/// clauses, which the CLI expands into flat keys.
const STACK_OUTPUTS: &str = "MigrationsExportString\texport MIGRATIONS_EKS_CLUSTER_NAME=migration-eks-cluster-prod-us-east-1; \
export MIGRATIONS_ECR_REGISTRY=111122223333.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-prod-us-east-1; \
export SNAPSHOT_ROLE=arn:aws:iam::111122223333:role/snap; export STAGE=prod";

/// Build an `Env` rooted at a temp state dir, non-interactive (CI-style).
fn temp_env(home: &std::path::Path, stage: &str) -> Env {
    Env {
        home: home.to_path_buf(),
        stage: stage.to_string(),
        non_interactive: true,
        verbose: false,
        enable_agent: false,
        preview_ack: true,
    }
}

/// A mock with all the deploy commands stubbed for a clean first install.
fn happy_deploy_runner() -> MockRunner {
    MockRunner::new()
        .with_command("aws")
        .with_command("kubectl")
        .with_command("helm")
        .with_command("bash")
        // Stack doesn't exist yet -> deploy runs.
        .stub("aws", &["describe-stacks"], 0, STACK_OUTPUTS)
        .stub("aws", &["cloudformation", "deploy"], 0, "")
        .stub("aws", &["eks", "update-kubeconfig"], 0, "")
        .stub("helm", &["status"], 1, "") // release absent
        .stub("helm", &["upgrade", "--install"], 0, "")
        .stub("kubectl", &["get", "namespace"], 0, "")
        .stub("kubectl", &["wait"], 0, "")
}

#[test]
fn full_manual_pipeline_runs_each_phase_in_order_and_records_last_step() {
    let home = tempfile::tempdir().unwrap();
    let runner = happy_deploy_runner();
    let mut app = App::load(temp_env(home.path(), "prod"), &runner).unwrap();

    // Seed the wizard inputs the way the dispatcher would after the wizard.
    app.state.set("AWS_REGION", "us-east-1");
    app.state.set("AWS_ACCOUNT", "111122223333");
    app.state.set("STAGE_NAME", "prod");
    app.state.set("MA_VERSION", "3.2.1");
    app.state.set("MIRROR_IMAGES", "N");

    // 1. CFN deploy.
    app.cfn_deploy_or_skip("/tmp/template.json").unwrap();
    assert_eq!(app.state.resumable_step(), "cfn_done");

    // 2. kubeconfig — reads the cluster name out of the CFN outputs.
    let ctx = app.kubeconfig_setup().unwrap();
    assert_eq!(ctx, "migration-eks-cluster-prod-us-east-1");

    // 3. Image mirror — skipped (MIRROR_IMAGES=N).
    let values = app.mirror_images_and_charts().unwrap();
    assert!(values.is_empty());

    // 4. helm install + readiness wait.
    app.helm_install_or_upgrade("/charts/ma.tgz", &["/values.yaml".to_string()])
        .unwrap();
    assert_eq!(app.state.resumable_step(), "helm_done");

    // The command stream went out in deploy order.
    let aws = runner.calls_to("aws");
    let pos = |needle: &str| aws.iter().position(|c| c.joined().contains(needle));
    assert!(pos("cloudformation deploy") < pos("eks update-kubeconfig"));

    // Helm install was bound to the discovered kube context and used the
    // mirrored registry + snapshot role pulled from the CFN outputs. The install
    // now runs through a `bash -c <script>` wrapper (the script streams namespace
    // pods/events while `helm --wait` blocks), so the helm argv is embedded
    // shell-quoted; assert against the script with quotes stripped.
    let helm_bash = runner
        .calls_to("bash")
        .into_iter()
        .find(|c| c.joined().contains("upgrade") && c.joined().contains("--install"))
        .expect("expected the helm install to run via a bash wrapper");
    let helm_script = helm_bash.joined().replace('\'', "");
    assert!(helm_script.contains("--kube-context migration-eks-cluster-prod-us-east-1"));
    // MIRROR_IMAGES=N → public ECR image flags.
    assert!(runner.any_call_contains(
        "images.captureProxy.repository=public.ecr.aws/opensearchproject/opensearch-migrations-traffic-capture-proxy"
    ));
    assert!(runner.any_call_contains("images.captureProxy.tag=3.2.1"));
    assert!(runner.any_call_contains(
        "defaultBucketConfiguration.snapshotRoleArn=arn:aws:iam::111122223333:role/snap"
    ));
    assert!(runner.any_call_contains("pod/migration-console-0"));

    // State persisted to disk across the run.
    let state_json = home.path().join("prod/state.json");
    assert!(state_json.is_file());
    let v: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(state_json).unwrap()).unwrap();
    assert_eq!(v["last_step"], "helm_done");
    assert_eq!(v["EKS_CLUSTER"], "migration-eks-cluster-prod-us-east-1");
}

#[test]
fn healthy_stack_skips_cfn_deploy_but_still_advances() {
    let home = tempfile::tempdir().unwrap();
    let runner = MockRunner::new().with_command("aws").stub(
        "aws",
        &["describe-stacks"],
        0,
        "CREATE_COMPLETE",
    );
    let mut app = App::load(temp_env(home.path(), "prod"), &runner).unwrap();
    app.state.set("AWS_REGION", "us-east-1");
    app.state.set("STAGE_NAME", "prod");

    app.cfn_deploy_or_skip("/tmp/template.json").unwrap();

    assert_eq!(app.state.resumable_step(), "cfn_done");
    assert!(runner.any_call_contains("describe-stacks"));
    assert!(!runner.any_call_contains("cloudformation deploy"));
}

#[test]
fn mirror_disabled_skips_entirely() {
    let home = tempfile::tempdir().unwrap();
    let runner = MockRunner::new();
    let mut app = App::load(temp_env(home.path(), "prod"), &runner).unwrap();
    app.state.set("MIRROR_IMAGES", "N");

    let values = app.mirror_images_and_charts().unwrap();
    assert!(values.is_empty());
}

#[test]
fn import_vpc_deploy_requires_vpc_and_subnet_ids() {
    let home = tempfile::tempdir().unwrap();
    let runner = MockRunner::new().with_command("aws").stub(
        "aws",
        &["describe-stacks"],
        0,
        "DOES_NOT_EXIST",
    );
    let mut app = App::load(temp_env(home.path(), "prod"), &runner).unwrap();
    app.state.set("AWS_REGION", "us-east-1");
    app.state.set("CFN_TEMPLATE_VARIANT", "import-vpc");

    let err = app.cfn_deploy_or_skip("/tmp/t.json").unwrap_err();
    assert!(err.message.contains("--vpc-id"), "got: {}", err.message);
}
