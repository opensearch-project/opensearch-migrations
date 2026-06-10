//! Agent discovery, MCP registration, and exec-handoff planning.
//!
//! Pure decision logic — no file writes or process spawns. The caller drives
//! discovery through a [`CommandRunner`] and performs the actual `exec`:
//!   * [`AGENT_CANDIDATES`] + [`binaries_for`] — canonical-name → binaries;
//!   * [`discover`] — which agents resolve on PATH;
//!   * [`mcp_envvar`] — MCP-name → env-var normalization;
//!   * [`missing_hints`] — install hints for not-installed agents;
//!   * [`claude_has_session`] — claude session-storage probe;
//!   * [`exec_command`] — the resume-vs-fresh command line to `exec`.

use crate::runner::CommandRunner;
use std::path::{Path, PathBuf};

/// Locate the skills source directory (the one containing `Startup.md` and the
/// `<name>/SKILL.md` subdirs). Candidate search order:
///   1. `<cli>/skills`                         (release tarball layout)
///   2. `<cli>/../../../../agent-skills/skills` (repo dev mode)
///   3. `$MIGRATE_SKILLS_SRC`                   (test/operator override)
///
/// Returns `None` when no `Startup.md` is found.
pub fn skills_src(cli_dir: &Path) -> Option<PathBuf> {
    let candidates = [
        cli_dir.join("skills"),
        cli_dir.join("../../../../agent-skills/skills"),
    ];
    for c in candidates {
        if c.join("Startup.md").is_file() {
            return Some(c);
        }
    }
    if let Some(p) = std::env::var_os("MIGRATE_SKILLS_SRC") {
        let p = PathBuf::from(p);
        if p.join("Startup.md").is_file() {
            return Some(p);
        }
    }
    None
}

/// Discover the skill names under a skills source — every immediate subdir
/// containing a `SKILL.md` (the `skills.discovery: auto` contract).
pub fn discover_skills(skills_src: &Path) -> Vec<String> {
    let Ok(entries) = std::fs::read_dir(skills_src) else {
        return Vec::new();
    };
    let mut names: Vec<String> = entries
        .filter_map(|e| e.ok())
        .filter(|e| e.path().join("SKILL.md").is_file())
        .filter_map(|e| e.file_name().into_string().ok())
        .collect();
    names.sort();
    names
}

use crate::manifest::AgentConfig;

/// Default agent candidates (used when manifest has no `agents` array).
pub const DEFAULT_AGENT_CANDIDATES: &[(&str, &[&str])] = &[
    ("claude", &["claude"]),
    ("codex", &["codex"]),
    ("kiro", &["kiro-cli", "kiro"]),
];

/// The candidate binaries for a canonical agent name, checking the manifest
/// config first, then falling back to the hardcoded defaults.
pub fn binaries_for_with_config(canonical: &str, config: &[AgentConfig]) -> Vec<String> {
    if let Some(ac) = config.iter().find(|a| a.id == canonical) {
        if !ac.binaries.is_empty() {
            return ac.binaries.clone();
        }
    }
    DEFAULT_AGENT_CANDIDATES
        .iter()
        .find(|(name, _)| *name == canonical)
        .map(|(_, bins)| bins.iter().map(|s| s.to_string()).collect())
        .unwrap_or_default()
}

/// The candidate binaries for a canonical agent name (no manifest config).
pub fn binaries_for(canonical: &str) -> &'static [&'static str] {
    DEFAULT_AGENT_CANDIDATES
        .iter()
        .find(|(name, _)| *name == canonical)
        .map(|(_, bins)| *bins)
        .unwrap_or(&[])
}

/// The first candidate binary for `canonical` that resolves on PATH.
pub fn bin_for<R: CommandRunner>(runner: &R, canonical: &str) -> Option<&'static str> {
    binaries_for(canonical)
        .iter()
        .copied()
        .find(|b| runner.has_command(b))
}

/// Discover agents from manifest config. Returns `(id, resolved_binary)` pairs
/// in manifest order for agents found on PATH.
pub fn discover_from_config<R: CommandRunner>(
    runner: &R,
    config: &[AgentConfig],
) -> Vec<(String, String)> {
    config
        .iter()
        .filter_map(|ac| {
            let bins = binaries_for_with_config(&ac.id, config);
            let found = bins.iter().find(|b| runner.has_command(b))?;
            Some((ac.id.clone(), found.clone()))
        })
        .collect()
}

