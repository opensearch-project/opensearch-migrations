//! `migration-assistant pack` orchestration — the filesystem/tar side of
//! repacking, on top of the pure merge logic in [`crate::pack`].
//!
//! Parse flags, extract the input tarball (via the runner's
//! `tar`), apply skill/MCP/branding merges, validate, append the pack entry,
//! and re-tar to the output (renaming the top dir when the binary name
//! changed). The pure merges are unit-tested in [`crate::pack`]; this module
//! handles argument parsing + IO and is covered by the integration tests.

use crate::error::{Error, Result};
use crate::pack;
use crate::runner::CommandRunner;
use crate::ui;
use serde_json::Value;
use std::path::{Path, PathBuf};

/// Parsed `pack` flags.
#[derive(Debug, Default)]
struct PackArgs {
    input: String,
    output: String,
    pack_name: String,
    pack_version: String,
    branding_file: String,
    add_skills: Vec<String>,
    add_mcps: Vec<String>,
    brand_name: String,
    brand_binary: String,
    brand_tagline: String,
    mode_default: String,
    mode_order: String,
    strict: bool,
    help: bool,
}

fn parse_args(args: &[String]) -> Result<PackArgs> {
    let mut p = PackArgs::default();
    let mut i = 0;
    while i < args.len() {
        i += apply_token(&mut p, args, i)?;
    }
    Ok(p)
}

/// Classify and apply the flag at `args[i]`, returning how many argv tokens it
/// consumed (1 for a bare/`=`-joined flag, 2 when the value is the next token).
/// Lifting this out of the parse loop keeps both functions flat (and testable).
fn apply_token(p: &mut PackArgs, args: &[String], i: usize) -> Result<usize> {
    let (flag, eq) = match args[i].split_once('=') {
        Some((f, v)) => (f, Some(v.to_string())),
        None => (args[i].as_str(), None),
    };
    // A value is either after `=` or the next argv token (which we'd consume).
    let value = || -> Option<String> { eq.clone().or_else(|| args.get(i + 1).cloned()) };
    let consumes_next = eq.is_none();

    // Single-value flags: map flag name → the field it sets.
    if let Some(slot) = value_slot(p, flag) {
        *slot = value().unwrap_or_default();
    } else if flag == "--add-skill" {
        if let Some(v) = value() {
            p.add_skills.push(v);
        }
    } else if flag == "--add-mcp" {
        if let Some(v) = value() {
            p.add_mcps.push(v);
        }
    } else if flag == "--strict" {
        p.strict = true;
        return Ok(1); // bool flag never consumes a value
    } else if flag == "-h" || flag == "--help" {
        p.help = true;
        return Ok(1);
    } else {
        return Err(Error::die(format!(
            "pack: unknown flag: {flag} (try --help)"
        )));
    }
    Ok(if consumes_next { 2 } else { 1 })
}

/// Mutable reference to the single-value field a flag sets, or `None` if the
/// flag isn't a simple value flag. Table-driven so adding a flag is one line.
fn value_slot<'a>(p: &'a mut PackArgs, flag: &str) -> Option<&'a mut String> {
    Some(match flag {
        "--input" => &mut p.input,
        "--output" => &mut p.output,
        "--pack-name" => &mut p.pack_name,
        "--pack-version" => &mut p.pack_version,
        "--branding" => &mut p.branding_file,
        "--brand-name" => &mut p.brand_name,
        "--brand-binary" => &mut p.brand_binary,
        "--brand-tagline" => &mut p.brand_tagline,
        "--mode-default" => &mut p.mode_default,
        "--mode-order" => &mut p.mode_order,
        _ => return None,
    })
}

/// `cmd_pack` entry point.
pub fn cmd_pack<R: CommandRunner>(runner: &R, args: &[String]) -> Result<()> {
    let p = parse_args(args)?;
    if p.help {
        print!("{}", help_text());
        return Ok(());
    }
    validate_args(&p, runner)?;

    ui::step(&format!("Repacking: {} → {}", p.input, p.output));

    let stage = tempdir()?;
    let root = extract_bundle(runner, &p.input, &stage)?;
    let manifest_path = root.join("manifest.json");
    let mut manifest: Value = serde_json::from_str(&std::fs::read_to_string(&manifest_path)?)
        .map_err(|e| Error::die(format!("pack: manifest parse error: {e}")))?;

    for skill_dir in &p.add_skills {
        add_skill(&root, skill_dir)?;
    }
    apply_mcps(&mut manifest, &p.add_mcps)?;
    let branding_changed = apply_branding_opts(&mut manifest, &p)?;
    run_validation(&manifest, p.strict)?;

    let added_skill_names: Vec<String> = p.add_skills.iter().map(|s| basename(s)).collect();
    pack::append_pack_entry(
        &mut manifest,
        &p.pack_name,
        &p.pack_version,
        &iso8601_utc_now(),
        &added_skill_names,
        &mcp_names(&p.add_mcps),
        branding_changed,
    )?;
    std::fs::write(
        &manifest_path,
        serde_json::to_string_pretty(&manifest).unwrap(),
    )?;

    let bin = pack::binary_name(&manifest);
    repack(runner, &stage, root, &bin, &p.output)?;
    let _ = std::fs::remove_dir_all(&stage);

    ui::ok(&format!("wrote {}", p.output));
    ui::dim(&format!("  binary: {bin}"));
    Ok(())
}

