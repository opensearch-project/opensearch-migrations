//! Command-line surface + dispatcher.
//!
//! Subcommands: resume (default) / console / agent / diag / clear / cleanup /
//! pack / version / help. Each handler resolves the environment, loads state,
//! and drives the orchestrator. Exit-code contract: unknown command → 64,
//! generic failure → 1, success → 0.

use crate::config::{self, Env};
use crate::error::{Error, Result};
use crate::runner::CommandRunner;
use crate::state::State;
use crate::{agent, helm, timeline, ui, version};
use std::path::PathBuf;

/// Parse argv (already split, program name dropped) and dispatch. Returns the
/// process exit code. `runner` is the external-command backend (real in `main`,
/// a mock in tests).
pub fn dispatch<R: CommandRunner>(args: &[String], runner: &R) -> i32 {
    match run(args, runner) {
        Ok(()) => 0,
        Err(e) => {
            ui::err(&e.message);
            e.code
        }
    }
}

fn run<R: CommandRunner>(args: &[String], runner: &R) -> Result<()> {
    // The first non-flag token is the subcommand; a leading flag means the
    // default `resume` (so `migration-assistant --non-interactive …` works),
    // matching the dispatcher contract.
    let (subcommand, rest) = split_subcommand(args);
    let env = Env::from_process();

    match subcommand.as_deref() {
        None | Some("resume") => cmd_resume(env, runner, &rest),
        Some("console") => cmd_console(env, runner, &rest),
        Some("agent") => cmd_agent(env, runner, &rest),
        Some("diag") => cmd_diag(env, runner, &rest),
        Some("clear") => cmd_clear(env, runner, &rest),
        Some("cleanup") => cmd_cleanup(env, runner, &rest),
        Some("dashboard") => cmd_dashboard(env, &rest),
        Some("pack") => crate::pack_cmd::cmd_pack(runner, &rest),
        Some("version") | Some("--version") | Some("-V") => {
            println!("{}", resolve_version_string());
            Ok(())
        }
        Some("help") | Some("--help") | Some("-h") => {
            print!("{}", help_text());
            Ok(())
        }
        // A flag-led invocation behaves like `resume <flags>`.
        Some(s) if s.starts_with('-') => {
            let mut full = vec![s.to_string()];
            full.extend_from_slice(&rest);
            cmd_resume(env, runner, &full)
        }
        Some(_unknown) => {
            print!("{}", help_text());
            Err(Error::with_code("unknown command", 64))
        }
    }
}

/// Split argv into `(subcommand, rest)`. The subcommand is the first token that
/// doesn't begin with `-`; everything else is `rest`.
fn split_subcommand(args: &[String]) -> (Option<String>, Vec<String>) {
    if args.is_empty() {
        return (None, Vec::new());
    }
    let first = &args[0];
    if first.starts_with('-') {
        // Flag-led: no explicit subcommand; pass everything through.
        (Some(first.clone()), args[1..].to_vec())
    } else {
        (Some(first.clone()), args[1..].to_vec())
    }
}

/// Extract `--stage <name>` (or `--stage=name`) from args, returning the stage
/// if present. Shared by the simple subcommands that only honor `--stage`.
fn stage_from_args(args: &[String]) -> Option<String> {
    let mut i = 0;
    while i < args.len() {
        if let Some(v) = args[i].strip_prefix("--stage=") {
            return Some(v.to_string());
        }
        if args[i] == "--stage" {
            return args.get(i + 1).cloned();
        }
        i += 1;
    }
    None
}

/// Apply an explicit `--stage` to the env (the simple subcommands' first pass).
fn env_with_stage(mut env: Env, args: &[String]) -> Env {
    if let Some(stage) = stage_from_args(args) {
        env.stage = stage;
    }
    env
}

// ---------------------------------------------------------------------------
// resume — the controller
// ---------------------------------------------------------------------------

