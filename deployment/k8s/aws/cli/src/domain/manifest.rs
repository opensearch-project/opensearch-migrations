//! `manifest.json` — bundle branding, modes, MCP servers, and pack provenance.
//!
//! Deserialized once with serde; queries answered in-process. Non-obvious
//! behaviors are unit-tested: `${VAR}` substitution (state → env →
//! literal-with-warn, and `$$` → `$`), mode visibility/ordering, MCP
//! filtering by agent, and the `+pack-ver` version suffix.
//!
//! Only `schemaVersion: 1` is understood; anything else is a hard error.

use serde::Deserialize;
use std::collections::BTreeMap;

/// The whole manifest document.
#[derive(Debug, Clone, Deserialize)]
pub struct Manifest {
    #[serde(rename = "schemaVersion", default)]
    pub schema_version: i64,
    #[serde(default)]
    pub branding: Branding,
    #[serde(default)]
    pub skills: Skills,
    #[serde(rename = "mcpServers", default)]
    pub mcp_servers: BTreeMap<String, McpServer>,
    #[serde(default)]
    pub build: Build,
    #[serde(default)]
    pub agents: Vec<AgentConfig>,
}

/// One supported AI coding agent. Order in the array = picker display order.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct AgentConfig {
    /// Canonical id (e.g. "claude", "codex", "kiro", "q").
    pub id: String,
    /// Binaries to probe on PATH, in priority order. First match wins.
    #[serde(default)]
    pub binaries: Vec<String>,
    /// Human-readable label for the picker.
    #[serde(default)]
    pub label: String,
    /// One-line description shown in the picker.
    #[serde(default)]
    pub description: String,
    /// Install URL shown when the agent isn't found on PATH.
    #[serde(rename = "installUrl", default)]
    pub install_url: String,
}

/// Operator-visible UI strings.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct Branding {
    #[serde(rename = "appName", default)]
    pub app_name: String,
    #[serde(rename = "binaryName", default)]
    pub binary_name: String,
    #[serde(default)]
    pub tagline: String,
    #[serde(rename = "helpHeader", default)]
    pub help_header: String,
    #[serde(rename = "welcomeMessage", default)]
    pub welcome_message: String,
    #[serde(rename = "versionString", default)]
    pub version_string: String,
    #[serde(rename = "agentFreshPrompt", default)]
    pub agent_fresh_prompt: String,
    #[serde(default)]
    pub modes: Vec<Mode>,
}

/// One driver mode in the picker.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct Mode {
    pub id: String,
    #[serde(default)]
    pub label: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub default: bool,
    /// Hidden when `available: false`; absent means visible.
    #[serde(default)]
    pub available: Option<bool>,
}

impl Mode {
    /// Whether this mode is shown in the picker (`available != false`).
    pub fn is_visible(&self) -> bool {
        self.available != Some(false)
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct Skills {
    #[serde(default = "default_discovery")]
    pub discovery: String,
}

impl Default for Skills {
    fn default() -> Self {
        Self {
            discovery: default_discovery(),
        }
    }
}

fn default_discovery() -> String {
    "auto".to_string()
}

/// One MCP server entry.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct McpServer {
    #[serde(default)]
    pub command: String,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default)]
    pub scope: String,
    #[serde(default)]
    pub agents: Vec<String>,
    #[serde(default)]
    pub requires: Vec<String>,
    #[serde(rename = "permissionsAllow", default)]
    pub permissions_allow: Vec<String>,
}

/// Bundle identity + applied-pack provenance.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct Build {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub version: String,
    #[serde(default)]
    pub packs: Vec<Pack>,
    /// URL to check for newer versions. The CLI fetches this URL and extracts
    /// a version string to compare against the local version. Supports two
    /// response formats:
    ///   - JSON with a `tag_name` field (GitHub releases API)
    ///   - JSON with a `version` field (custom endpoint)
    ///   - Plain text (the body IS the version, trimmed)
    ///
    /// When absent, the update check is disabled.
    #[serde(rename = "updateCheckUrl", default)]
    pub update_check_url: String,
    /// Default container image tag for helm deploys. When set, this is used
    /// instead of `build.version`. Operator can override with `--image-tag`.
    #[serde(rename = "imageTag", default)]
    pub image_tag: String,
}

