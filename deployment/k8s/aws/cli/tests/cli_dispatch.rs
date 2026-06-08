//! Public CLI-surface tests: drive [`cli::dispatch`] the way `main` does and
//! assert the operator-visible contract (exit codes, the Agent preview gate,
//! the resume prompt, cleanup of empty state).
//!
//! `dispatch` resolves its environment from the real process env (`MIGRATE_HOME`
//! and friends), so these tests mutate env vars and therefore must not run
//! concurrently with each other. A single `Mutex` serializes them; each test
//! points `MIGRATE_HOME` at its own tempdir so state never leaks between runs.

use migration_assistant::cli;
use migration_assistant::runner::MockRunner;
use std::sync::Mutex;

/// Serializes the env-mutating dispatch tests (they share process env).
static ENV_LOCK: Mutex<()> = Mutex::new(());

/// Run `dispatch(args)` with `MIGRATE_HOME` pointed at a fresh tempdir and the
/// given extra env vars set for the duration of the call. Returns the exit code.
fn dispatch_isolated(args: &[&str], extra_env: &[(&str, &str)], runner: &MockRunner) -> i32 {
    let _guard = ENV_LOCK.lock().unwrap();
    let home = tempfile::tempdir().unwrap();

    // Snapshot + set the env knobs dispatch reads, then restore afterwards so
    // tests stay independent.
    let keys = [
        "MIGRATE_HOME",
        "MIGRATE_ENABLE_AGENT",
        "MIGRATE_NONINTERACTIVE",
        "STAGE",
    ];
    let saved: Vec<(&str, Option<String>)> =
        keys.iter().map(|k| (*k, std::env::var(k).ok())).collect();
    for k in keys {
        std::env::remove_var(k);
    }
    std::env::set_var("MIGRATE_HOME", home.path());
    for (k, v) in extra_env {
        std::env::set_var(k, v);
    }

    let owned: Vec<String> = args.iter().map(|s| s.to_string()).collect();
    let code = cli::dispatch(&owned, runner);

    for (k, v) in saved {
        match v {
            Some(val) => std::env::set_var(k, val),
            None => std::env::remove_var(k),
        }
    }
    code
}

#[test]
fn unknown_command_exits_64() {
    let code = dispatch_isolated(&["notacommand"], &[], &MockRunner::new());
    assert_eq!(code, 64);
}

#[test]
fn version_and_help_exit_zero() {
    assert_eq!(dispatch_isolated(&["version"], &[], &MockRunner::new()), 0);
    assert_eq!(dispatch_isolated(&["help"], &[], &MockRunner::new()), 0);
}

#[test]
fn help_text_describes_the_surface() {
    let h = cli::help_text();
    assert!(h.contains("OpenSearch Migration Assistant CLI"));
    assert!(h.contains("--switch"));
    assert!(h.contains("Version:"));
    // Every subcommand is documented.
    for sub in [
        "resume",
        "console",
        "agent",
        "diag",
        "clear",
        "cleanup",
        "dashboard",
        "pack",
        "version",
        "help",
    ] {
        assert!(h.contains(sub), "help missing subcommand: {sub}");
    }
}

#[test]
fn agent_mode_is_gated_off_by_default() {
    // No MIGRATE_ENABLE_AGENT -> `--mode Agent` is rejected (die, exit 1).
    let code = dispatch_isolated(
        &["resume", "--mode", "Agent", "-y"],
        &[],
        &MockRunner::new(),
    );
    assert_eq!(code, 1);
}

#[test]
fn agent_mode_is_accepted_when_gate_is_on() {
    // With the gate on, `--mode Agent` is allowed (no AWS work happens because
    // the Agent path just prints handoff guidance).
    let code = dispatch_isolated(
        &["resume", "--mode", "Agent", "-y"],
        &[("MIGRATE_ENABLE_AGENT", "1")],
        &MockRunner::new(),
    );
    assert_eq!(code, 0);
}

#[test]
fn cleanup_with_empty_state_exits_zero() {
    // Nothing deployed for this stage -> friendly no-op, exit 0.
    let code = dispatch_isolated(
        &["cleanup", "--stage", "nope", "-y"],
        &[],
        &MockRunner::new(),
    );
    assert_eq!(code, 0);
}

#[test]
fn console_without_a_deployed_stage_errors() {
    // No state -> "no deployed stage found" (die, exit 1).
    let code = dispatch_isolated(&["console", "--stage", "nope"], &[], &MockRunner::new());
    assert_eq!(code, 1);
}