fn cmd_resume<R: CommandRunner>(mut env: Env, runner: &R, args: &[String]) -> Result<()> {
    // First pass: only --stage, so we load the right stage's state.
    if let Some(stage) = stage_from_args(args) {
        env.stage = stage;
    }
    print_banner(&env);

    // Load state BEFORE applying flags, so flag overrides merge in.
    let mut state = State::new(env.stage_dir());
    state.load()?;
    let flags = config::apply_deploy_flags(&mut state, args);
    if flags.verbose {
        env.verbose = true;
    }
    if flags.non_interactive {
        env.non_interactive = true;
    }
    state.save()?;

    let resuming = resolve_resume(&mut state, &env)?;

    // Mode resolution + Agent preview gate.
    let state_mode = state.get_owned("MODE", "");
    config::check_agent_gate(flags.force_mode.as_deref(), &state_mode, env.enable_agent)?;
    let mode = resolve_mode(&mut state, &flags, &env, resuming)?;
    state.set("MODE", &mode);
    state.save()?;

    match mode.as_str() {
        "Manual" => manual_path(env, runner, state, resuming),
        "Agent" => {
            ui::ok(&format!("mode: Agent (stage={})", env.stage));
            ui::info("Agent handoff flow ready; use `migration-assistant agent` after deploy.");
            Ok(())
        }
        other => Err(Error::die(format!("unknown mode: {other}"))),
    }
}

/// Print the branded banner + cli/stage/workdir line. Branding comes from the
/// bundled manifest.json when present, else the upstream defaults.
fn print_banner(env: &Env) {
    let manifest = load_manifest();
    ui::banner(&brand(
        &manifest,
        "appName",
        "OpenSearch Migration Assistant",
    ));
    let tagline = brand(&manifest, "tagline", "");
    if !tagline.is_empty() {
        ui::dim(&format!("  {tagline}"));
    }
    ui::dim(&format!(
        "  cli={}  stage={}  workdir={}",
        resolve_version_string(),
        env.stage,
        env.stage_dir().display()
    ));
}

/// Decide whether this run resumes from the saved step. Terminal steps resume
/// silently; an in-progress step prompts, and "start over" clears the wizard
/// inputs. Returns whether we're resuming.
fn resolve_resume(state: &mut State, env: &Env) -> Result<bool> {
    let prev = state.resumable_step().to_string();
    if prev.is_empty() {
        return Ok(false);
    }
    if matches!(prev.as_str(), "agent_handoff" | "console_handoff" | "ready") {
        return Ok(true);
    }
    ui::info(&format!(
        "previous run progressed to: {}",
        timeline::phase_label(&prev)
    ));
    if ui::confirm("Resume from this point?", true, env.non_interactive) {
        return Ok(true);
    }
    ui::warn("starting over: re-prompting wizard");
    for k in ["last_step", "STAGE_NAME", "MIRROR_IMAGES", "MA_VERSION"] {
        state.set(k, "");
    }
    state.save()?;
    Ok(false)
}

/// The Manual deploy pipeline — `manual_path`. Discovers the environment,
/// collects the wizard config, then drives the orchestrator through
/// CFN → kubeconfig → crane → helm → console. AWS-touching steps require real
/// credentials; without `aws` on PATH the discovery step fails cleanly with the
/// a clear error message.
fn manual_path<R: CommandRunner>(env: Env, runner: &R, state: State, resuming: bool) -> Result<()> {
    // Init the run log under <stage>/log/migrate.log. The Jenkins helper
    // (test/awsRunEksValidation.sh) dumps this file on failure, so it must
    // exist as soon as the deploy flow starts.
    let log = crate::log::Log::init(&env.stage_dir(), env.verbose);
    log.info(&format!(
        "manual_path start stage={} resuming={}",
        env.stage, resuming
    ));
    let mut app = crate::app::App { env, runner, state };

    ui::step("Discover environment");
    log.info("discover: os + aws + resources");
    crate::discover::discover_os(runner, &mut app.state);
    if app.state.get("AWS_ACCOUNT").unwrap_or("").is_empty() {
        if let Err(e) = crate::discover::discover_aws(&mut app.state) {
            log.error(&format!("AWS credentials unavailable: {}", e.message));
            return Err(Error::die(format!(
                "AWS credentials are required. Set up 'aws configure' and rerun. ({})",
                e.message
            )));
        }
    }
    crate::discover::discover_resources(runner, &mut app.state);
    app.state.save()?;

    // Wizard: in non-interactive mode (or on resume with everything saved) we
    // take saved values + defaults; otherwise the TUI collects them. The
    // resume fast-path: skip wizard if state is already populated.
    if !(resuming
        && !app.state.get_or("AWS_REGION", "").is_empty()
        && !app.state.get_or("STAGE_NAME", "").is_empty()
        && !app.state.get_or("MIRROR_IMAGES", "").is_empty()
        && !app.state.get_or("MA_VERSION", "").is_empty())
    {
        collect_wizard(&mut app)?;
    }

    log.info(&format!(
        "config: region={} stage_name={} mirror={} ma_version={}",
        app.state.get_or("AWS_REGION", ""),
        app.state.get_or("STAGE_NAME", ""),
        app.state.get_or("MIRROR_IMAGES", ""),
        app.state.get_or("MA_VERSION", ""),
    ));

    // Drive the real deploy pipeline. Each step propagates its error (and exit
    // code) up through `dispatch`, so a failed deploy fails the Jenkins step —
    // this is the contract the bootstrap shell preserved with `exit $PIPESTATUS`.
    run_deploy(&mut app, &log)?;

    ui::ok(&format!("Deploy complete for stage={}", app.env.stage));
    Ok(())
}

