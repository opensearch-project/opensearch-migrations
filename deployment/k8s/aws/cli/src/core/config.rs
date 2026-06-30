//! Run configuration: environment resolution and the deploy-flag → state map.
//!
//!   * [`Env`] — the process-environment knobs (`MIGRATE_HOME`, `STAGE`,
//!     `MIGRATE_NONINTERACTIVE`, `MIGRATE_ENABLE_AGENT`, …) with defaults.
//!   * [`apply_deploy_flags`] — maps CLI flags onto `state.env` keys. Keeping
//!     it as a pure function over a [`State`](crate::state::State) makes the
//!     whole flag surface testable.

use crate::state::State;
use std::path::PathBuf;

/// Process-environment configuration, resolved once at startup.
#[derive(Debug, Clone)]
pub struct Env {
    /// State root. Default: `$PWD/migration-assistant-workspace` (per-project),
    /// overridable via `MIGRATE_HOME`.
    pub home: PathBuf,
    /// Active stage name. Default `default`.
    pub stage: String,
    /// Accept all prompt defaults (CI). `MIGRATE_NONINTERACTIVE=1` or `-y`.
    pub non_interactive: bool,
    /// Mirror logs to stderr live. `MIGRATE_VERBOSE=1` or `-v`.
    pub verbose: bool,
    /// Surface the Agent mode + accept `--mode Agent`. `MIGRATE_ENABLE_AGENT=1`.
    pub enable_agent: bool,
    /// Silence the preview banner. `MIGRATE_PREVIEW_ACK=1`.
    pub preview_ack: bool,
}

impl Env {
    /// Resolve from the real process environment + the current directory.
    pub fn from_process() -> Self {
        let cwd = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
        let home = std::env::var_os("MIGRATE_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| cwd.join("migration-assistant-workspace"));
        Self {
            home,
            stage: env_or("STAGE", "default"),
            non_interactive: env_flag("MIGRATE_NONINTERACTIVE"),
            verbose: env_flag("MIGRATE_VERBOSE"),
            enable_agent: env_flag("MIGRATE_ENABLE_AGENT"),
            preview_ack: env_flag("MIGRATE_PREVIEW_ACK"),
        }
    }

    /// The directory holding this stage's state — `$MIGRATE_HOME/<stage>`.
    pub fn stage_dir(&self) -> PathBuf {
        self.home.join(&self.stage)
    }
}

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key)
        .ok()
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| default.to_string())
}

fn env_flag(key: &str) -> bool {
    std::env::var(key).map(|v| v == "1").unwrap_or(false)
}

/// Outcome of parsing the resume/deploy flags.
#[derive(Debug, Default, Clone)]
pub struct DeployFlags {
    /// `--switch` — re-prompt the mode picker.
    pub force_switch: bool,
    /// `--mode <name>` — bypass the picker.
    pub force_mode: Option<String>,
    /// `--non-interactive` / `-y` was passed (implies a default Manual mode).
    pub non_interactive: bool,
    /// `--verbose` / `-v` was passed.
    pub verbose: bool,
    /// `--reset-cache` was passed.
    pub reset_cache: bool,
    /// Positional args that weren't recognized as flags.
    pub rest: Vec<String>,
}