/// Canonical names of agents whose binary resolves on PATH, in candidate order.
/// Uses default candidates when no manifest config is provided.
pub fn discover<R: CommandRunner>(runner: &R) -> Vec<&'static str> {
    DEFAULT_AGENT_CANDIDATES
        .iter()
        .filter(|(name, _)| bin_for(runner, name).is_some())
        .map(|(name, _)| *name)
        .collect()
}

/// Normalize an MCP name into an uppercase env-var-safe suffix.
/// `aws-mcp` → `AWS_MCP`, `my.org/secrets` → `MY_ORG_SECRETS`.
/// Non-`[A-Za-z0-9]` characters all map to `_`.
pub fn mcp_envvar(name: &str) -> String {
    name.chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() {
                c.to_ascii_uppercase()
            } else {
                '_'
            }
        })
        .collect()
}

/// Default install hints (used when manifest has no `agents` config).
const DEFAULT_INSTALL_URLS: &[(&str, &str)] = &[
    ("claude", "https://code.claude.com/docs/en/quickstart"),
    ("codex", "https://developers.openai.com/codex/cli"),
    ("kiro", "https://kiro.dev/cli/"),
];

/// Install hints for supported agents NOT in `installed`. Returns `(name, url)`
/// pairs in canonical order; empty when all are installed.
pub fn missing_hints(installed: &[&str]) -> Vec<(&'static str, &'static str)> {
    DEFAULT_INSTALL_URLS
        .iter()
        .filter(|(name, _)| !installed.contains(name))
        .copied()
        .collect()
}

/// Install hints from manifest config. Returns `(id, url)` pairs for agents
/// not found in `installed_ids`.
pub fn missing_hints_from_config(
    installed_ids: &[String],
    config: &[AgentConfig],
) -> Vec<(String, String)> {
    config
        .iter()
        .filter(|ac| !installed_ids.contains(&ac.id))
        .filter(|ac| !ac.install_url.is_empty())
        .map(|ac| (ac.id.clone(), ac.install_url.clone()))
        .collect()
}

/// Whether the claude session storage has a conversation rooted at `cwd`.
/// Claude encodes the cwd by replacing `/` and `.` with `-`, then looks for
/// `~/.claude/projects/<key>/*.jsonl`. Non-claude agents are always considered
/// resumable (that decision lives at the call site).
pub fn claude_has_session(home: &Path, cwd: &str) -> bool {
    let key: String = cwd
        .chars()
        .map(|c| if c == '/' || c == '.' { '-' } else { c })
        .collect();
    let dir = home.join(".claude").join("projects").join(&key);
    let Ok(entries) = std::fs::read_dir(&dir) else {
        return false;
    };
    entries
        .filter_map(|e| e.ok())
        .any(|e| e.path().extension().map(|x| x == "jsonl").unwrap_or(false))
}