/// The headless deploy spine: CFN template → CFN deploy → kubeconfig → EKS
/// access → image build/mirror → helm.
/// Each `?` aborts the run with a non-zero exit.
/// pipeline did.
fn run_deploy<R: CommandRunner>(app: &mut crate::app::App<R>, log: &crate::log::Log) -> Result<()> {
    log.info("deploy: resolve CFN template");
    let template = app.resolve_cfn_template()?;

    log.info("deploy: cfn_deploy_or_skip");
    app.cfn_deploy_or_skip(&template)?;

    log.info("deploy: kubeconfig_setup");
    app.kubeconfig_setup()?;

    log.info("deploy: grant_eks_access");
    app.grant_eks_access()?;

    // --build builds + pushes the MA images to ECR. (No-op on the release lane.)
    // The chart's dependency-installer pulls its sub-charts (cert-manager, argo,
    // strimzi, …) and their images directly from PUBLIC registries — the
    // working reference deploy does NOT mirror those to private ECR, so we
    // don't either. (Mirroring all charts through private OCI made the
    // installer job exceed its activeDeadlineSeconds.) Only the MA app images
    // are repointed at the private ECR, via the `--set images.*` flags inside
    // helm_install_or_upgrade.
    log.info("deploy: build_images_or_skip");
    app.build_images_or_skip()?;

    // Warm the general-purpose nodepool so the chart's installer hook pods have
    // capacity to schedule on (else they wait on Karpenter cold-start and the
    // hook job exceeds its activeDeadlineSeconds → helm DeadlineExceeded).
    log.info("deploy: ensure_general_node_pool");
    app.ensure_general_node_pool()?;

    log.info("deploy: resolve chart + helm install");
    let (chart, values) = app.resolve_chart()?;
    app.helm_install_or_upgrade(&chart, &values)?;

    log.info("deploy: complete");
    Ok(())
}

/// Fill the wizard fields. Non-interactive: saved value → sensible default.
/// Interactive: prompt for each (the Ratatui wizard is used by the binary's
/// interactive path; here we keep the prompt-based fallback that also satisfies
/// the stdout-discipline + non-interactive contracts).
fn collect_wizard<R: CommandRunner>(app: &mut crate::app::App<R>) -> Result<()> {
    ui::step("Configure deployment");
    let ni = app.env.non_interactive;

    let region_default = {
        let saved = app.state.get_owned("AWS_REGION", "");
        if saved.is_empty() {
            std::env::var("AWS_REGION")
                .or_else(|_| std::env::var("AWS_DEFAULT_REGION"))
                .unwrap_or_else(|_| "us-east-1".to_string())
        } else {
            saved
        }
    };
    let region = ui::prompt("AWS region (deploy target)", &region_default, ni);
    app.state.set("AWS_REGION", &region);

    let stage_default = app.state.get_owned("STAGE_NAME", "ma");
    let stage_name = ui::prompt("Stage name", &stage_default, ni);
    app.state.set("STAGE_NAME", stage_name);

    let mirror_default = app.state.get_or("MIRROR_IMAGES", "Y") == "Y";
    let mirror = ui::confirm(
        "Mirror images from public.ecr.aws to your private ECR?",
        mirror_default,
        ni,
    );
    app.state
        .set("MIRROR_IMAGES", if mirror { "Y" } else { "N" });

    let ver_default = app.state.get_owned("MA_VERSION", "");
    let ma_ver = ui::prompt("Migration Assistant version", &ver_default, ni);
    app.state.set("MA_VERSION", ma_ver);

    app.state.set("last_step", "wizard_done");
    app.state.save()?;
    ui::ok("wizard saved");
    Ok(())
}