/// Validate required args + the `tar` prerequisite before doing any work.
fn validate_args<R: CommandRunner>(p: &PackArgs, runner: &R) -> Result<()> {
    let required = [
        (p.input.is_empty(), "pack: --input <tarball> is required"),
        (p.output.is_empty(), "pack: --output <path> is required"),
        (
            p.pack_name.is_empty(),
            "pack: --pack-name <name> is required",
        ),
        (
            p.pack_version.is_empty(),
            "pack: --pack-version <ver> is required",
        ),
    ];
    if let Some((_, msg)) = required.into_iter().find(|(missing, _)| *missing) {
        return Err(Error::die(msg));
    }
    if !Path::new(&p.input).is_file() {
        return Err(Error::die(format!("pack: --input not found: {}", p.input)));
    }
    if !runner.has_command("tar") {
        return Err(Error::die("pack: tar required on PATH"));
    }
    Ok(())
}

/// Extract the input tarball into `stage` and return the single bundle root
/// (the dir containing `manifest.json`).
fn extract_bundle<R: CommandRunner>(runner: &R, input: &str, stage: &Path) -> Result<PathBuf> {
    let abs_input = std::fs::canonicalize(input)?;
    let ok = runner
        .run(
            "tar",
            &[
                "-xzf",
                abs_input.to_str().unwrap(),
                "-C",
                stage.to_str().unwrap(),
            ],
        )
        .success();
    if !ok {
        return Err(Error::die(format!("pack: could not extract {input}")));
    }
    let root = resolve_root(stage)?;
    if !root.join("manifest.json").is_file() {
        return Err(Error::die(
            "pack: input has no manifest.json — is this a v1 bundle?",
        ));
    }
    Ok(root)
}

/// Merge each `--add-mcp` fragment into the manifest, warning on collisions.
fn apply_mcps(manifest: &mut Value, mcp_files: &[String]) -> Result<()> {
    for mcp_file in mcp_files {
        if !Path::new(mcp_file).is_file() {
            return Err(Error::die(format!(
                "pack: --add-mcp file not found: {mcp_file}"
            )));
        }
        let frag: Value =
            serde_json::from_str(&std::fs::read_to_string(mcp_file)?).map_err(|_| {
                Error::die(format!(
                    "pack: --add-mcp file is not valid JSON: {mcp_file}"
                ))
            })?;
        for name in pack::add_mcp(manifest, &frag)? {
            ui::warn(&format!("pack: MCP '{name}' already in bundle — replacing"));
        }
    }
    Ok(())
}

/// Apply `--branding` and the brand-flag overrides. Returns whether branding
/// changed (recorded in the pack-entry provenance).
fn apply_branding_opts(manifest: &mut Value, p: &PackArgs) -> Result<bool> {
    let mut changed = false;
    if !p.branding_file.is_empty() {
        if !Path::new(&p.branding_file).is_file() {
            return Err(Error::die(format!(
                "pack: --branding file not found: {}",
                p.branding_file
            )));
        }
        let frag: Value = serde_json::from_str(&std::fs::read_to_string(&p.branding_file)?)
            .map_err(|_| Error::die("pack: --branding file is not valid JSON"))?;
        pack::apply_branding(manifest, &frag)?;
        changed = true;
    }
    let has_brand_flags = ![
        &p.brand_name,
        &p.brand_binary,
        &p.brand_tagline,
        &p.mode_default,
        &p.mode_order,
    ]
    .iter()
    .all(|s| s.is_empty());
    if has_brand_flags {
        pack::apply_brand_flags(
            manifest,
            &p.brand_name,
            &p.brand_binary,
            &p.brand_tagline,
            &p.mode_default,
            &p.mode_order,
        )?;
        changed = true;
    }
    Ok(changed)
}

