# Test guide — idiomatic, e2e-focused Amber tests

The CLI's tests favor **end-to-end flows over isolated units with mocks**: drive a
real `cmd_*` / flow function against a faked external world (aws/kubectl/helm/…)
and assert on observable behavior — what state was persisted, which commands the
CLI issued, what it returned. Pure unit tests are still welcome for genuinely
pure logic (formatters, parsers, classifiers), but they should be the minority.

## The shared support library (use it; don't re-derive)

```
test/support/world.ab   per-test sandbox + CLI env isolation
test/support/mock.ab     the external-tool mock fleet (aws/kubectl/helm/crane/…)
test/fixtures/*.json     static fixtures (manifests, etc.)
```

Import these instead of hand-writing mktemp/PATH/stub bash in every file.

### `world.ab` — isolate every test

```amber
import { fresh_world, world_seed, world_noninteractive } from "./support/world.ab"

let w = fresh_world()                 // unique sandbox; MIGRATE_HOME/STAGE_DIR/STAGE pinned to it
world_seed(w, "STAGE_NAME", "ma")     // pre-load a state.env value the CLI will read
world_noninteractive()                // prompts return defaults (no /dev/tty)
```

Each `test` block MUST mint its own world. Module-level `let` resets per block,
but **env vars leak across blocks** — so never depend on another block's setup,
and never steer behavior through a shared mutable env var (it races under the
parallel runner). The world dir is the per-test-unique channel: the CLI's
`MIGRATE_HOME` points into it and every child process (aws/kubectl/…) inherits it.

### `mock.ab` — fake the external world by scenario

```amber
import { mock_install_default, mock_respond, mock_fail,
         mock_called, mock_call_count } from "./support/mock.ab"

mock_install_default()                          // shims for aws + kubectl + helm on PATH
mock_install(["aws","kubectl","helm","crane"])  // or pick the fleet explicitly

// Program responses by sub-command token:
mock_respond(w, "aws", "describe-stacks", "CREATE_COMPLETE")  // aws … describe-stacks … → this stdout
mock_respond(w, "helm", "status", "NAME: ma\nSTATUS: deployed\n")
mock_fail(w, "helm", "uninstall", "1", "Error: release: not found")  // non-zero exit

// Drive the CLI, then assert on what it DID:
cmd_cleanup(["--non-interactive", "--stage", "w1"])
assert(mock_called(w, "helm", "uninstall"))     // the CLI issued helm uninstall
assert(mock_called(w, "aws", "delete-stack"))
assert_eq(mock_call_count(w, "kubectl"), 0)
```

The shim records every call to `<world>/mock/calls/<tool>.log` and emits the
fixture whose key matches the first arg token (e.g. `describe-stacks`, `status`,
`deploy`). Shims are content-stable + arg/world-steered → race-safe.

## Structure each test file for extensibility

1. **Imports** (std/test, the support lib, the module(s) under test).
2. **File-local helpers** (ABOVE the test blocks — define-before-use across the
   import boundary). Keep these thin; push anything reusable into `support/`.
3. **Test blocks**, grouped by behavior with `// ---- group ----` banners.
   Name them as behaviors: `test "cleanup uninstalls helm then deletes the stack"`.

Rules (see `../AMBER_IDIOMS.md` for the full list):
- NO `return` inside a `test` block — branch with `if`.
- A `{` in an Amber Text literal / `$…$` command is interpolation — escape JSON
  braces (`\{`) or load fixtures from `test/fixtures/*.json` via the world.
- Reserved keywords (`status`, `failed`, `lines`, `pid`, …) can't be identifiers.

## What to cover, by layer

- **E2E (primary):** each `cmd_*` happy path + its key failure/skip branches,
  driven through the public entrypoint with the mock fleet. Assert on persisted
  state (`state_get` after), issued commands (`mock_called`), and return/exit.
- **Flow (manual_path):** the deploy spine wired end to end with every external
  tool faked to "succeeds" — asserts the steps run in order and state advances
  `discover → wizard_done → cfn_done → … → ready`.
- **Unit (as needed):** pure formatters/parsers/classifiers — `fmt_*`,
  `cfn_status_class`, `parse_flag_*`, `regex_capture`, dashboard render Text.
  Assert on returned `Text`; no world/mock needed.

## Running

```bash
MIGRATE_FORCE_TTY=1 amber test test/            # full suite (run 2× — cold-cache flake)
MIGRATE_FORCE_TTY=1 amber test test/test_cleanup.ab   # one file
make test                                       # the gradle/CI entrypoint
```