/// Resolve the effective mode: explicit `--mode` wins; else the picker fires on
/// first run / `--switch`; else the saved mode persists.
fn resolve_mode(
    state: &mut State,
    flags: &config::DeployFlags,
    env: &Env,
    resuming: bool,
) -> Result<String> {
    if let Some(m) = &flags.force_mode {
        return Ok(m.clone());
    }
    let current = state.get_owned("MODE", "");
    if current.is_empty() || flags.force_switch {
        return Ok(pick_mode(&current, env));
    }
    if resuming {
        ui::dim(&format!(
            "  resuming with mode={current} (use --switch to change)"
        ));
        return Ok(current);
    }
    // Offer a switch.
    if ui::confirm(
        &format!("Mode: {current}. Switch?"),
        false,
        env.non_interactive,
    ) {
        Ok(pick_mode(&current, env))
    } else {
        Ok(current)
    }
}

/// Present the mode picker and return the chosen id. Agent is offered only when
/// the gate is on (`MIGRATE_ENABLE_AGENT=1`).
fn pick_mode(current: &str, env: &Env) -> String {
    let mut ids = vec!["Manual"];
    let mut labels = vec!["Manual"];
    if env.enable_agent {
        ids.push("Agent");
        labels.push("AI");
    }
    ui::step("How do you want to drive this migration?");
    for (i, label) in labels.iter().enumerate() {
        ui::dim(&format!("  [{}] {}", i + 1, label));
    }
    let default = if !current.is_empty() {
        ids.iter()
            .position(|id| *id == current)
            .map(|i| i + 1)
            .unwrap_or(1)
    } else {
        1
    };
    let pick = ui::prompt("Select", &default.to_string(), env.non_interactive);
    ui::resolve_pick(&pick, &ids, &labels)
        .unwrap_or("Manual")
        .to_string()
}

// ---------------------------------------------------------------------------
// console / agent / diag / clear / cleanup
// ---------------------------------------------------------------------------

fn cmd_console<R: CommandRunner>(env: Env, runner: &R, args: &[String]) -> Result<()> {
    let env = env_with_stage(env, args);
    let mut state = State::new(env.stage_dir());
    state.load()?;
    if state.get_or("STAGE_NAME", "").is_empty() {
        return Err(Error::die(
            "no deployed stage found in state. Run `migration-assistant` first or pass --stage <name>.",
        ));
    }
    let kube_ctx = state.get_owned("KUBECTL_CONTEXT", "");
    ui::step("Handing off to migration-console-0 (kubectl exec)");
    let mut a = vec![];
    if !kube_ctx.is_empty() {
        a.push("--context".to_string());
        a.push(kube_ctx);
    }
    a.extend(
        [
            "exec",
            "--stdin",
            "--tty",
            "--namespace",
            helm::NAMESPACE,
            "migration-console-0",
            "--",
            "/bin/bash",
        ]
        .map(String::from),
    );
    runner.run("kubectl", &a.iter().map(String::as_str).collect::<Vec<_>>());
    Ok(())
}

fn cmd_agent<R: CommandRunner>(env: Env, runner: &R, args: &[String]) -> Result<()> {
    let env = env_with_stage(env, args);
    let mut state = State::new(env.stage_dir());
    state.load()?;
    if state.get_or("STAGE_NAME", "").is_empty() {
        return Err(Error::die(
            "no deployed stage found in state. Run `migration-assistant` first or pass --stage <name>.",
        ));
    }
    // Positional (non-flag) arg picks the agent.
    let picked = args.iter().find(|a| !a.starts_with('-')).cloned();
    let picked = match picked {
        Some(p) => p,
        None => {
            let saved = state.get_owned("AGENT", "");
            if !saved.is_empty() {
                saved
            } else {
                let found = agent::discover(runner);
                found
                    .first()
                    .map(|s| s.to_string())
                    .ok_or_else(|| Error::die("no supported agent CLI found on PATH"))?
            }
        }
    };
    if agent::bin_for(runner, &picked).is_none() {
        return Err(Error::die(format!("agent not on PATH: {picked}")));
    }
    state.set("AGENT", &picked);
    state.set("last_step", "agent_handoff");
    state.save()?;
    ui::ok(&format!("Handing off to: {picked}"));
    Ok(())
}