/// Run manifest validation, printing warnings and failing on fatal findings
/// (or any finding under `--strict`).
fn run_validation(manifest: &Value, strict: bool) -> Result<()> {
    let findings = pack::validate(manifest);
    for f in &findings {
        ui::warn(&format!("pack: {}", f.message));
    }
    let fatal = findings.iter().filter(|f| f.always_fatal || strict).count();
    if fatal > 0 {
        return Err(Error::die(format!(
            "pack: validation failed with {fatal} error(s)"
        )));
    }
    Ok(())
}

/// Re-tar the bundle to `output`, renaming the top dir when the binary name
/// changed so the tarball self-describes.
fn repack<R: CommandRunner>(
    runner: &R,
    stage: &Path,
    root: PathBuf,
    bin: &str,
    output: &str,
) -> Result<()> {
    let orig_base = root.file_name().unwrap().to_string_lossy().to_string();
    let new_base = pack::renamed_root(&orig_base, bin);
    if new_base != orig_base {
        std::fs::rename(&root, stage.join(&new_base))?;
    }
    if let Some(parent) = Path::new(output).parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)?;
        }
    }
    let abs_output = absolutize(output);
    let ok = runner
        .run(
            "tar",
            &[
                "-czf",
                &abs_output,
                "-C",
                stage.to_str().unwrap(),
                &new_base,
            ],
        )
        .success();
    if !ok {
        return Err(Error::die(format!("pack: tar -czf {output} failed")));
    }
    Ok(())
}

fn add_skill(root: &Path, skill_dir: &str) -> Result<()> {
    let src = Path::new(skill_dir);
    if !src.is_dir() {
        return Err(Error::die(format!(
            "pack: --add-skill not a directory: {skill_dir}"
        )));
    }
    if !src.join("SKILL.md").is_file() {
        return Err(Error::die(format!(
            "pack: --add-skill has no SKILL.md: {skill_dir}"
        )));
    }
    // Recursive copy in pure Rust — no `cp` binary dependency.
    let dest = root.join("skills").join(basename(skill_dir));
    copy_dir_recursive(src, &dest)?;
    ui::dim(&format!("  +skill: {}", basename(skill_dir)));
    Ok(())
}

/// Copy a directory tree from `src` to `dest` (creating `dest`), using only the
/// standard library.
fn copy_dir_recursive(src: &Path, dest: &Path) -> Result<()> {
    std::fs::create_dir_all(dest)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let from = entry.path();
        let to = dest.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_dir_recursive(&from, &to)?;
        } else {
            std::fs::copy(&from, &to)?;
        }
    }
    Ok(())
}

fn resolve_root(stage: &Path) -> Result<std::path::PathBuf> {
    let mut candidates = Vec::new();
    for entry in std::fs::read_dir(stage)? {
        let path = entry?.path();
        if path.is_dir()
            && (path.join("manifest.json").is_file()
                || path.join("skills/manifest.json").is_file()
                || path.join("lib").is_dir())
        {
            candidates.push(path);
        }
    }
    if candidates.len() == 1 {
        Ok(candidates.pop().unwrap())
    } else {
        Err(Error::die(
            "pack: input tarball does not look like a CLI bundle (no manifest.json found)",
        ))
    }
}

fn mcp_names(files: &[String]) -> Vec<String> {
    let mut names = Vec::new();
    for f in files {
        if let Ok(text) = std::fs::read_to_string(f) {
            if let Ok(Value::Object(map)) = serde_json::from_str::<Value>(&text) {
                names.extend(map.keys().cloned());
            }
        }
    }
    names
}

fn basename(path: &str) -> String {
    Path::new(path)
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| path.to_string())
}

fn absolutize(path: &str) -> String {
    let p = Path::new(path);
    if p.is_absolute() {
        path.to_string()
    } else {
        std::env::current_dir()
            .unwrap_or_default()
            .join(p)
            .to_string_lossy()
            .to_string()
    }
}

fn tempdir() -> Result<std::path::PathBuf> {
    let base = std::env::temp_dir();
    // Use the PID + a monotonic counter substitute to avoid Math.random.
    let unique = format!("ma-pack-{}", std::process::id());
    let dir = base.join(unique);
    // If it exists from a prior run, clear it.
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir)?;
    Ok(dir)
}

/// ISO-8601 UTC timestamp `YYYY-MM-DDTHH:MM:SSZ`, computed from the system
/// clock. Kept tiny + dependency-free (no chrono): a civil-time conversion of
/// the Unix epoch seconds. Matches `date -u +%FT%TZ`.
fn iso8601_utc_now() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    epoch_to_iso8601(secs)
}

