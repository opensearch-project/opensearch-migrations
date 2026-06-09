# Release Extensibility Contract

**Schema version**: 1  
**Status**: Stable (breaking changes require a schema version bump)

This document defines the contract between the `migration-assistant` CLI
release artifacts and consumers who extend, repack, or integrate with it.
A release ships **two artifacts** — a per-platform native binary and a
shared bundle — plus a universal installer. No Rust toolchain, no
Gradle, no network access at runtime.

---

## 1. Release artifact layout (stable)

A GitHub Release publishes these files:

```
Per-release artifacts:
  install.sh                                    # universal installer (Linux, macOS, Windows/Git Bash)
  VERSION.txt                                   # plain-text version string
  migration-assistant-bundle.tar.gz             # shared bundle (platform-independent)
  migration-assistant-x86_64-unknown-linux-gnu  # Linux x86_64 binary
  migration-assistant-aarch64-unknown-linux-gnu # Linux ARM64 binary
  migration-assistant-x86_64-apple-darwin       # macOS Intel binary
  migration-assistant-aarch64-apple-darwin      # macOS Apple Silicon binary
  migration-assistant-x86_64-pc-windows-msvc.exe # Windows x86_64 binary
```

### Bundle contents (`migration-assistant-bundle.tar.gz`)

The bundle is **fully self-contained** — after install, the CLI never
needs to reach GitHub or any network to operate. Everything it deploys
from (helm chart, CFN templates, values files) is local.

```
skills/
├── manifest.json              # bundle contract (schemaVersion: 1)
├── Startup.md                 # agent-handoff briefing (loaded first)
└── <skill-name>/
    ├── SKILL.md               # required — skill entry point
    └── ...                    # additional skill files
helm/
└── migrationAssistantWithArgo/
    ├── Chart.yaml             # the deployment helm chart
    ├── values.yaml            # base values
    ├── valuesEks.yaml         # EKS preset
    ├── valuesForLocalK8s.yaml # kind/minikube preset
    ├── templates/             # helm templates
    └── scripts/               # ECR mirror + helpers
cfn/
├── cdk.json                   # CDK app config
├── bin/app.ts                 # CDK entry point
├── lib/                       # CDK stack definitions
└── ...                        # supporting files for CFN synth
VERSION.txt                    # version stamp (matches the release)
```

### Installed layout (after `install.sh`)

```
~/.opensearch-migration-assistant/
├── bin/
│   └── migration-assistant    # native binary
├── skills/...                 # agent skills + manifest.json
├── helm/...                   # helm chart (ready for `helm install`)
├── cfn/...                    # CDK/CFN templates
└── VERSION.txt                # runtime version resolution

~/.local/bin/
└── migration-assistant → ~/.opensearch-migration-assistant/bin/migration-assistant
```

### Guarantees

| Artifact | Stability | Contract |
|----------|-----------|----------|
| `migration-assistant-<target>[.exe]` | **Stable** | Self-contained native binary. No wrapper, no toolchain. Resolves version from sibling `VERSION.txt` at runtime. |
| `migration-assistant-bundle.tar.gz` | **Stable** | Platform-independent. Contains `skills/`, `manifest.json`, `VERSION.txt`. Name is flat (no version suffix). |
| `install.sh` | **Stable** | Bash. Works on Linux, macOS, and Windows (Git Bash/MSYS/Cygwin). Same `curl .../install.sh \| bash` command everywhere. Supports `path` and `workspace` modes. Reads `branding.binaryName` from the bundle's `manifest.json`. |
| `VERSION.txt` | **Stable** | Plain text, one line, the semver release string. Placed next to the binary AND in the bundle root. |
| `manifest.json` (bundle root) | **Stable** | `schemaVersion: 1` JSON. The single source of truth for branding, MCP servers, skill discovery config, and build provenance. |
| `skills/` (in bundle) | **Stable** | Convention-based discovery: every immediate subdirectory containing `SKILL.md` is a loadable skill. |

### What is NOT stable

- Internal file offsets within the binary.
- The `skills/manifest.json` path within the bundle (may move to root-only).
- Any `lib/` or `sops/` directories (internal to certain builds).
- The combined single-tarball format from pre-v4 releases (superseded by the two-artifact model).

---

## 2. manifest.json schema (v1)

The manifest is the **sole configuration surface** for extensibility.
The CLI parses it at startup; the `pack` subcommand reads/writes it
during repacking. All fields are optional unless marked required.