/// Apply the deploy/resume flag table to `state`, returning the control flags
/// that don't live in state.
/// `cmd_resume` (run AFTER `state.load()` so flag overrides merge into the
/// loaded values rather than clobbering them).
///
/// Each `--flag VALUE` and `--flag=VALUE` form is accepted. Unknown tokens are
/// collected into `rest`.
pub fn apply_deploy_flags(state: &mut State, args: &[String]) -> DeployFlags {
    let mut out = DeployFlags::default();
    let mut i = 0;
    while i < args.len() {
        let arg = &args[i];

        // `--flag VALUE` consumes the next arg; `--flag=VALUE` splits in place.
        // Returns the value plus how many args (1 or 2) were consumed.
        let take_value = |this: &str| -> Option<(String, usize)> {
            if let Some(v) = arg.strip_prefix(&format!("{this}=")) {
                Some((v.to_string(), 1))
            } else if arg == this {
                args.get(i + 1).map(|v| (v.clone(), 2))
            } else {
                None
            }
        };

        // Each arm tries the matching flag; `continue` after a match advances.
        macro_rules! set_from {
            ($flag:literal, $key:literal) => {
                if let Some((v, consumed)) = take_value($flag) {
                    state.set($key, v);
                    i += consumed;
                    continue;
                }
            };
        }

        // --stage also names the stack/ECR/state-dir (STAGE_NAME).
        set_from!("--stage", "STAGE_NAME");
        set_from!("--region", "AWS_REGION");
        set_from!("--stack-name", "CFN_STACK_NAME");
        set_from!("--version", "MA_VERSION");
        set_from!("--kubectl-context", "KUBECTL_CONTEXT");
        set_from!("--namespace", "STAGE_NAME");
        set_from!("--helm-values", "HELM_EXTRA_VALUES_FILE");
        set_from!("--vpc-id", "IMPORT_VPC_ID");
        set_from!("--subnet-ids", "IMPORT_SUBNET_IDS");
        set_from!("--eks-access-principal-arn", "EKS_ACCESS_PRINCIPAL_ARN");
        set_from!("--ma-images-source", "MA_IMAGES_SOURCE");
        set_from!("--ma-chart-dir", "MA_CHART_DIR");
        set_from!("--image-tag", "IMAGE_TAG");
        set_from!("--base-dir", "BASE_DIR");
        set_from!("--tls-mode", "TLS_MODE");
        set_from!("--pca-arn", "PCA_ARN");
        set_from!("--create-vpc-endpoints", "CREATE_VPC_ENDPOINTS");

        // --mode needs special handling (control flag, not state).
        if let Some((v, consumed)) = take_value("--mode") {
            out.force_mode = Some(v);
            i += consumed;
            continue;
        }

        // Boolean flags (no value).
        match arg.as_str() {
            "--switch" => out.force_switch = true,
            "--verbose" | "-v" => {
                out.verbose = true;
            }
            "--reset-cache" => out.reset_cache = true,
            "--non-interactive" | "-y" => {
                out.non_interactive = true;
                if out.force_mode.is_none() {
                    out.force_mode = Some("Manual".to_string());
                }
            }
            "--use-public-images" => state.set("MIRROR_IMAGES", "N"),
            "--skip-cfn-deploy" => state.set("SKIP_CFN_DEPLOY", "Y"),
            "--skip-console-exec" => state.set("SKIP_CONSOLE_EXEC", "Y"),
            "--skip-setting-k8s-context" => state.set("SKIP_KUBECONFIG_UPDATE", "Y"),
            "--deploy-create-vpc-cfn" => state.set("CFN_TEMPLATE_VARIANT", "create-vpc"),
            "--deploy-import-vpc-cfn" => state.set("CFN_TEMPLATE_VARIANT", "import-vpc"),
            "--use-general-node-pool" => state.set("USE_GENERAL_NODE_POOL", "Y"),
            "--disable-general-purpose-pool" => state.set("DISABLE_GENERAL_NODE_POOL", "Y"),
            "--build" => state.set("BUILD_FROM_SOURCE", "Y"),
            "--skip-test-images" => state.set("SKIP_TEST_IMAGES", "Y"),
            "--ignore-checks" => state.set("IGNORE_CHECKS", "Y"),
            "--" => {
                // Everything after `--` is positional.
                out.rest.extend(args[i + 1..].iter().cloned());
                break;
            }
            other => out.rest.push(other.to_string()),
        }
        i += 1;
    }
    out
}