fn cmd_diag<R: CommandRunner>(env: Env, runner: &R, args: &[String]) -> Result<()> {
    let env = env_with_stage(env, args);
    let mut state = State::new(env.stage_dir());
    state.load()?;
    ui::banner("OpenSearch Migration Assistant — diag");
    if state.get_or("STAGE_NAME", "").is_empty() {
        return Err(Error::die(
            "no deployed stage found in state. Run `migration-assistant` first or pass --stage <name>.",
        ));
    }
    let kube_ctx = state.get_owned("KUBECTL_CONTEXT", "");
    let ctx_args = |a: &[&str]| -> Vec<String> {
        let mut full = vec![];
        if !kube_ctx.is_empty() {
            full.push("--context".to_string());
            full.push(kube_ctx.clone());
        }
        full.extend(a.iter().map(|s| s.to_string()));
        full
    };
    runner.run(
        "helm",
        &ctx_args(&["status", helm::RELEASE_NAME, "--namespace", helm::NAMESPACE])
            .iter()
            .map(String::as_str)
            .collect::<Vec<_>>(),
    );
    runner.run(
        "kubectl",
        &ctx_args(&["get", "pods", "--namespace", helm::NAMESPACE, "-o", "wide"])
            .iter()
            .map(String::as_str)
            .collect::<Vec<_>>(),
    );
    ui::ok("diag complete");
    Ok(())
}

/// `dashboard` — launch the interactive deploy dashboard for a stage in an
/// inline viewport (preserves terminal scrollback; scroll the activity log with
/// ↑/↓/PageUp/PageDown, q to quit). Seeds the timeline from the stage's saved
/// `last_step` and replays the run log. This is the proposal's interactive
/// surface; the deploy pipeline itself stays non-interactive for CI.
fn cmd_dashboard(env: Env, args: &[String]) -> Result<()> {
    let env = env_with_stage(env, args);
    let mut state = State::new(env.stage_dir());
    state.load()?;

    let app_name = brand(
        &load_manifest(),
        "appName",
        "OpenSearch Migration Assistant",
    );
    let mut dash = crate::dashboard::Dashboard::new(app_name);
    dash.set_phase(state.resumable_step());

    // Seed the activity log from the run log, if present.
    if let Ok(text) = std::fs::read_to_string(env.stage_dir().join("log/migrate.log")) {
        for line in text.lines().filter(|l| !l.trim().is_empty()) {
            dash.push_log(line.to_string());
        }
    }
    if dash.log.is_empty() {
        dash.push_log(format!("stage '{}' — no activity yet", env.stage));
        dash.push_log("run `migration-assistant` to start a deploy".to_string());
    }

    // Inline-viewport runtime: 14 rows is enough for the two-pane layout while
    // leaving scrollback above it. The step closure is a no-op here (we're
    // viewing existing state, not driving a live deploy).
    crate::dashboard::run_inline(dash, 14, |_| false)?;
    Ok(())
}

fn cmd_clear<R: CommandRunner>(env: Env, _runner: &R, args: &[String]) -> Result<()> {
    let env = env_with_stage(env, args);
    let force = args.iter().any(|a| a == "-y" || a == "--yes");
    ui::banner(&format!("Clear local state for stage: {}", env.stage));
    let stage_dir = env.stage_dir();
    if !stage_dir.exists() {
        ui::info(&format!(
            "nothing to clear; no state for stage '{}'",
            env.stage
        ));
        return Ok(());
    }
    ui::dim(&format!("  workdir: {}", stage_dir.display()));
    ui::dim("  this does NOT touch AWS resources or kubernetes (use 'cleanup' for that)");
    if !force
        && !ui::confirm(
            &format!("Wipe local state for stage '{}'?", env.stage),
            false,
            env.non_interactive,
        )
    {
        ui::info("clear cancelled");
        return Ok(());
    }
    std::fs::remove_dir_all(&stage_dir)?;
    ui::ok(&format!("stage '{}' state cleared", env.stage));
    Ok(())
}