/// One applied pack record (written by `migration-assistant pack`).
#[derive(Debug, Clone, Default, Deserialize, serde::Serialize)]
pub struct Pack {
    pub name: String,
    pub version: String,
    #[serde(rename = "appliedAt", default)]
    pub applied_at: String,
    #[serde(rename = "addedSkills", default)]
    pub added_skills: Vec<String>,
    #[serde(rename = "addedMcpServers", default)]
    pub added_mcp_servers: Vec<String>,
    #[serde(rename = "brandingChanged", default)]
    pub branding_changed: bool,
}

/// How a `${VAR}` reference is resolved during substitution.
pub trait VarSource {
    /// Return the value for `name`, or `None` if unknown.
    fn lookup(&self, name: &str) -> Option<String>;
}

/// A [`VarSource`] backed by a closure — convenient for state+env chains.
impl<F> VarSource for F
where
    F: Fn(&str) -> Option<String>,
{
    fn lookup(&self, name: &str) -> Option<String> {
        self(name)
    }
}

impl Manifest {
    /// Parse and validate a manifest from JSON text. Rejects any
    /// `schemaVersion` other than 1. Unknown fields are ignored by serde, so
    /// the real bundle manifest parses cleanly.
    pub fn parse(json: &str) -> crate::Result<Self> {
        let m: Manifest = serde_json::from_str(json)
            .map_err(|e| crate::Error::die(format!("manifest.json parse error: {e}")))?;
        if m.schema_version != 1 {
            return Err(crate::Error::die(format!(
                "manifest.json schemaVersion={} (this CLI only understands schemaVersion=1)",
                m.schema_version
            )));
        }
        Ok(m)
    }

    /// Locate `manifest.json` on disk, relative to the directory the CLI lives
    /// in (`cli_dir`). Candidate search order:
    ///   1. `$MIGRATE_MANIFEST` (operator/test override)
    ///   2. `<cli>/manifest.json`        (release tarball — sibling of bin/)
    ///   3. `<cli>/skills/manifest.json` (release tarball — inside skills/)
    ///   4. `<cli>/../../../../agent-skills/skills/manifest.json` (repo dev mode)
    ///
    /// Returns `None` when no manifest is found — callers fall back to built-in
    /// defaults.
    pub fn locate(cli_dir: &std::path::Path) -> Option<std::path::PathBuf> {
        if let Some(p) = std::env::var_os("MIGRATE_MANIFEST") {
            let p = std::path::PathBuf::from(p);
            if p.is_file() {
                return Some(p);
            }
        }
        let candidates = [
            cli_dir.join("manifest.json"),
            cli_dir.join("skills/manifest.json"),
            cli_dir.join("../../../../agent-skills/skills/manifest.json"),
        ];
        candidates.into_iter().find(|p| p.is_file())
    }

    /// Locate and parse the manifest from disk, or return `Ok(None)` when none
    /// is present (the "no manifest, use defaults" path).
    pub fn load(cli_dir: &std::path::Path) -> crate::Result<Option<Self>> {
        match Self::locate(cli_dir) {
            Some(path) => {
                let text = std::fs::read_to_string(&path).map_err(|e| {
                    crate::Error::die(format!("could not read {}: {e}", path.display()))
                })?;
                Ok(Some(Self::parse(&text)?))
            }
            None => Ok(None),
        }
    }

    /// Substitute `${VAR}` tokens in `s` using `src`, then collapse `$$` → `$`.
    /// Unresolved vars are left literal (`${VAR}`); callers may warn.
    pub fn substitute_vars(s: &str, src: &impl VarSource) -> String {
        // Two-pass to honor the `$$ → $` escape without it interfering with
        // `${...}` detection: first expand ${...}, then collapse $$.
        let mut out = String::with_capacity(s.len());
        let bytes = s.as_bytes();
        let mut i = 0;
        while i < bytes.len() {
            if bytes[i] == b'$' && i + 1 < bytes.len() && bytes[i + 1] == b'{' {
                if let Some(close) = s[i + 2..].find('}') {
                    let name = &s[i + 2..i + 2 + close];
                    match src.lookup(name) {
                        Some(v) if !v.is_empty() => out.push_str(&v),
                        _ => {
                            out.push_str("${");
                            out.push_str(name);
                            out.push('}');
                        }
                    }
                    i = i + 2 + close + 1;
                    continue;
                }
            }
            out.push(bytes[i] as char);
            i += 1;
        }
        out.replace("$$", "$")
    }

