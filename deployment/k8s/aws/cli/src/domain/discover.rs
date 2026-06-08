//! Read-only environment discovery.
//!
//! Every function is non-destructive and writes its findings into [`State`].
//! `discover_aws` uses the AWS SDK directly; `discover_resources` uses the aws
//! CLI for EKS/CFN list calls (routed through the [`CommandRunner`] seam so
//! tests can stub them).
//!
//! State keys populated: `OS_NAME`, `PKG_MGR`, `AWS_ACCOUNT`, `AWS_REGION`,
//! `AWS_USER_ARN`, `EKS_CLUSTERS`, `CFN_MA_STACKS`.

use crate::runner::CommandRunner;
use crate::state::State;

/// Detect OS name and package manager, writing `OS_NAME` + `PKG_MGR`.
/// The OS is detected from the host; the package manager from what's on PATH
/// (via the runner's `has_command`).
pub fn discover_os<R: CommandRunner>(runner: &R, state: &mut State) {
    let os = detect_os();
    let pm = detect_pkg_mgr(runner, &os);
    state.set("OS_NAME", os);
    state.set("PKG_MGR", pm);
}

/// The host OS name: `linux` / `wsl` / `darwin` / `other`.
pub fn detect_os() -> String {
    if cfg!(target_os = "linux") {
        // WSL detection: /proc/version mentions microsoft.
        if std::fs::read_to_string("/proc/version")
            .map(|v| v.to_lowercase().contains("microsoft"))
            .unwrap_or(false)
        {
            "wsl".to_string()
        } else {
            "linux".to_string()
        }
    } else if cfg!(target_os = "macos") {
        "darwin".to_string()
    } else {
        "other".to_string()
    }
}

fn detect_pkg_mgr<R: CommandRunner>(runner: &R, os: &str) -> String {
    if os == "darwin" && runner.has_command("brew") {
        "brew".into()
    } else if runner.has_command("apt-get") {
        "apt".into()
    } else if runner.has_command("dnf") {
        "dnf".into()
    } else if runner.has_command("yum") {
        "yum".into()
    } else if runner.has_command("brew") {
        "brew".into()
    } else {
        "none".into()
    }
}

/// Resolve AWS identity into `AWS_ACCOUNT` / `AWS_USER_ARN` / `AWS_REGION`.
///
/// Primary path: AWS SDK (`sts:GetCallerIdentity`) — no `aws` CLI binary
/// required. Fallback path: runner-based `aws sts get-caller-identity` (used
/// when the SDK call fails, which covers test environments that inject credentials
/// via [`MockRunner`] stubs). Region priority: state → env vars → SDK config.
pub fn discover_aws<R: CommandRunner>(runner: &R, state: &mut State) -> crate::Result<()> {
    // Region: prefer existing state / env before calling the SDK.
    let mut region = state.get_owned("AWS_REGION", "");
    if region.is_empty() {
        region = std::env::var("AWS_REGION")
            .or_else(|_| std::env::var("AWS_DEFAULT_REGION"))
            .unwrap_or_default();
    }

    // Try the SDK first (no binary required).
    match crate::ecr::get_caller_identity() {
        Ok(id) => {
            if region.is_empty() && !id.region.is_empty() {
                region = id.region;
            }
            state.set("AWS_ACCOUNT", &id.account);
            state.set("AWS_USER_ARN", &id.arn);
            state.set("AWS_REGION", &region);
            return Ok(());
        }
        Err(_) => {
            // SDK path failed (no credentials or no network in test env).
            // Fall through to the runner-based path.
        }
    }

    // Fallback: runner-based path (supports MockRunner in tests, and also
    // covers environments where only `aws` CLI credentials are configured but
    // the SDK can't find them via the standard provider chain).
    if !runner.has_command("aws") {
        return Err(crate::Error::die(
            "AWS credentials not configured. Run `aws configure` or set AWS_PROFILE.",
        ));
    }
    let out = runner.run("aws", &["sts", "get-caller-identity", "--output", "json"]);
    if !out.success() {
        return Err(crate::Error::die(
            "AWS credentials not configured. Run `aws configure` or set AWS_PROFILE.",
        ));
    }
    let v: serde_json::Value = serde_json::from_str(&out.stdout).unwrap_or(serde_json::Value::Null);
    let account = v.get("Account").and_then(|x| x.as_str()).unwrap_or("");
    let arn = v.get("Arn").and_then(|x| x.as_str()).unwrap_or("");
    if region.is_empty() {
        let r = runner.run("aws", &["configure", "get", "region"]);
        if r.success() {
            region = r.trimmed_stdout().to_string();
        }
    }
    state.set("AWS_ACCOUNT", account);
    state.set("AWS_USER_ARN", arn);
    state.set("AWS_REGION", &region);
    Ok(())
}