fn cmd_cleanup<R: CommandRunner>(env: Env, runner: &R, args: &[String]) -> Result<()> {
    let mut env = env_with_stage(env, args);
    if args.iter().any(|a| a == "-y" || a == "--non-interactive") {
        env.non_interactive = true;
    }
    let mut state = State::new(env.stage_dir());
    state.load()?;
    let stack = state.get_owned("CFN_STACK_NAME", "");
    let mut release = state.get_owned("HELM_RELEASE", "");
    let region = state.get_owned("AWS_REGION", "");
    if release.is_empty() && !stack.is_empty() {
        release = helm::RELEASE_NAME.to_string();
    }
    if stack.is_empty() && release.is_empty() {
        ui::warn(&format!(
            "nothing to clean up; state is empty for stage '{}'",
            env.stage
        ));
        return Ok(());
    }
    ui::banner(&format!("Cleanup stage: {}", env.stage));
    if !stack.is_empty() {
        ui::dim(&format!("  CloudFormation stack    {stack}"));
    }
    if !release.is_empty() {
        ui::dim(&format!(
            "  Helm release            {release}  (namespace: {})",
            helm::NAMESPACE
        ));
    }
    ui::dim(&format!(
        "  Local state             {}",
        env.stage_dir().display()
    ));

    if !env.non_interactive && !ui::confirm("Proceed with cleanup?", false, env.non_interactive) {
        ui::info("cleanup cancelled");
        return Ok(());
    }
    let kube_ctx = state.get_owned("KUBECTL_CONTEXT", "");
    if !release.is_empty() {
        ui::step(&format!("helm uninstall {release}"));
        let mut a = vec![];
        if !kube_ctx.is_empty() {
            a.push("--kube-context".to_string());
            a.push(kube_ctx);
        }
        a.extend(
            [
                "uninstall",
                &release,
                "--namespace",
                helm::NAMESPACE,
                "--wait",
            ]
            .map(String::from),
        );
        runner.run("helm", &a.iter().map(String::as_str).collect::<Vec<_>>());
    }
    if !stack.is_empty() {
        ui::step(&format!("Deleting CFN stack: {stack}"));
        runner.run(
            "aws",
            &[
                "cloudformation",
                "delete-stack",
                "--region",
                &region,
                "--stack-name",
                &stack,
            ],
        );
        runner.run(
            "aws",
            &[
                "cloudformation",
                "wait",
                "stack-delete-complete",
                "--region",
                &region,
                "--stack-name",
                &stack,
            ],
        );
    }
    state.archive()?;
    ui::ok(&format!("stage '{}' cleaned up; state archived", env.stage));
    Ok(())
}

// ---------------------------------------------------------------------------
// version / help text
// ---------------------------------------------------------------------------

/// The directory the CLI lives in (its crate root, containing `bin/` + `src/`).
///
/// Resolved from the running executable: in a build-from-source layout the
/// binary is at `<cli>/target/release/migration-assistant`, and in a release
/// tarball it's at `<cli>/bin/migration-assistant-bin`. Both are walked back up
/// to `<cli>`. Used to locate the bundled `manifest.json` + `skills/`.
fn cli_dir() -> Option<std::path::PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let parent = exe.parent()?;
    // target/release/<bin> → up 2 to crate root; bin/<bin> → up 1.
    if parent
        .file_name()
        .map(|n| n == "release" || n == "debug")
        .unwrap_or(false)
    {
        parent.parent()?.parent().map(|p| p.to_path_buf())
    } else if parent.file_name().map(|n| n == "bin").unwrap_or(false) {
        parent.parent().map(|p| p.to_path_buf())
    } else {
        Some(parent.to_path_buf())
    }
}

/// Load the bundled manifest, if any — branding source for the banner/version.
fn load_manifest() -> Option<crate::manifest::Manifest> {
    let dir = cli_dir()?;
    crate::manifest::Manifest::load(&dir).ok().flatten()
}

/// Resolve a branding field via the manifest, falling back to `default`.
/// `${VAR}` tokens resolve against state then env (matching `manifest_brand`).
fn brand(manifest: &Option<crate::manifest::Manifest>, field: &str, default: &str) -> String {
    let src = |name: &str| std::env::var(name).ok();
    match manifest {
        Some(m) => {
            let v = m.brand(field, &src);
            if v.is_empty() {
                default.to_string()
            } else {
                v
            }
        }
        None => default.to_string(),
    }
}

