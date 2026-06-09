//! Repack a CLI tarball with new skills, MCPs, and branding.
//!
//! Operates on `serde_json::Value` so the merge rules (MCP add with
//! last-write-wins, branding deep-merge with `modes[]` REPLACE, brand-flag
//! convenience setters, validation, and the `build.packs[]` append) are pure
//! and unit-testable. The tar extract/repack is orchestrated separately over a
//! [`CommandRunner`](crate::runner::CommandRunner).

use crate::error::{Error, Result};
use serde_json::{json, Value};

/// Deep-merge an MCP fragment (an object whose keys become `mcpServers`
/// entries) into the manifest. Last-write-wins on name collisions; returns the
/// names that were overwritten so the caller can warn.
pub fn add_mcp(manifest: &mut Value, fragment: &Value) -> Result<Vec<String>> {
    let frag_obj = fragment
        .as_object()
        .ok_or_else(|| Error::die("pack: --add-mcp file is not a JSON object"))?;
    let servers = manifest
        .as_object_mut()
        .and_then(|m| {
            m.entry("mcpServers").or_insert_with(|| json!({}));
            m.get_mut("mcpServers")
        })
        .and_then(Value::as_object_mut)
        .ok_or_else(|| Error::die("pack: manifest mcpServers is not an object"))?;

    let mut collisions = Vec::new();
    for (name, entry) in frag_obj {
        if servers.contains_key(name) {
            collisions.push(name.clone());
        }
        servers.insert(name.clone(), entry.clone());
    }
    Ok(collisions)
}

/// Deep-merge a branding fragment into `manifest.branding`. `modes[]` is
/// REPLACED (not merged) when the fragment declares it — array order matters
/// for the picker.
pub fn apply_branding(manifest: &mut Value, fragment: &Value) -> Result<()> {
    let frag = fragment
        .as_object()
        .ok_or_else(|| Error::die("pack: --branding file is not a JSON object"))?;
    let obj = manifest
        .as_object_mut()
        .ok_or_else(|| Error::die("pack: manifest is not an object"))?;
    let branding = obj.entry("branding").or_insert_with(|| json!({}));
    let branding = branding
        .as_object_mut()
        .ok_or_else(|| Error::die("pack: manifest branding is not an object"))?;
    for (k, v) in frag {
        if k == "modes" {
            branding.insert("modes".to_string(), v.clone()); // REPLACE
        } else {
            branding.insert(k.clone(), v.clone());
        }
    }
    Ok(())
}

/// Apply the convenience brand flags. Each is optional (empty = skip).
/// `mode_default` flips exactly one mode's `default` flag; `mode_order` is a
/// comma-separated id list that reorders the existing modes.
pub fn apply_brand_flags(
    manifest: &mut Value,
    name: &str,
    binary: &str,
    tagline: &str,
    mode_default: &str,
    mode_order: &str,
) -> Result<()> {
    let obj = manifest
        .as_object_mut()
        .ok_or_else(|| Error::die("pack: manifest is not an object"))?;
    let branding = obj.entry("branding").or_insert_with(|| json!({}));
    let branding = branding
        .as_object_mut()
        .ok_or_else(|| Error::die("pack: manifest branding is not an object"))?;

    for (flag, key) in [
        (name, "appName"),
        (binary, "binaryName"),
        (tagline, "tagline"),
    ] {
        if !flag.is_empty() {
            branding.insert(key.into(), json!(flag));
        }
    }
    if !mode_default.is_empty() {
        set_default_mode(branding, mode_default);
    }
    if !mode_order.is_empty() {
        reorder_modes(branding, mode_order);
    }
    Ok(())
}

/// Set `default: true` on the mode whose id is `default_id`, `false` on the rest.
fn set_default_mode(branding: &mut serde_json::Map<String, Value>, default_id: &str) {
    let Some(modes) = branding.get_mut("modes").and_then(Value::as_array_mut) else {
        return;
    };
    for m in modes.iter_mut() {
        if let Some(mo) = m.as_object_mut() {
            let is_default = mo.get("id").and_then(Value::as_str) == Some(default_id);
            mo.insert("default".into(), json!(is_default));
        }
    }
}