/// Resolve the effective mode given the gate and the picker/flag inputs.
///
/// Encodes the Agent preview-gate logic: `--mode Agent` (or a stale
/// `MODE=Agent` in state) is rejected unless `enable_agent`.
pub fn check_agent_gate(
    force_mode: Option<&str>,
    state_mode: &str,
    enable_agent: bool,
) -> crate::Result<()> {
    let wants_agent =
        force_mode == Some("Agent") || (force_mode.is_none() && state_mode == "Agent");
    if wants_agent && !enable_agent {
        return Err(crate::Error::die(
            "Agent mode is a preview feature gated behind MIGRATE_ENABLE_AGENT=1. \
             Set the env var if you want to evaluate it; otherwise use --mode Manual.",
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state() -> State {
        State::new(std::env::temp_dir().join("ma-test-cfg"))
    }

    fn flags(args: &[&str]) -> (State, DeployFlags) {
        let mut s = state();
        let owned: Vec<String> = args.iter().map(|s| s.to_string()).collect();
        let f = apply_deploy_flags(&mut s, &owned);
        (s, f)
    }

    #[test]
    fn region_space_and_equals_forms() {
        let (s, _) = flags(&["--region", "us-west-2"]);
        assert_eq!(s.get("AWS_REGION"), Some("us-west-2"));
        let (s, _) = flags(&["--region=eu-west-1"]);
        assert_eq!(s.get("AWS_REGION"), Some("eu-west-1"));
    }

    #[test]
    fn stage_and_namespace_both_set_stage_name() {
        let (s, _) = flags(&["--stage", "prod"]);
        assert_eq!(s.get("STAGE_NAME"), Some("prod"));
        let (s, _) = flags(&["--namespace=ns1"]);
        assert_eq!(s.get("STAGE_NAME"), Some("ns1"));
    }

    #[test]
    fn boolean_flags_map_to_state() {
        let (s, _) = flags(&[
            "--use-public-images",
            "--skip-cfn-deploy",
            "--build",
            "--deploy-import-vpc-cfn",
            "--use-general-node-pool",
        ]);
        assert_eq!(s.get("MIRROR_IMAGES"), Some("N"));
        assert_eq!(s.get("SKIP_CFN_DEPLOY"), Some("Y"));
        assert_eq!(s.get("BUILD_FROM_SOURCE"), Some("Y"));
        assert_eq!(s.get("CFN_TEMPLATE_VARIANT"), Some("import-vpc"));
        assert_eq!(s.get("USE_GENERAL_NODE_POOL"), Some("Y"));
    }

    #[test]
    fn non_interactive_implies_manual() {
        let (_s, f) = flags(&["--non-interactive"]);
        assert!(f.non_interactive);
        assert_eq!(f.force_mode.as_deref(), Some("Manual"));

        let (_s, f) = flags(&["-y"]);
        assert!(f.non_interactive);
    }

    #[test]
    fn explicit_mode_overrides_noninteractive_default() {
        // --mode Agent before -y: the explicit mode must survive.
        let (_s, f) = flags(&["--mode", "Agent", "--non-interactive"]);
        assert_eq!(f.force_mode.as_deref(), Some("Agent"));
    }

    #[test]
    fn switch_and_reset_cache() {
        let (_s, f) = flags(&["--switch", "--reset-cache"]);
        assert!(f.force_switch);
        assert!(f.reset_cache);
    }

    #[test]
    fn tls_and_vpc_endpoint_flags() {
        let (s, _) = flags(&[
            "--tls-mode",
            "pca-existing",
            "--pca-arn",
            "arn:x",
            "--create-vpc-endpoints",
            "s3,ecr",
        ]);
        assert_eq!(s.get("TLS_MODE"), Some("pca-existing"));
        assert_eq!(s.get("PCA_ARN"), Some("arn:x"));
        assert_eq!(s.get("CREATE_VPC_ENDPOINTS"), Some("s3,ecr"));
    }

    #[test]
    fn unknown_tokens_collected_into_rest() {
        let (_s, f) = flags(&["positional", "--also-unknown"]);
        assert!(f.rest.contains(&"positional".to_string()));
        assert!(f.rest.contains(&"--also-unknown".to_string()));
    }

    #[test]
    fn double_dash_terminates_flags() {
        let (s, f) = flags(&["--region", "us-east-1", "--", "--region", "ignored"]);
        assert_eq!(s.get("AWS_REGION"), Some("us-east-1"));
        assert!(f.rest.contains(&"--region".to_string()));
        assert!(f.rest.contains(&"ignored".to_string()));
    }

    // ---- Agent preview gate ----

    #[test]
    fn agent_gate_rejects_without_env() {
        assert!(check_agent_gate(Some("Agent"), "", false).is_err());
        assert!(check_agent_gate(None, "Agent", false).is_err());
    }

    #[test]
    fn agent_gate_accepts_with_env() {
        assert!(check_agent_gate(Some("Agent"), "", true).is_ok());
        assert!(check_agent_gate(None, "Agent", true).is_ok());
    }

    #[test]
    fn agent_gate_allows_manual_always() {
        assert!(check_agent_gate(Some("Manual"), "", false).is_ok());
        assert!(check_agent_gate(None, "Manual", false).is_ok());
        assert!(check_agent_gate(None, "", false).is_ok());
    }
}