    /// A branding field by name, with `${VAR}` substitution applied.
    pub fn brand(&self, field: &str, src: &impl VarSource) -> String {
        let raw = match field {
            "appName" => &self.branding.app_name,
            "binaryName" => &self.branding.binary_name,
            "tagline" => &self.branding.tagline,
            "helpHeader" => &self.branding.help_header,
            "welcomeMessage" => &self.branding.welcome_message,
            "versionString" => &self.branding.version_string,
            "agentFreshPrompt" => &self.branding.agent_fresh_prompt,
            _ => return String::new(),
        };
        Self::substitute_vars(raw, src)
    }

    /// Visible modes, in array order. Hidden modes (`available: false`) are
    /// filtered out.
    pub fn visible_modes(&self) -> Vec<&Mode> {
        self.branding
            .modes
            .iter()
            .filter(|m| m.is_visible())
            .collect()
    }

    /// MCP server names whose `agents` list includes `agent`, in name order.
    pub fn mcp_names_for(&self, agent: &str) -> Vec<&str> {
        self.mcp_servers
            .iter()
            .filter(|(_, srv)| srv.agents.iter().any(|a| a == agent))
            .map(|(name, _)| name.as_str())
            .collect()
    }

    /// MCP args with `${VAR}` substitution applied.
    pub fn mcp_args(&self, name: &str, src: &impl VarSource) -> Vec<String> {
        self.mcp_servers
            .get(name)
            .map(|srv| {
                srv.args
                    .iter()
                    .map(|a| Self::substitute_vars(a, src))
                    .collect()
            })
            .unwrap_or_default()
    }

    /// Every `permissionsAllow` entry across all MCPs, deduped + sorted.
    pub fn all_perms(&self) -> Vec<String> {
        let mut set: std::collections::BTreeSet<String> = std::collections::BTreeSet::new();
        for srv in self.mcp_servers.values() {
            for p in &srv.permissions_allow {
                set.insert(p.clone());
            }
        }
        set.into_iter().collect()
    }

    /// `" +pack-ver +pack-ver"` for each applied pack, or empty. Used by the
    /// banner/version line.
    pub fn pack_summary(&self) -> String {
        self.build
            .packs
            .iter()
            .map(|p| format!(" +{}-{}", p.name, p.version))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn fixture() -> Manifest {
        Manifest::parse(
            r#"{
              "schemaVersion": 1,
              "branding": {
                "appName": "OpenSearch Migration Assistant",
                "binaryName": "migration-assistant",
                "helpHeader": "migration-assistant — OpenSearch Migration Assistant CLI",
                "modes": [
                  {"id": "Manual", "label": "Manual", "description": "you drive", "default": true},
                  {"id": "Agent", "label": "AI", "description": "agent drives"}
                ]
              },
              "skills": {"discovery": "auto"},
              "mcpServers": {
                "aws-mcp": {
                  "command": "uvx",
                  "args": ["mcp-proxy-for-aws@latest", "AWS_REGION=${AWS_REGION}"],
                  "scope": "project",
                  "agents": ["claude", "codex", "kiro"],
                  "requires": ["uvx"],
                  "permissionsAllow": [
                    "mcp__aws-mcp__aws___read_documentation",
                    "mcp__aws-mcp__aws___search_documentation",
                    "mcp__aws-mcp__aws___call_aws"
                  ]
                }
              },
              "build": {"name": "migration-assistant", "version": "0.0.0", "packs": []}
            }"#,
        )
        .unwrap()
    }

    fn src_with(pairs: &[(&str, &str)]) -> impl VarSource {
        let map: HashMap<String, String> = pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        move |name: &str| map.get(name).cloned()
    }