/// Reorder `branding.modes` to match the comma-separated `order` of ids,
/// dropping ids not present.
fn reorder_modes(branding: &mut serde_json::Map<String, Value>, order: &str) {
    let Some(modes) = branding.get("modes").and_then(Value::as_array).cloned() else {
        return;
    };
    let mode_by_id = |id: &str| {
        modes
            .iter()
            .find(|m| m.get("id").and_then(Value::as_str) == Some(id))
            .cloned()
    };
    let reordered: Vec<Value> = order.split(',').filter_map(mode_by_id).collect();
    branding.insert("modes".into(), Value::Array(reordered));
}

/// A validation finding from [`validate`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Finding {
    pub message: String,
    /// Whether this finding fails the build even without `--strict`.
    pub always_fatal: bool,
}

/// Validate the post-merge manifest:
///   * exactly one mode is default (warn; fatal under strict)
///   * no duplicate mode ids (always fatal)
///   * every visible mode id is a known dispatch target (Manual|Agent)
///     (warn; fatal under strict)
///   * binaryName matches `[a-z][a-z0-9-]*` (warn; fatal under strict)
///   * every MCP has command(string)/args(array)/agents(array) (always fatal)
pub fn validate(manifest: &Value) -> Vec<Finding> {
    let modes = manifest
        .get("branding")
        .and_then(|b| b.get("modes"))
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();

    let mut findings = Vec::new();
    findings.extend(check_single_default(&modes));
    findings.extend(check_duplicate_ids(&modes));
    findings.extend(check_known_dispatch_targets(&modes));
    findings.extend(check_binary_name(manifest));
    findings.extend(check_mcp_entries(manifest));
    findings
}

fn warn(message: String) -> Finding {
    Finding {
        message,
        always_fatal: false,
    }
}
fn fatal(message: String) -> Finding {
    Finding {
        message,
        always_fatal: true,
    }
}

/// Exactly one mode must be `default: true`.
fn check_single_default(modes: &[Value]) -> Option<Finding> {
    let count = modes
        .iter()
        .filter(|m| m.get("default").and_then(Value::as_bool) == Some(true))
        .count();
    (count != 1).then(|| {
        warn(format!(
            "{count} modes have default:true (expected exactly 1)"
        ))
    })
}

/// Mode ids must be unique.
fn check_duplicate_ids(modes: &[Value]) -> Vec<Finding> {
    let mut seen = std::collections::HashSet::new();
    modes
        .iter()
        .filter_map(|m| m.get("id").and_then(Value::as_str))
        .filter(|id| !seen.insert(id.to_string()))
        .map(|id| fatal(format!("duplicate mode id: {id}")))
        .collect()
}

/// Every visible mode id must be a known dispatch target (Manual|Agent).
fn check_known_dispatch_targets(modes: &[Value]) -> Vec<Finding> {
    modes
        .iter()
        .filter(|m| m.get("available").and_then(Value::as_bool) != Some(false))
        .filter_map(|m| m.get("id").and_then(Value::as_str))
        .filter(|id| *id != "Manual" && *id != "Agent")
        .map(|id| warn(format!("unknown mode id [{id}] — resume has no handler")))
        .collect()
}

/// `binaryName` must match `[a-z][a-z0-9-]*`.
fn check_binary_name(manifest: &Value) -> Option<Finding> {
    let bin = manifest
        .get("branding")
        .and_then(|b| b.get("binaryName"))
        .and_then(Value::as_str)
        .unwrap_or("migration-assistant");
    (!is_valid_binary_name(bin))
        .then(|| warn(format!("binaryName='{bin}' should match [a-z][a-z0-9-]*")))
}