/// End-to-end headless deploy (the regression guard for "prepare but don't
/// execute"): drive the full Jenkins-style invocation through `dispatch` with
/// every external command stubbed, and assert the deploy pipeline ACTUALLY
/// runs `aws cloudformation deploy` and `helm upgrade --install` — not just the
/// wizard. This is the `--version` (download) lane so no gradle/build is needed.
#[test]
fn headless_deploy_runs_cfn_and_helm() {
    // Pre-create the template the resolver downloads (curl is mocked).
    let tmpl = std::env::temp_dir().join(format!("ma-template-{}.json", std::process::id()));
    std::fs::write(&tmpl, "{}").unwrap();
    // Pre-create the chart tarball the resolver downloads.
    let chart = std::env::temp_dir().join("migration-assistant-2.9.0.tgz");
    std::fs::write(&chart, "x").unwrap();

    let runner = MockRunner::new()
        .with_command("aws")
        .with_command("curl")
        .with_command("helm")
        .with_command("kubectl")
        // discovery
        .stub("aws", &["sts", "get-caller-identity"], 0, r#"{"Account":"111122223333","Arn":"arn:aws:iam::111122223333:role/x"}"#)
        .stub("aws", &["configure", "get", "region"], 0, "us-east-1")
        .stub("aws", &["eks", "list-clusters"], 0, "{}")
        .stub("aws", &["cloudformation", "list-stacks"], 0, "{}")
        // artifact downloads
        .stub("curl", &["-o"], 0, "")
        // CFN exports query (kubeconfig/helm) — MORE SPECIFIC, registered first
        // so first-match-wins returns the export blob, not the health status.
        .stub(
            "aws",
            &["describe-stacks", "Outputs"],
            0,
            "MigrationsExportString\texport MIGRATIONS_EKS_CLUSTER_NAME=migration-eks-x; export MIGRATIONS_ECR_REGISTRY=111122223333.dkr.ecr.us-east-1.amazonaws.com/migration-ecr; export SNAPSHOT_ROLE=arn:aws:iam::111122223333:role/snap",
        )
        // CFN health check: stack absent -> deploy
        .stub("aws", &["describe-stacks", "StackStatus"], 0, "DOES_NOT_EXIST")
        // CFN deploy now runs via `bash -c <script>` (aws deploy + event tailer).
        .stub("bash", &["cloudformation deploy"], 0, "")
        .stub("aws", &["eks", "update-kubeconfig"], 0, "")
        .stub("aws", &["associate-access-policy"], 0, "")
        .stub("aws", &["describe-access-entry"], 0, "")
        .stub("kubectl", &["config", "use-context"], 0, "")
        .stub("kubectl", &["get", "namespace"], 0, "")
        .stub("kubectl", &["wait"], 0, "")
        .stub("helm", &["status"], 1, "")
        // helm install now runs via `bash -c <script>` (helm --wait + a namespace
        // pod/event watcher); the direct `helm upgrade --install` stub no longer
        // (MockRunner default-success covers it).
        .stub("bash", &["upgrade", "--install"], 0, "");

    let code = dispatch_isolated(
        &[
            "--non-interactive",
            "--deploy-create-vpc-cfn",
            "--stack-name",
            "MA-test-stack",
            "--stage",
            "ci",
            "--version",
            "2.9.0",
            "--use-public-images",
            "--skip-console-exec",
            "--skip-setting-k8s-context",
            "--kubectl-context",
            "migration-eks-ci",
            "--region",
            "us-east-1",
            "--eks-access-principal-arn",
            "arn:aws:iam::111122223333:role/JenkinsDeploymentRole",
        ],
        &[],
        &runner,
    );

    assert_eq!(code, 0, "headless deploy should exit 0");
    // The load-bearing assertions: the stack was actually deployed under the
    // passed --stack-name, and helm actually installed. (These would FAIL
    // against the old prepare-only manual_path.)
    let deploy = runner
        .calls_to("bash")
        .into_iter()
        .find(|c| c.joined().contains("cloudformation deploy"))
        .expect("expected `aws cloudformation deploy` (via bash) to run");
    assert!(deploy.joined().contains("STACK=\"MA-test-stack\""));
    assert!(runner.any_call_contains("eks update-kubeconfig"));
    assert!(runner.any_call_contains("associate-access-policy"));
    // helm install ran via the shell wrapper (argv embedded shell-quoted).
    let helm = runner
        .calls_to("bash")
        .into_iter()
        .find(|c| c.joined().contains("upgrade") && c.joined().contains("--install"))
        .expect("expected `helm upgrade --install` (via bash) to run");
    assert!(helm.joined().contains("helm"));

    let _ = std::fs::remove_file(&tmpl);
    let _ = std::fs::remove_file(&chart);
}
