# migration-assistant CLI

The OpenSearch Migration Assistant deploy CLI — a single self-contained binary
that drives an EKS deploy end to end:

> discover → wizard → CloudFormation → *(optional `--build`)* → crane image
> mirror → helm install → `kubectl exec migration-console-0`

…or, in **Agent** mode (preview, behind `MIGRATE_ENABLE_AGENT=1`), hands off to
an LLM coding agent (claude / codex / kiro) with a pre-loaded skill set.

It's an idiomatic Rust crate with a clean split between **pure decision logic**
(unit-tested) and **external-command I/O** (mockable), plus a
[Ratatui](https://ratatui.rs) TUI for the interactive deploy wizard.

## Design

- **Pure logic is pure.** CFN output parsing, the crane destination/retry
  classifier, the helm image/TLS flag builders, manifest `${VAR}` substitution,
  version resolution, the wizard state machine, and the pack manifest-merge are
  plain functions over plain data — tested directly.
- **I/O goes through one seam.** Every `aws`/`kubectl`/`helm`/`crane`/`tar`
  call goes through the [`CommandRunner`](src/core/runner.rs) trait. `RealRunner`
  spawns processes; `MockRunner` matches argument patterns and records calls, so
  the whole deploy pipeline is asserted with no AWS access.
- **Immediate-mode TUI.** The wizard + resume timeline are Ratatui widgets
  driven The-Elm-Architecture way (Model → Message → update → view), with the
  rendered `Buffer` asserted via `TestBackend`.

## Layout

The source is grouped into four layered folders; each leaf module is
re-exported at the crate root, so the public path stays a flat
`migration_assistant::<module>` regardless of folder.

```
src/
  core/       error · util · config · state · log · runner   (foundation + I/O seam)
  domain/     cfn · crane · helm · discover · manifest · version · pack · agent · timeline
  view/       ui (output discipline) · tui (Ratatui front-end)
  command/    app (deploy orchestrator) · cli (dispatcher) · pack_cmd
tests/        deploy_pipeline · cli_dispatch · pack_and_manifest   (integration)
bin/migration-assistant   source-checkout wrapper (cargo build + exec)
```

## Subcommands

```
migration-assistant [flags]            Deploy / resume (default)
migration-assistant resume   [flags]   Same as default
migration-assistant console  [--stage] kubectl exec migration-console-0
migration-assistant agent    [--stage] [<agent>]   Open an LLM coding agent
migration-assistant diag     [--stage] Dump diagnostics
migration-assistant clear    [--stage] Wipe local state (no AWS changes)
migration-assistant cleanup  [--stage] Tear down deploy + archive state
migration-assistant pack     [flags]   Repack a CLI tarball with custom skills/MCPs/branding
migration-assistant version
migration-assistant help
```

The full deploy-flag surface (`--region`, `--stack-name`, `--build`,
`--tls-mode`, `--create-vpc-endpoints`, `--use-general-node-pool`, …), the Agent
preview gate (`MIGRATE_ENABLE_AGENT=1`), the `MIGRATE_HOME`/`--stage` per-project
state layout, and an exit-code contract (unknown command → 64).

## Build & test

The toolchain is pinned in [`rust-toolchain.toml`](rust-toolchain.toml); rustup
fetches it on first use. From this directory:

```sh
cargo test       # unit + integration
cargo clippy --all-targets -- -D warnings
cargo fmt --check
cargo build --release
```

Or via Gradle, which **auto-provisions Rust** the same way the repo
auto-downloads the Corretto JDK (no toolchain needed on the agent):

```sh
./gradlew :deployment:k8s:aws:packageMigrationAssistantCli   # tarball + install.sh
```

## Tests

Two layers, both run by `cargo test`:

- **Unit tests** live next to the code (`#[cfg(test)]`), covering the pure
  decision functions and each module's edge cases (parsing, classification,
  flag building, the confirm truth table, the wizard state machine, the TUI
  render via `TestBackend`).
- **Integration tests** in [`tests/`](tests) exercise the public surface
  end-to-end against `MockRunner` / `RealRunner`:
  - `deploy_pipeline.rs` — a full Manual deploy through the orchestrator
    (CFN → kubeconfig → crane → helm), asserting the command sequence and the
    persisted state transitions; plus the skip/guard paths.
  - `cli_dispatch.rs` — the dispatcher contract: exit codes, the Agent gate,
    cleanup of empty state, the help surface.
  - `pack_and_manifest.rs` — the `pack` subcommand through a real `tar`
    round-trip, and manifest/skills resolution against an on-disk bundle.

## Dependencies

Kept deliberately minimal — 2 runtime crates (`ratatui`, `serde`/`serde_json`),
a hand-rolled CLI parser + error type (no clap/anyhow/thiserror/regex), and a
short, audited list of external programs (`aws`/`kubectl`/`helm`/`crane`, plus
`tar` for `pack`) all funneled through one `CommandRunner` seam. See
[DEPENDENCIES.md](DEPENDENCIES.md) for the exhaustive list and how to re-audit it.

## License

Apache-2.0 (matches opensearch-project).