/// Every MCP entry needs `command` (string), `args` (array), `agents` (array).
fn check_mcp_entries(manifest: &Value) -> Vec<Finding> {
    let Some(servers) = manifest.get("mcpServers").and_then(Value::as_object) else {
        return Vec::new();
    };
    servers
        .iter()
        .filter(|(_, srv)| !mcp_entry_is_valid(srv))
        .map(|(name, _)| {
            fatal(format!(
                "malformed MCP entry (need command, args, agents): {name}"
            ))
        })
        .collect()
}

fn mcp_entry_is_valid(srv: &Value) -> bool {
    srv.get("command").map(Value::is_string).unwrap_or(false)
        && srv.get("args").map(Value::is_array).unwrap_or(false)
        && srv.get("agents").map(Value::is_array).unwrap_or(false)
}

fn is_valid_binary_name(name: &str) -> bool {
    let mut chars = name.chars();
    match chars.next() {
        Some(c) if c.is_ascii_lowercase() => {}
        _ => return false,
    }
    chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
}

/// Append a `build.packs[]` entry recording the pack identity + additions.
/// `timestamp` is injected (ISO-8601 UTC) so this stays pure/testable.
/// Append a provenance record to `build.packs[]`.
pub fn append_pack_entry(
    manifest: &mut Value,
    name: &str,
    version: &str,
    timestamp: &str,
    added_skills: &[String],
    added_mcps: &[String],
    branding_changed: bool,
) -> Result<()> {
    let obj = manifest
        .as_object_mut()
        .ok_or_else(|| Error::die("pack: manifest is not an object"))?;
    let build = obj.entry("build").or_insert_with(|| json!({}));
    let build = build
        .as_object_mut()
        .ok_or_else(|| Error::die("pack: manifest build is not an object"))?;
    let packs = build.entry("packs").or_insert_with(|| json!([]));
    let packs = packs
        .as_array_mut()
        .ok_or_else(|| Error::die("pack: build.packs is not an array"))?;
    packs.push(json!({
        "name": name,
        "version": version,
        "appliedAt": timestamp,
        "addedSkills": added_skills,
        "addedMcpServers": added_mcps,
        "brandingChanged": branding_changed,
    }));
    Ok(())
}

/// Compute the renamed top-level tarball dir when `binaryName` is customized.
/// `migration-assistant-cli-3.2.1` + binary `myorg-migrate` →
/// `myorg-migrate-cli-3.2.1`. Returns the original when unchanged.
pub fn renamed_root(original_basename: &str, binary_name: &str) -> String {
    if binary_name == "migration-assistant" {
        return original_basename.to_string();
    }
    if let Some(rest) = original_basename.strip_prefix("migration-assistant-cli-") {
        format!("{binary_name}-cli-{rest}")
    } else {
        original_basename.to_string()
    }
}