/// The command + args the CLI would `exec` to hand off to `agent`. `resuming`
/// selects the agent's native session-resume invocation; otherwise the
/// fresh-session form carries `fresh_prompt`.
pub fn exec_command(agent: &str, bin: &str, resuming: bool, fresh_prompt: &str) -> Vec<String> {
    let mut cmd = vec![bin.to_string()];
    match agent {
        "claude" => {
            if resuming {
                cmd.push("--continue".into());
            } else {
                cmd.push(fresh_prompt.into());
            }
        }
        "codex" => {
            if resuming {
                cmd.push("resume".into());
                cmd.push("--last".into());
            } else {
                cmd.push(fresh_prompt.into());
            }
        }
        "kiro" => {
            cmd.push("chat".into());
            cmd.push("--agent".into());
            cmd.push("opensearch-migration".into());
            if resuming {
                cmd.push("--resume".into());
            } else {
                cmd.push(fresh_prompt.into());
            }
        }
        _ => {}
    }
    cmd
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runner::MockRunner;

    #[test]
    fn skills_src_resolves_real_agent_skills_in_dev_mode() {
        // From the crate dir (deployment/k8s/aws/cli), the dev-mode candidate
        // ../../../../agent-skills/skills must contain the committed Startup.md.
        let cli_dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR"));
        if let Some(src) = skills_src(cli_dir) {
            assert!(src.join("Startup.md").is_file());
            let skills = discover_skills(&src);
            // The two upstream skills committed in this PR.
            assert!(skills.iter().any(|s| s == "migration-assistant-operator"));
            assert!(skills
                .iter()
                .any(|s| s == "migration-assistant-cli-reference"));
        }
        // If the layout differs in some checkout, the resolver returns None.
    }

    #[test]
    fn skills_src_honors_env_override() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("Startup.md"), "# startup").unwrap();
        // A cli_dir with no skills/ forces the env-override branch.
        let empty = tempfile::tempdir().unwrap();
        std::env::set_var("MIGRATE_SKILLS_SRC", tmp.path());
        let got = skills_src(empty.path());
        std::env::remove_var("MIGRATE_SKILLS_SRC");
        assert_eq!(got.as_deref(), Some(tmp.path()));
    }

    #[test]
    fn discover_lists_only_installed_candidates() {
        let r = MockRunner::new()
            .with_command("claude")
            .with_command("kiro");
        let found = discover(&r);
        assert!(found.contains(&"claude"));
        assert!(found.contains(&"kiro"));
        assert!(!found.contains(&"codex"));
    }

    #[test]
    fn discover_empty_when_none_installed() {
        let r = MockRunner::new();
        assert!(discover(&r).is_empty());
    }

    #[test]
    fn kiro_resolves_via_kiro_cli_binary() {
        let r = MockRunner::new().with_command("kiro-cli");
        assert_eq!(bin_for(&r, "kiro"), Some("kiro-cli"));
        assert!(discover(&r).contains(&"kiro"));
    }

    #[test]
    fn mcp_envvar_normalization() {
        assert_eq!(mcp_envvar("aws-mcp"), "AWS_MCP");
        assert_eq!(mcp_envvar("my.org/secrets"), "MY_ORG_SECRETS");
        assert_eq!(mcp_envvar("already_ok"), "ALREADY_OK");
    }

    #[test]
    fn missing_hints_excludes_installed() {
        let hints = missing_hints(&["claude"]);
        let names: Vec<&str> = hints.iter().map(|(n, _)| *n).collect();
        assert!(names.contains(&"codex"));
        assert!(names.contains(&"kiro"));
        assert!(!names.contains(&"claude"));
    }

    #[test]
    fn missing_hints_empty_when_all_present() {
        assert!(missing_hints(&["claude", "codex", "kiro"]).is_empty());
    }

    #[test]
    fn claude_session_detection() {
        let home = tempfile::tempdir().unwrap();
        let cwd = "/Users/me/migration-assistant-workspace/default";
        // No dir yet → false.
        assert!(!claude_has_session(home.path(), cwd));

        let key: String = cwd
            .chars()
            .map(|c| if c == '/' || c == '.' { '-' } else { c })
            .collect();
        let dir = home.path().join(".claude").join("projects").join(&key);
        std::fs::create_dir_all(&dir).unwrap();
        // Empty dir → still false.
        assert!(!claude_has_session(home.path(), cwd));
        // A .jsonl appears → true.
        std::fs::write(dir.join("sess1.jsonl"), "").unwrap();
        assert!(claude_has_session(home.path(), cwd));
    }

    #[test]
    fn exec_command_resume_vs_fresh() {
        assert_eq!(
            exec_command("claude", "claude", true, "hi"),
            vec!["claude", "--continue"]
        );
        assert_eq!(
            exec_command("claude", "claude", false, "hi"),
            vec!["claude", "hi"]
        );
        assert_eq!(
            exec_command("codex", "codex", true, "hi"),
            vec!["codex", "resume", "--last"]
        );
        assert_eq!(
            exec_command("kiro", "kiro-cli", true, "hi"),
            vec![
                "kiro-cli",
                "chat",
                "--agent",
                "opensearch-migration",
                "--resume"
            ]
        );
        assert_eq!(
            exec_command("kiro", "kiro-cli", false, "go"),
            vec!["kiro-cli", "chat", "--agent", "opensearch-migration", "go"]
        );
    }
}
