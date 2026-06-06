# Amber port — status

Rewrite of the PR #3022 migration-assistant bash CLI into Amber 0.6.0-alpha.
Amber-only (drop bash lib/), full test coverage, verified on bash 3.2+.

## Toolchain (proven)
- `amber 0.6.0-alpha` via `brew install amber-lang/amber/amber-lang`.
- `make check` (parse), `make test` (amber test, FORCE_TTY=1), `make verify`
  (compile `--target bash-3.2` + RUN under genuine `/bin/bash` 3.2.57).
- Conventions: `AMBER_IDIOMS.md`. Compiler-verified language facts captured there.

## Done — TUI layer (Tier 0), 30 tests green, bash-3.2 verified
| Amber module | from bash | tests | notes |
|---|---|---|---|
| `src/core.ab` | term.sh (foundation) | test_core.ab | esc() byte mint, eprint/eprintln (stderr), term_detect/term_interactive |
| `src/term.ab` | term.sh | test_term.ab (9) | VT100 cursor/wrap/panel/progress/link/spinner; UTF-8 codepoint width (no byte bugs); EXIT-trap cursor restore |
| `src/dashboard.ab` | dashboard.sh | test_dashboard.ab (9) | parallel-array store (no eval, no nested arrays), cfn classifier via if-chain, in-place redraw |
| `src/timeline.ab` | timeline.sh | test_timeline.ab (5) | phase checklist ●◐○ |
| `src/ui.ab` | ui.sh | test_ui.ab (5) | chrome→stderr, prompts/confirm/select/banner/table, non-interactive defaults |

## Remaining (per port plan, dependency-ordered)
- **Tier 1:** std.ab (std.sh helpers), common.ab (_common.sh), install.ab (standalone bootstrapper)
- **Tier 2:** state.ab, log.ab, version.ab, artifacts.ab
- **Tier 3:** manifest.ab, discover.ab, cfn_outputs.ab (pure), install_tools.ab
- **Tier 4:** wizard.ab, cfn_deploy.ab, crane.ab
- **Tier 5:** helm_*.ab (4 files), build.ab  ← hardest (background watchers → synchronous tee)
- **Tier 6:** console.ab(+diag), agent_*.ab (3 files), cleanup.ab, pack.ab
- **Tier 7:** resume.ab (controller), bin entrypoint

## Key escape-hatch decisions (Amber 0.6 has no &/wait/trap/eval/heredoc/fd-ops)
- Background watchers (helm/cfn/log_stream) → **synchronous tee + post-hoc dump / foreground poll loop**.
- `eval` array indirection → `ref` params or returned CSV.
- `exec` handoff → raw `trust $exec…$` as terminal statement only.
- `<<<` / `<(…)` → `lines(trust $cmd$)` or temp-file + `lines(file_read)`.
- heredocs → `\n` Text + `file_write`. `flock` → raw subshell or drop (single-process CLI).