```jsonc
{
  "schemaVersion": 1,                    // REQUIRED. Must be 1.

  "branding": {
    "appName": "string",                 // Display name in TUI header + help
    "binaryName": "string",              // [a-z][a-z0-9-]* — controls symlink name
    "tagline": "string",                 // One-liner shown after version
    "helpHeader": "string",              // First line of `--help`
    "welcomeMessage": "string",          // TUI welcome screen
    "versionString": "string",           // Overrides default `<ver>+packs` display
    "agentFreshPrompt": "string",        // System prompt on first agent turn
    "modes": [                           // Deploy-mode picker (order = display order)
      {
        "id": "string",                  // Dispatch target: "Manual" | "Agent"
        "label": "string",              // Picker label
        "description": "string",        // Picker description
        "default": true,                // Exactly one must be true
        "available": true               // false = hidden from picker
      }
    ]
  },

  "skills": {
    "discovery": "auto"                  // "auto" = scan skills/*/SKILL.md
  },

  "mcpServers": {                        // MCP Model Context Protocol servers
    "<server-name>": {
      "command": "string",               // REQUIRED. The executable to launch.
      "args": ["string"],                // REQUIRED. Supports ${VAR} substitution.
      "scope": "string",                 // "project" | "global"
      "agents": ["string"],              // REQUIRED. Which agents get this MCP.
      "requires": ["string"],            // Binaries that must be on PATH.
      "permissionsAllow": ["string"]     // Tool permission patterns to auto-allow.
    }
  },

  "build": {
    "name": "string",                    // Bundle identity (e.g. "migration-assistant")
    "version": "string",                 // Semantic version of the bundle
    "packs": [                           // Provenance trail (append-only)
      {
        "name": "string",                // Pack identity (e.g. "myorg-internal")
        "version": "string",            // Pack version
        "appliedAt": "string",          // ISO-8601 UTC timestamp
        "addedSkills": ["string"],      // Skill dir names added by this pack
        "addedMcpServers": ["string"],  // MCP server names added
        "brandingChanged": false        // Whether branding was modified
      }
    ]
  }
}
```

### Variable substitution

String values in `branding.*` and `mcpServers.*.args[]` support
`${VAR}` interpolation. Resolution order:

1. Stage state (`state.env` / `state.json`)
2. Process environment
3. Left as literal `${VAR}` (unresolved; agents may warn)

Escape: `$$` collapses to `$`.

### Validation rules (enforced by `pack --strict`)

| Rule | Severity |
|------|----------|
| Exactly one mode has `default: true` | warn (fatal under `--strict`) |
| No duplicate mode `id` values | **always fatal** |
| Every visible mode `id` is a known dispatch target (`Manual` or `Agent`) | warn |
| `binaryName` matches `[a-z][a-z0-9-]*` | warn |
| Every MCP entry has `command` (string), `args` (array), `agents` (array) | **always fatal** |

---

## 3. Extension points

### 3.1 Skills (drop-in)

**Contract**: any directory under `skills/` containing a `SKILL.md` file
is auto-discovered and available to the agent at runtime.

```
skills/
└── your-runbook/
    ├── SKILL.md          # REQUIRED. Entry point. Agent reads this first.
    ├── workflow.md       # Optional additional files (any name/structure)
    └── templates/        # Optional subdirectories
```

The SKILL.md frontmatter:

```markdown
---
name: your-runbook
description: One-line description of what this skill does and when to use it.
---

# Your Runbook Title

Content the agent reads and follows...
```

Skills are **additive** — adding one never removes or modifies upstream
skills. The agent discovers all skills at session start via:

```bash
for d in skills/*/; do
  [[ -f "$d/SKILL.md" ]] && echo "${d%/}"
done
```

### 3.2 MCP servers (manifest entry)

Add a JSON file with the MCP shape and pass it to `pack --add-mcp`:

```json
{
  "myorg-mcp": {
    "command": "uvx",
    "args": ["myorg-mcp-server@latest", "--region", "${AWS_REGION}"],
    "scope": "project",
    "agents": ["claude", "codex", "kiro"],
    "requires": ["uvx"],
    "permissionsAllow": [
      "mcp__myorg-mcp__*"
    ]
  }
}
```

**Merge rule**: last-write-wins on name collisions. The `pack` command
warns when overwriting an upstream MCP.

### 3.3 Branding (manifest fields)

Rebrand without forking:

```json
{
  "appName": "MyOrg Migration Tool",
  "binaryName": "myorg-migrate",
  "tagline": "Powered by OpenSearch Migration Assistant",
  "modes": [
    {"id": "Agent", "label": "AI-first", "description": "Agent drives", "default": true, "available": true},
    {"id": "Manual", "label": "Manual", "description": "You drive", "default": false, "available": true}
  ]
}
```

**Merge rule**: `modes[]` is **replaced** (not merged) when the fragment
declares it — array order is display order. All other fields are
**last-write-wins**.

When `binaryName` changes:
- `install.sh` names the installed binary accordingly (e.g. `myorg-migrate` instead of `migration-assistant`)
- The `activate` script wires the new name onto PATH
- The `pack` subcommand renames the bundle tarball root dir to match