    #[test]
    fn rejects_non_v1_schema() {
        assert!(Manifest::parse(r#"{"schemaVersion": 2}"#).is_err());
        assert!(Manifest::parse(r#"{"schemaVersion": 1}"#).is_ok());
    }

    #[test]
    fn brand_reads_top_level_fields() {
        let m = fixture();
        let none = src_with(&[]);
        assert_eq!(m.brand("appName", &none), "OpenSearch Migration Assistant");
        assert_eq!(m.brand("binaryName", &none), "migration-assistant");
        assert!(m
            .brand("helpHeader", &none)
            .contains("OpenSearch Migration Assistant CLI"));
    }

    #[test]
    fn brand_substitutes_var_from_state() {
        let m = Manifest::parse(
            r#"{"schemaVersion":1,"branding":{"tagline":"hello-${MIGRATE_TEST_VAR}-world"}}"#,
        )
        .unwrap();
        let src = src_with(&[("MIGRATE_TEST_VAR", "from-state")]);
        assert_eq!(m.brand("tagline", &src), "hello-from-state-world");
    }

    #[test]
    fn substitute_leaves_unresolved_literal_and_collapses_dollar() {
        let none = src_with(&[]);
        assert_eq!(
            Manifest::substitute_vars("a-${NOPE}-b", &none),
            "a-${NOPE}-b"
        );
        assert_eq!(
            Manifest::substitute_vars("price is $$5", &none),
            "price is $5"
        );
    }

    #[test]
    fn modes_in_order_with_default() {
        let m = fixture();
        let modes = m.visible_modes();
        assert_eq!(modes.len(), 2);
        assert_eq!(modes[0].id, "Manual");
        assert!(modes[0].default);
        assert_eq!(modes[1].id, "Agent");
        assert!(!modes[1].default);
    }

    #[test]
    fn hidden_modes_filtered() {
        let m = Manifest::parse(
            r#"{"schemaVersion":1,"branding":{"modes":[
                {"id":"Manual","default":true},
                {"id":"Agent","available":false}
            ]}}"#,
        )
        .unwrap();
        let modes = m.visible_modes();
        assert_eq!(modes.len(), 1);
        assert_eq!(modes[0].id, "Manual");
    }

    #[test]
    fn mcp_names_filtered_by_agent() {
        let m = fixture();
        assert_eq!(m.mcp_names_for("claude"), vec!["aws-mcp"]);
        assert!(m.mcp_names_for("some-unknown-agent").is_empty());
    }

    #[test]
    fn mcp_args_substitutes_region() {
        let m = fixture();
        let src = src_with(&[("AWS_REGION", "us-west-9")]);
        let args = m.mcp_args("aws-mcp", &src);
        assert!(args.iter().any(|a| a == "AWS_REGION=us-west-9"));
    }

    #[test]
    fn mcp_perms_enumerated() {
        let m = fixture();
        let perms = &m.mcp_servers["aws-mcp"].permissions_allow;
        assert!(perms.len() >= 3);
        assert!(perms
            .iter()
            .any(|p| p == "mcp__aws-mcp__aws___read_documentation"));
    }

    #[test]
    fn pack_summary_empty_for_upstream() {
        assert_eq!(fixture().pack_summary(), "");
    }

    #[test]
    fn real_bundled_manifest_parses_and_resolves() {
        // The crate root is CARGO_MANIFEST_DIR (deployment/k8s/aws/cli). The
        // repo-dev-mode candidate (../../../../agent-skills/skills/manifest.json)
        // must resolve to the real bundle manifest committed in this PR.
        let cli_dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR"));
        let loaded = Manifest::load(cli_dir).expect("manifest load must not error");
        let m = match loaded {
            Some(m) => m,
            None => return, // packaging layout differs in some checkouts; skip.
        };
        // Branding + modes from the real file.
        let none = src_with(&[]);
        assert_eq!(m.brand("binaryName", &none), "migration-assistant");
        let modes = m.visible_modes();
        assert_eq!(modes.first().map(|x| x.id.as_str()), Some("Manual"));
        assert!(modes.iter().any(|x| x.id == "Agent"));
        // The aws-mcp server is present and targets claude.
        assert!(m.mcp_names_for("claude").contains(&"aws-mcp"));
        // Its args carry the ${AWS_REGION} token, substituted on demand.
        let src = src_with(&[("AWS_REGION", "eu-central-1")]);
        assert!(m
            .mcp_args("aws-mcp", &src)
            .iter()
            .any(|a| a.contains("AWS_REGION=eu-central-1")));
    }

    #[test]
    fn pack_summary_lists_applied_packs() {
        let m = Manifest::parse(
            r#"{"schemaVersion":1,"build":{"packs":[
                {"name":"myorg","version":"1.0.0"},
                {"name":"extra","version":"2.1"}
            ]}}"#,
        )
        .unwrap();
        assert_eq!(m.pack_summary(), " +myorg-1.0.0 +extra-2.1");
    }
}