/// Convert Unix epoch seconds to an ISO-8601 UTC string. Civil-date algorithm
/// (Howard Hinnant's days_from_civil inverse), valid for all years.
fn epoch_to_iso8601(secs: u64) -> String {
    let days = (secs / 86_400) as i64;
    let rem = secs % 86_400;
    let (hh, mm, ss) = (rem / 3600, (rem % 3600) / 60, rem % 60);

    // days since 1970-01-01 → civil date.
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365; // [0, 399]
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11]
    let d = doy - (153 * mp + 2) / 5 + 1; // [1, 31]
    let m = if mp < 10 { mp + 3 } else { mp - 9 }; // [1, 12]
    let year = if m <= 2 { y + 1 } else { y };

    format!("{year:04}-{m:02}-{d:02}T{hh:02}:{mm:02}:{ss:02}Z")
}

fn help_text() -> &'static str {
    "migration-assistant pack — repack a CLI tarball with new skills + MCPs + branding.\n\n\
Usage:\n\
\x20 migration-assistant pack [flags]\n\n\
Required:\n\
\x20 --input <path>          Source tarball (an existing CLI release).\n\
\x20 --output <path>         Target tarball.\n\
\x20 --pack-name <name>      Identifier recorded in build.packs[].\n\
\x20 --pack-version <ver>    Version recorded with the pack.\n\n\
Optional content:\n\
\x20 --add-skill <dir>       Add a skill directory (must contain SKILL.md). Repeatable.\n\
\x20 --add-mcp <file.json>   Add MCP definitions. Repeatable.\n\
\x20 --branding <file.json>  Deep-merge a branding fragment into the manifest.\n\n\
Optional convenience flags:\n\
\x20 --brand-name <name>     Set branding.appName.\n\
\x20 --brand-binary <name>   Set branding.binaryName.\n\
\x20 --brand-tagline <text>  Set branding.tagline.\n\
\x20 --mode-default <id>     Pick a single mode id (Manual|Agent) as default.\n\
\x20 --mode-order id1,id2    Reorder branding.modes[].\n\
\x20 --strict                Treat validation warnings as errors.\n"
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn epoch_to_iso8601_known_values() {
        assert_eq!(epoch_to_iso8601(0), "1970-01-01T00:00:00Z");
        // 2021-01-01T00:00:00Z = 1609459200
        assert_eq!(epoch_to_iso8601(1_609_459_200), "2021-01-01T00:00:00Z");
        // A timestamp with time-of-day: 2026-06-05T12:34:56Z = 1780662896
        assert_eq!(epoch_to_iso8601(1_780_662_896), "2026-06-05T12:34:56Z");
    }

    #[test]
    fn iso8601_now_has_expected_shape() {
        let ts = iso8601_utc_now();
        // YYYY-MM-DDT... shape (matched loosely without a regex dep).
        assert!(regex_ish(&ts), "unexpected ts: {ts}");
    }

    fn regex_ish(ts: &str) -> bool {
        let b = ts.as_bytes();
        ts.len() >= 11
            && b[0..4].iter().all(u8::is_ascii_digit)
            && b[4] == b'-'
            && b[5..7].iter().all(u8::is_ascii_digit)
            && b[7] == b'-'
            && b[8..10].iter().all(u8::is_ascii_digit)
            && b[10] == b'T'
    }

    #[test]
    fn basename_strips_dirs() {
        assert_eq!(basename("/a/b/myorg-runbook"), "myorg-runbook");
        assert_eq!(basename("plain"), "plain");
    }

    #[test]
    fn parse_requires_handled_in_cmd_not_parse() {
        // parse_args itself doesn't enforce required flags (cmd_pack does).
        let a: Vec<String> = vec!["--strict".into()];
        let p = parse_args(&a).unwrap();
        assert!(p.strict);
    }

    #[test]
    fn parse_rejects_unknown_flag() {
        let a: Vec<String> = vec!["--bogus".into()];
        assert!(parse_args(&a).is_err());
    }

    #[test]
    fn parse_collects_repeatable_flags() {
        let a: Vec<String> = [
            "--add-skill",
            "/a",
            "--add-skill",
            "/b",
            "--add-mcp=/m.json",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        let p = parse_args(&a).unwrap();
        assert_eq!(p.add_skills, vec!["/a", "/b"]);
        assert_eq!(p.add_mcps, vec!["/m.json"]);
    }
}