### 3.4 Build provenance (append-only)

Every `pack` operation appends to `build.packs[]`. This is a
**read-only audit trail** — consumers can inspect it but must never
modify or truncate it. The CLI uses it for:

- Version display: `migration-assistant 3.2.1 +myorg-1.0.0 +extra-2.1`
- Detecting custom builds (any `packs[]` entry → not vanilla upstream)
- Agent skill-set awareness (the Startup.md instructs the agent to
  check `build.packs` to understand what's bundled)

---

## 4. The `pack` subcommand (repacking without a fork)

`migration-assistant pack` takes an upstream release bundle tarball and
produces a new bundle with custom extensions applied — no source
checkout, no Rust toolchain, no Gradle.

```bash
migration-assistant pack \
  --input  migration-assistant-bundle.tar.gz \
  --output myorg-migrate-bundle.tar.gz \
  --pack-name myorg-internal \
  --pack-version 1.0.0 \
  --add-skill ./our-runbook \
  --add-skill ./our-compliance-checks \
  --add-mcp ./our-mcp.json \
  --branding ./our-branding.json \
  --strict
```

| Flag | Repeatable | Purpose |
|------|-----------|---------|
| `--input` | no | Source tarball (upstream release) |
| `--output` | no | Target tarball path |
| `--pack-name` | no | Identity recorded in provenance |
| `--pack-version` | no | Version recorded in provenance |
| `--add-skill <dir>` | **yes** | Copy a skill directory (must have SKILL.md) |
| `--add-mcp <file>` | **yes** | Merge MCP entries from a JSON fragment |
| `--branding <file>` | no | Deep-merge a branding JSON fragment |
| `--brand-name <str>` | no | Set `branding.appName` |
| `--brand-binary <str>` | no | Set `branding.binaryName` |
| `--brand-tagline <str>` | no | Set `branding.tagline` |
| `--mode-default <id>` | no | Flip one mode to `default: true` |
| `--mode-order id1,id2` | no | Reorder `branding.modes[]` |
| `--strict` | no | Treat validation warnings as fatal |

**Invariants**:
- The input tarball is never modified.
- The output is a self-contained, installable tarball (same contract).
- Packs are **composable**: pack A's output is a valid input to pack B.
- `tar` is the only external tool required.

---

## 5. install.sh contract

The installer is a bash script that works on **all platforms** via the
same command: `curl -fsSL .../install.sh | bash`. It works on Linux,
macOS, and Windows (Git Bash / MSYS2 / Cygwin).

| Mode | Trigger | Installs to | Auto-launches |
|------|---------|-------------|---------------|
| `path` (default) | `MIGRATE_INSTALL=path` or omitted | Binary → `~/.local/bin/<binaryName>`, bundle → `~/.opensearch-migrate/bundle/` | No |
| `workspace` | `MIGRATE_INSTALL=workspace` | `./migration-assistant-workspace/{bin/,bundle/}` + `activate` script | Yes (Unix TTY only) |

### Platform detection

| `uname -s` | `uname -m` | Target triple | Binary suffix |
|------------|------------|---------------|---------------|
| Linux | x86_64 | `x86_64-unknown-linux-gnu` | (none) |
| Linux | aarch64 | `aarch64-unknown-linux-gnu` | (none) |
| Darwin | x86_64 | `x86_64-apple-darwin` | (none) |
| Darwin | arm64 | `aarch64-apple-darwin` | (none) |
| MINGW*/MSYS*/CYGWIN* | x86_64 | `x86_64-pc-windows-msvc` | `.exe` |

### Environment overrides

| Variable | Default | Purpose |
|----------|---------|---------|
| `MIGRATE_INSTALL` | `path` | Install mode |
| `MIGRATE_REPO` | baked at build time | GitHub `owner/repo` for release downloads |
| `MIGRATE_VERSION` | baked at build time or `latest` | Pin a specific release tag |
| `MIGRATE_PREFIX` | per-mode | Override the bundle install directory |
| `BIN_DIR` | `~/.local/bin` (path mode) | Where the binary lands |
| `MIGRATE_WORKSPACE` | `./migration-assistant-workspace` | Workspace root (workspace mode) |
| `MIGRATE_NO_LAUNCH` | `0` | `1` = skip auto-launch in workspace mode |

### How the binary name flows

1. `install.sh` downloads the bundle and extracts it
2. Reads `branding.binaryName` from the bundle's `manifest.json`
3. Names the installed binary accordingly (e.g. `myorg-migrate` instead of `migration-assistant`)
4. Custom-branded installs are indistinguishable from upstream once on PATH

---

## 6. Binary contract

Each platform gets a standalone native binary (`migration-assistant-<target>[.exe]`).
There is **no wrapper script** — the binary is the direct entry point.

| Property | Contract |
|----------|----------|
| Self-contained | Runs on the target OS with no shared libs beyond system libc |
| No Rust toolchain | The binary is precompiled; `cargo`/`rustup` are never needed |
| Version resolution | Runtime: reads `VERSION.txt` from up to 3 ancestor directories above the binary. Fallback: compile-time `CLI_VERSION` env. Last resort: `0.0.0-dev` |
| Exit codes | `0` = success, `1` = runtime error, `64` = unknown command (dispatcher contract) |
| State location | `MIGRATE_HOME` or `~/.opensearch-migrate/` per-stage |
| External tools | `aws`, `kubectl`, `helm` on PATH (for deploy); `tar` (for `pack` only) |
| No network at startup | The binary never phones home; version check is opt-in (`migration-assistant update`) |

### VERSION.txt resolution order

The binary searches for `VERSION.txt` in this order:
1. Same directory as the binary
2. Parent directory
3. Grandparent directory

First non-empty match wins. When none found, falls back to the compile-time
`CLI_VERSION` constant (set by Gradle during builds), then `0.0.0-dev`.

### Development (source checkout)

For development, the `bin/migration-assistant` shell wrapper still exists.
It builds the binary from source if needed (`cargo build --release`) and
execs it. This is used by developers and Jenkins only — released installs
use the naked binary directly.

---

## 7. Versioning and compatibility

| Component | Versioning | Compatibility window |
|-----------|-----------|---------------------|
| `schemaVersion` | Integer (currently `1`) | A bump is a breaking change; old CLIs reject new schemas |
| CLI binary | SemVer (`CLI_VERSION`) | Reads schema ≤ its built version |
| manifest.json | Additive within a schema version | New fields are ignored by older CLIs |
| Skills | No versioning | Always loaded by name; content is opaque to the CLI |
| Packs | Named + versioned | Composable; provenance is append-only |

### Adding new manifest fields (non-breaking)

New top-level keys or new keys within existing objects are ignored by
older CLIs (serde's default). This is safe as long as the new field is
optional and the CLI's behavior without it is the pre-existing default.

### Breaking changes (requires schema version bump)

- Removing or renaming an existing field that the CLI reads.
- Changing the semantics of an existing field.
- Changing the `pack` merge rules.
- Changing the skill discovery convention (`skills/*/SKILL.md`).

---

## 8. For integrators: what you can rely on

If you are building automation around the release (CI/CD, fleet tooling,
internal distribution), these are the **stable interfaces**:

| Interface | Stability guarantee |
|-----------|-------------------|
| `install.sh` env vars (§5) | Stable; new vars may be added |
| `manifest.json` schema (§2) | Stable within a schema version |
| `migration-assistant version` exit 0 + prints version | Stable |
| `migration-assistant help` exit 0 | Stable |
| `migration-assistant pack` flags (§4) | Stable; new flags may be added |
| Unknown-command exit code `64` | Stable |
| `skills/*/SKILL.md` discovery | Stable |
| `build.packs[]` provenance shape | Stable |
| Binary naming: `migration-assistant-<target>[.exe]` | Stable |
| Bundle naming: `migration-assistant-bundle.tar.gz` (flat, no version suffix) | Stable |
| `VERSION.txt` next to binary | Stable |

---

## 9. Minimal viable extension (quick-start)

To ship a custom bundle with one added skill and one MCP:

```bash
# 1. Download the upstream release bundle
curl -fLO https://github.com/opensearch-project/opensearch-migrations/releases/latest/download/migration-assistant-bundle.tar.gz

# 2. Create your skill
mkdir my-skill && cat > my-skill/SKILL.md <<'EOF'
---
name: my-skill
description: Custom runbook for our team's migration workflow.
---
# My Skill
Instructions the agent follows...
EOF

# 3. Create your MCP definition
cat > my-mcp.json <<'EOF'
{
  "my-mcp": {
    "command": "npx",
    "args": ["-y", "@myorg/mcp-server"],
    "agents": ["claude", "codex"],
    "requires": ["npx"]
  }
}
EOF

# 4. Pack
migration-assistant pack \
  --input migration-assistant-bundle.tar.gz \
  --add-skill ./my-skill \
  --add-mcp ./my-mcp.json \
  --pack-name my-team \
  --pack-version 1.0.0 \
  --output my-team-bundle.tar.gz

# 5. Distribute — the output bundle is a drop-in replacement for the
#    upstream bundle. Host it alongside the platform binaries in your
#    own release, or point install.sh at it via MIGRATE_REPO.
```

The output bundle works with the same install.sh and platform binaries.
No fork, no rebuild, no toolchain.