/// The branding `binaryName`, defaulting to `migration-assistant`.
pub fn binary_name(manifest: &Value) -> String {
    manifest
        .get("branding")
        .and_then(|b| b.get("binaryName"))
        .and_then(Value::as_str)
        .unwrap_or("migration-assistant")
        .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base_manifest() -> Value {
        json!({
            "schemaVersion": 1,
            "branding": {
                "appName": "OpenSearch Migration Assistant",
                "binaryName": "migration-assistant",
                "modes": [
                    {"id": "Manual", "label": "Manual", "description": "x", "default": true, "available": true},
                    {"id": "Agent", "label": "AI", "description": "y", "default": false, "available": true}
                ]
            },
            "mcpServers": {
                "aws-mcp": {"command": "uvx", "args": ["x"], "agents": ["claude"]}
            },
            "build": {"name": "migration-assistant", "version": "3.2.1", "packs": []}
        })
    }

    #[test]
    fn add_mcp_merges_and_keeps_upstream() {
        let mut m = base_manifest();
        let frag = json!({"myorg-mcp": {"command": "uvx", "args": ["myorg@latest"], "agents": ["claude"]}});
        let collisions = add_mcp(&mut m, &frag).unwrap();
        assert!(collisions.is_empty());
        assert_eq!(m["mcpServers"]["myorg-mcp"]["command"], "uvx");
        assert!(
            m["mcpServers"]["aws-mcp"].is_object(),
            "upstream MCP preserved"
        );
    }

    #[test]
    fn add_mcp_reports_collisions() {
        let mut m = base_manifest();
        let frag = json!({"aws-mcp": {"command": "other", "args": [], "agents": []}});
        let collisions = add_mcp(&mut m, &frag).unwrap();
        assert_eq!(collisions, vec!["aws-mcp"]);
        assert_eq!(
            m["mcpServers"]["aws-mcp"]["command"], "other",
            "last write wins"
        );
    }

    #[test]
    fn branding_modes_replaced_not_merged() {
        let mut m = base_manifest();
        let frag = json!({"appName": "MyOrg", "modes": [{"id": "Manual", "default": true}]});
        apply_branding(&mut m, &frag).unwrap();
        assert_eq!(m["branding"]["appName"], "MyOrg");
        assert_eq!(m["branding"]["modes"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn brand_flags_set_binary_and_reorder_modes() {
        let mut m = base_manifest();
        apply_brand_flags(&mut m, "", "myorg-migrate", "", "Agent", "Agent,Manual").unwrap();
        assert_eq!(m["branding"]["binaryName"], "myorg-migrate");
        assert_eq!(m["branding"]["modes"][0]["id"], "Agent");
        assert_eq!(m["branding"]["modes"][0]["default"], true);
        assert_eq!(m["branding"]["modes"][1]["id"], "Manual");
        assert_eq!(m["branding"]["modes"][1]["default"], false);
    }

    #[test]
    fn validate_clean_manifest_has_no_findings() {
        assert!(validate(&base_manifest()).is_empty());
    }

    #[test]
    fn validate_catches_duplicate_default() {
        let mut m = base_manifest();
        m["branding"]["modes"][1]["default"] = json!(true);
        let findings = validate(&m);
        assert!(findings.iter().any(|f| f.message.contains("default:true")));
    }

    #[test]
    fn validate_catches_duplicate_ids_fatally() {
        let mut m = base_manifest();
        m["branding"]["modes"][1]["id"] = json!("Manual");
        let findings = validate(&m);
        assert!(findings
            .iter()
            .any(|f| f.message.contains("duplicate mode id") && f.always_fatal));
    }

    #[test]
    fn validate_catches_malformed_mcp_fatally() {
        let mut m = base_manifest();
        m["mcpServers"]["bad"] = json!({"command": 5});
        let findings = validate(&m);
        assert!(findings
            .iter()
            .any(|f| f.message.contains("malformed MCP") && f.always_fatal));
    }

    #[test]
    fn append_pack_entry_records_provenance() {
        let mut m = base_manifest();
        append_pack_entry(
            &mut m,
            "myorg-internal",
            "1.0.0",
            "2026-06-05T00:00:00Z",
            &[],
            &[],
            false,
        )
        .unwrap();
        let packs = m["build"]["packs"].as_array().unwrap();
        assert_eq!(packs.len(), 1);
        assert_eq!(packs[0]["name"], "myorg-internal");
        assert_eq!(packs[0]["version"], "1.0.0");
        assert_eq!(packs[0]["appliedAt"], "2026-06-05T00:00:00Z");
    }

    #[test]
    fn renamed_root_when_binary_customized() {
        assert_eq!(
            renamed_root("migration-assistant-cli-3.2.1", "myorg-migrate"),
            "myorg-migrate-cli-3.2.1"
        );
        assert_eq!(
            renamed_root("migration-assistant-cli-3.2.1", "migration-assistant"),
            "migration-assistant-cli-3.2.1"
        );
    }
}
