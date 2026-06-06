# Dependencies

This CLI keeps its dependency surface deliberately small. There are two kinds of
dependency: **Rust crates** (compiled in) and **external programs** (invoked at
runtime). Both are enumerated here exhaustively.

> Note on the `pack` subcommand: `migration-assistant pack` repacks a CLI
> release tarball with custom skills/MCPs/branding. It is unrelated to the
> obsolete Unix `pack`/`unpack`/`pcat` compression utility (Huffman coding,
> dropped from modern POSIX). Internally it uses `tar` (gzip), not `pack`.

## Rust crates (direct)

| Crate | Why it's needed | Could we drop it? |
|---|---|---|
| `ratatui` | The interactive TUI (wizard + resume timeline). Pulls in `crossterm` as its terminal backend. | No — it's the TUI. |
| `serde` + `serde_json` | Parse/emit `manifest.json` and `state.json` (and the `pack` manifest merge). | Not without hand-rolling a JSON parser; serde is the standard, audited choice. |

Dev-only:

| Crate | Why |
|---|---|
| `tempfile` | Scratch dirs in unit + integration tests. Not in the shipped binary. |

### Deliberately NOT used

These are common defaults we intentionally avoid to keep the tree minimal — each
was removed after confirming zero usage:

| Crate | What we do instead |
|---|---|
| `clap` | The argv dispatcher is hand-rolled (`command/cli.rs`). The flag surface is small and the parsing is a simple match, so a derive-macro arg parser earns its weight elsewhere, not here. |
| `anyhow` | One small `Error` type (`core/error.rs`) carrying a message + an exit code — exactly the surface the CLI needs (`die`, exit 64, …). |
| `thiserror` | Same — the error enum is trivial and hand-written. |
| `regex` | Manifest/state parsing is serde; CFN export-blob and version-tag extraction are plain string ops. No regex engine needed. |

Removing these cut the compiled dependency graph from **122 to 103 crates**.

## External programs (invoked at runtime, via the `CommandRunner` seam)

Every external-process call goes through `core/runner.rs` (`RealRunner` in
production, `MockRunner` in tests), so this list is the complete set of binaries
the CLI may shell out to. None are bundled; they're expected on the operator's
PATH for the operations that use them.

### Required for a deploy

| Program | Used by | Purpose |
|---|---|---|
| `aws` | discover, cfn, crane, helm, build, cleanup | CloudFormation deploy, EKS kubeconfig, ECR auth, STS identity. |
| `kubectl` | helm, console, diag, cleanup | Namespace/pod ops, `exec` into migration-console, readiness wait. |
| `helm` | helm, diag, cleanup | Install/upgrade/status/uninstall the chart. |
| `crane` | crane | Mirror images to the operator's private ECR (only when `MIRROR_IMAGES=Y`). |

### Required only for specific subcommands

| Program | Used by | Purpose |
|---|---|---|
| `tar` | `pack` | Extract/repack the CLI release tarball. |

### Probed but never executed

The CLI checks for these with `has_command` (detection only) and never runs
them:

| Program | Where | Note |
|---|---|---|
| `brew`, `apt-get`, `dnf`, `yum` | `discover` | Package-manager *detection* to report the host environment. The CLI does not auto-install tools, so these are never invoked. |

### Build/install-time only (not the CLI itself)

| Program | Where | Purpose |
|---|---|---|
| `cargo` / `rustup` | `bin/migration-assistant` wrapper, Gradle | Build the binary from source. Released tarballs ship a prebuilt binary, so end users need neither. Gradle auto-provisions `rustup` if absent (see `rust-toolchain.toml`). |
| `curl` / `tar` | `install.sh` | Download + unpack the release tarball during a curl-pipe install. |
| `git` | Gradle | Derive a dev version string when `CLI_VERSION` is unset. |

## Auditing this list

```sh
# External programs the CLI may call (the complete set):
grep -rhoE '\.(run|run_ok|has_command)\("[a-z][a-z0-9_-]*"' src \
  | sed -E 's/.*\("([^"]+)".*/\1/' | sort -u

# Direct Rust crates:
sed -n '/^\[dependencies\]/,/^\[/p' Cargo.toml

# Total compiled crates (incl. transitive):
grep -c '^name = ' Cargo.lock
```
