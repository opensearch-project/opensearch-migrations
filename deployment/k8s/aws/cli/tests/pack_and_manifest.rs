//! End-to-end `pack` + manifest/skills resolution.
//!
//! `pack` is exercised through its public entry point ([`pack_cmd::cmd_pack`])
//! with a real `tar` round-trip against a staged bundle, then the output tarball
//! is unpacked and inspected. The manifest/skills resolvers are checked against
//! that same on-disk layout, so the "does the CLI find its bundle" contract is
//! covered without mocking the filesystem.

use migration_assistant::manifest::Manifest;
use migration_assistant::pack_cmd;
use migration_assistant::runner::RealRunner;
use std::path::Path;

/// A minimal but valid v1 manifest with the two upstream modes + one MCP.
const BASE_MANIFEST: &str = r#"{
  "schemaVersion": 1,
  "branding": {
    "appName": "OpenSearch Migration Assistant",
    "binaryName": "migration-assistant",
    "modes": [
      {"id": "Manual", "label": "Manual", "description": "x", "default": true, "available": true},
      {"id": "Agent",  "label": "AI",     "description": "y", "default": false, "available": true}
    ]
  },
  "mcpServers": {
    "aws-mcp": {"command": "uvx", "args": ["mcp-proxy@latest", "AWS_REGION=${AWS_REGION}"], "agents": ["claude"]}
  },
  "build": {"name": "migration-assistant", "version": "0.0.0-dev", "packs": []}
}"#;

/// Stage a tarball that looks like a CLI release bundle:
/// `migration-assistant-cli-<ver>/{bin/,skills/,manifest.json}`. Returns the
/// tarball path inside `dir`.
fn stage_bundle(dir: &Path, ver: &str) -> std::path::PathBuf {
    let root = dir.join(format!("migration-assistant-cli-{ver}"));
    std::fs::create_dir_all(root.join("bin")).unwrap();
    std::fs::create_dir_all(root.join("skills")).unwrap();
    std::fs::write(
        root.join("bin/migration-assistant"),
        "#!/usr/bin/env bash\n",
    )
    .unwrap();
    std::fs::write(root.join("manifest.json"), BASE_MANIFEST).unwrap();
    std::fs::write(root.join("skills/manifest.json"), BASE_MANIFEST).unwrap();
    std::fs::write(root.join("skills/Startup.md"), "# Startup\n").unwrap();
    // One upstream skill dir.
    std::fs::create_dir_all(root.join("skills/migration-assistant-operator")).unwrap();
    std::fs::write(
        root.join("skills/migration-assistant-operator/SKILL.md"),
        "# op\n",
    )
    .unwrap();

    let tarball = dir.join(format!("migration-assistant-cli-{ver}.tar.gz"));
    let status = std::process::Command::new("tar")
        .args([
            "-czf",
            tarball.to_str().unwrap(),
            "-C",
            dir.to_str().unwrap(),
            &format!("migration-assistant-cli-{ver}"),
        ])
        .status()
        .unwrap();
    assert!(status.success(), "staging tar failed");
    tarball
}

#[test]
fn pack_adds_a_skill_rebrands_and_records_provenance() {
    let dir = tempfile::tempdir().unwrap();
    let input = stage_bundle(dir.path(), "3.2.1");

    // A custom skill to fold in.
    let skill = dir.path().join("myorg-runbook");
    std::fs::create_dir_all(&skill).unwrap();
    std::fs::write(skill.join("SKILL.md"), "# MyOrg\n").unwrap();

    let output = dir.path().join("out.tar.gz");
    pack_cmd::cmd_pack(
        &RealRunner,
        &[
            "--input".into(),
            input.to_str().unwrap().into(),
            "--add-skill".into(),
            skill.to_str().unwrap().into(),
            "--brand-binary".into(),
            "myorg-migrate".into(),
            "--pack-name".into(),
            "myorg".into(),
            "--pack-version".into(),
            "1.0.0".into(),
            "--output".into(),
            output.to_str().unwrap().into(),
        ],
    )
    .expect("pack should succeed");
    assert!(output.is_file(), "pack wrote an output tarball");

    // Unpack and inspect.
    let ex = dir.path().join("ex");
    std::fs::create_dir_all(&ex).unwrap();
    let status = std::process::Command::new("tar")
        .args(["-xzf", output.to_str().unwrap(), "-C", ex.to_str().unwrap()])
        .status()
        .unwrap();
    assert!(status.success());

    // Rebranding renames the top-level dir.
    let root = ex.join("myorg-migrate-cli-3.2.1");
    assert!(
        root.is_dir(),
        "binary rename should rename the tarball root"
    );
    // The added skill shipped.
    assert!(root.join("skills/myorg-runbook/SKILL.md").is_file());

    // The manifest carries the new binaryName + a build.packs[] provenance entry.
    let m: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(root.join("manifest.json")).unwrap())
            .unwrap();
    assert_eq!(m["branding"]["binaryName"], "myorg-migrate");
    assert_eq!(m["build"]["packs"][0]["name"], "myorg");
    assert_eq!(m["build"]["packs"][0]["version"], "1.0.0");
    // appliedAt is an ISO-8601 UTC timestamp.
    let applied = m["build"]["packs"][0]["appliedAt"].as_str().unwrap();
    assert!(
        applied.len() >= 11 && &applied[10..11] == "T",
        "ISO-8601 appliedAt, got {applied}"
    );
}

#[test]
fn pack_requires_pack_name_and_version() {
    let dir = tempfile::tempdir().unwrap();
    let input = stage_bundle(dir.path(), "3.2.1");
    let output = dir.path().join("out.tar.gz");

    // Missing --pack-name -> error.
    let err = pack_cmd::cmd_pack(
        &RealRunner,
        &[
            "--input".into(),
            input.to_str().unwrap().into(),
            "--output".into(),
            output.to_str().unwrap().into(),
        ],
    )
    .unwrap_err();
    assert!(err.message.contains("--pack-name"), "got: {}", err.message);
}

#[test]
fn manifest_resolves_and_parses_from_a_release_layout() {
    // A release tarball puts manifest.json at the bundle root (sibling of bin/).
    // `Manifest::load(cli_dir)` must find + parse it.
    let dir = tempfile::tempdir().unwrap();
    let cli_dir = dir.path().join("migration-assistant-cli-9.9.9");
    std::fs::create_dir_all(cli_dir.join("bin")).unwrap();
    std::fs::write(cli_dir.join("manifest.json"), BASE_MANIFEST).unwrap();

    let m = Manifest::load(&cli_dir)
        .unwrap()
        .expect("manifest must resolve at bundle root");
    let no_vars = |_: &str| None;
    assert_eq!(m.brand("binaryName", &no_vars), "migration-assistant");
    assert_eq!(
        m.visible_modes().first().map(|x| x.id.as_str()),
        Some("Manual")
    );
    assert!(m.mcp_names_for("claude").contains(&"aws-mcp"));

    // ${AWS_REGION} in the MCP args is substituted on demand.
    let with_region = |n: &str| (n == "AWS_REGION").then(|| "us-west-2".to_string());
    assert!(m
        .mcp_args("aws-mcp", &with_region)
        .iter()
        .any(|a| a == "AWS_REGION=us-west-2"));
}

#[test]
fn missing_manifest_resolves_to_none_not_an_error() {
    let dir = tempfile::tempdir().unwrap();
    // An empty dir with no manifest.json on any candidate path.
    assert!(Manifest::load(dir.path()).unwrap().is_none());
}