/// The version string the `version` subcommand prints — branding
/// `versionString` if set, else `CLI_VERSION` + any `+pack-ver` suffix.
/// Output the CLI version string.
fn resolve_version_string() -> String {
    let manifest = load_manifest();
    if let Some(m) = &manifest {
        let vs = m.brand("versionString", &|n: &str| std::env::var(n).ok());
        if !vs.is_empty() {
            return vs;
        }
        return format!("{}{}", version::CLI_VERSION, m.pack_summary());
    }
    version::CLI_VERSION.to_string()
}

/// The help text (goes to stdout).
pub fn help_text() -> String {
    format!(
        "migration-assistant — OpenSearch Migration Assistant CLI\n\n\
Usage:\n\
\x20 migration-assistant [flags]                    Deploy / resume (default)\n\
\x20 migration-assistant resume   [flags]           Same as default\n\
\x20 migration-assistant console  [--stage NAME]    kubectl exec migration-console-0\n\
\x20 migration-assistant agent    [--stage NAME] [<agent>]  Open an LLM coding agent\n\
\x20 migration-assistant diag     [--stage NAME]    Dump diagnostics\n\
\x20 migration-assistant clear    [--stage NAME]    Wipe local state (no AWS changes)\n\
\x20 migration-assistant cleanup  [--stage NAME]    Tear down deploy + archive state\n\
\x20 migration-assistant dashboard [--stage NAME]   Interactive deploy dashboard (scrollable)\n\
\x20 migration-assistant pack     [flags]           Repack a CLI tarball\n\
\x20 migration-assistant version                    Print CLI version\n\
\x20 migration-assistant help                       This help\n\n\
Common flags:\n\
\x20 --stage NAME            Stage name (CFN stack + ECR repo suffix).\n\
\x20 --region REGION         AWS region. Default: us-east-1.\n\
\x20 --version VER           Pin Migration Assistant artifact version.\n\
\x20 --use-public-images     Skip image mirror; pull from public.ecr.aws.\n\
\x20 --non-interactive, -y   Accept defaults; for Jenkins / CI.\n\
\x20 --verbose, -v           Mirror logs to stderr live.\n\
\x20 --switch                Re-prompt the deploy wizard / mode picker.\n\
\x20 --mode Manual|Agent     Bypass the mode picker. Agent mode is a PREVIEW\n\
\x20                         and requires MIGRATE_ENABLE_AGENT=1.\n\n\
Version: {}\n",
        version::CLI_VERSION
    )
}

/// The default-state-root path, exposed for tests.
pub fn default_home() -> PathBuf {
    std::env::current_dir()
        .unwrap_or_default()
        .join("migration-assistant-workspace")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runner::MockRunner;

    fn args(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn unknown_command_exits_64() {
        let r = MockRunner::new();
        let code = dispatch(&args(&["notacommand"]), &r);
        assert_eq!(code, 64);
    }

    #[test]
    fn version_exits_zero() {
        let r = MockRunner::new();
        assert_eq!(dispatch(&args(&["version"]), &r), 0);
    }

    #[test]
    fn help_exits_zero() {
        let r = MockRunner::new();
        assert_eq!(dispatch(&args(&["help"]), &r), 0);
    }

    #[test]
    fn help_text_has_expected_content() {
        let h = help_text();
        assert!(h.contains("OpenSearch Migration Assistant CLI"));
        assert!(h.contains("--switch"));
        assert!(h.contains("Version:"));
    }

    #[test]
    fn version_string_is_semverish() {
        let v = resolve_version_string();
        assert!(v.split('.').count() >= 3, "expected x.y.z, got {v}");
    }

    #[test]
    fn split_subcommand_flag_led_is_resume() {
        let (sub, rest) = split_subcommand(&args(&["--non-interactive", "--stage", "x"]));
        assert_eq!(sub.as_deref(), Some("--non-interactive"));
        assert_eq!(rest, args(&["--stage", "x"]));
    }

    #[test]
    fn stage_extraction() {
        assert_eq!(
            stage_from_args(&args(&["--stage", "prod"])),
            Some("prod".into())
        );
        assert_eq!(stage_from_args(&args(&["--stage=stg"])), Some("stg".into()));
        assert_eq!(stage_from_args(&args(&["--region", "x"])), None);
    }
}