/// List EKS clusters and MigrationAssistant CFN stacks into `EKS_CLUSTERS` /
/// `CFN_MA_STACKS` (space-separated). Best-effort: a missing region or failing
/// call simply leaves the keys unset.
pub fn discover_resources<R: CommandRunner>(runner: &R, state: &mut State) {
    let region = state.get_owned("AWS_REGION", "");
    if region.is_empty() || !runner.has_command("aws") {
        return;
    }
    let clusters = runner.run(
        "aws",
        &[
            "eks",
            "list-clusters",
            "--region",
            &region,
            "--query",
            "clusters[]",
            "--output",
            "text",
        ],
    );
    let stacks = runner.run(
        "aws",
        &[
            "cloudformation",
            "list-stacks",
            "--region",
            &region,
            "--stack-status-filter",
            "CREATE_COMPLETE",
            "UPDATE_COMPLETE",
            "UPDATE_ROLLBACK_COMPLETE",
            "ROLLBACK_COMPLETE",
            "--query",
            "StackSummaries[?contains(StackName,`MigrationAssistant`)].StackName",
            "--output",
            "text",
        ],
    );
    // `--output text` separates with tabs; normalize to spaces.
    state.set("EKS_CLUSTERS", normalize_ws(clusters.trimmed_stdout()));
    state.set("CFN_MA_STACKS", normalize_ws(stacks.trimmed_stdout()));
}

fn normalize_ws(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runner::MockRunner;

    fn state() -> State {
        State::new(std::env::temp_dir().join("ma-test-discover"))
    }

    // discover_aws tries the SDK first, then falls back to the runner path when
    // the SDK fails (test env has no real credentials). These tests exercise the
    // runner-fallback path via MockRunner stubs.

    #[test]
    fn discover_aws_sets_account_region_arn_via_runner_fallback() {
        // The SDK path will fail in CI (no real creds); the runner fallback fires.
        let r = MockRunner::new().with_command("aws").stub(
            "aws",
            &["sts", "get-caller-identity"],
            0,
            r#"{"UserId":"ABC","Account":"123456789012","Arn":"arn:aws:iam::123456789012:user/alice"}"#,
        );
        let mut s = state();
        s.set("AWS_REGION", "us-east-1");
        discover_aws(&r, &mut s).unwrap();
        assert_eq!(s.get("AWS_ACCOUNT"), Some("123456789012"));
        assert_eq!(
            s.get("AWS_USER_ARN"),
            Some("arn:aws:iam::123456789012:user/alice")
        );
        assert_eq!(s.get("AWS_REGION"), Some("us-east-1"));
    }

    #[test]
    fn discover_aws_fails_when_both_sdk_and_runner_unavailable() {
        // SDK fails (no creds) + no `aws` command on PATH → Err.
        let r = MockRunner::new(); // aws not registered
        let mut s = state();
        assert!(discover_aws(&r, &mut s).is_err());
    }

    #[test]
    fn discover_aws_fails_on_runner_sts_error() {
        let r = MockRunner::new().with_command("aws").stub(
            "aws",
            &["sts", "get-caller-identity"],
            255,
            "",
        );
        let mut s = state();
        assert!(discover_aws(&r, &mut s).is_err());
    }

    #[test]
    fn discover_os_matches_host() {
        let r = MockRunner::new();
        let mut s = state();
        discover_os(&r, &mut s);
        let os = s.get("OS_NAME").unwrap();
        if cfg!(target_os = "macos") {
            assert_eq!(os, "darwin");
        } else if cfg!(target_os = "linux") {
            assert!(os == "linux" || os == "wsl");
        }
    }

    #[test]
    fn discover_resources_normalizes_tabs_to_spaces() {
        let r = MockRunner::new()
            .with_command("aws")
            .stub("aws", &["eks", "list-clusters"], 0, "cluster-a\tcluster-b")
            .stub(
                "aws",
                &["cloudformation", "list-stacks"],
                0,
                "MigrationAssistant-default",
            );
        let mut s = state();
        s.set("AWS_REGION", "us-east-1");
        discover_resources(&r, &mut s);
        assert_eq!(s.get("EKS_CLUSTERS"), Some("cluster-a cluster-b"));
        assert_eq!(s.get("CFN_MA_STACKS"), Some("MigrationAssistant-default"));
    }

    #[test]
    fn discover_resources_skips_without_region() {
        let r = MockRunner::new().with_command("aws");
        let mut s = state();
        discover_resources(&r, &mut s);
        assert_eq!(s.get("EKS_CLUSTERS"), None);
    }
}
