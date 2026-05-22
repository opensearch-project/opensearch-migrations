# UX — Locked Operational Rules

This document is the source of truth for user-facing behavior that must not
drift between releases. Each numbered rule is referenced by code comments
(`// UX §0.4`) and by the design recommendation. Changing a rule is a public
behavior change and should be a deliberate, separately reviewed PR.

The `r1`..`rN` items below are *locked rules*: they encode promises the TUI
makes to the operator. Removing or relaxing one is equivalent to changing the
product's contract.

---

## §0.1 — Re-runs are idempotent

Running `migration-assistant` twice in the same shell, against the same AWS
account/region, MUST converge:

  - If a workdir for this `<account, region>` exists, prompt to resume from
    the saved `.ma-state.json` rather than starting from welcome.
  - If a partial deploy exists in the workdir (CFN stack present, helm release
    absent), the deploy page resumes from the next pending phase rather than
    re-running completed phases.
  - Hard fail (no auto-retry) if the cached artifact SHA does not match the
    SHA in the live release index — see §0.7.

The drift guard for the upstream `aws-bootstrap.sh` (a scheduled CI job
diffing the verified version of the script against `main`) is filed under
§0.1.3.

## §0.4 — Workdir naming and isolation

Workdirs are created in the operator's CWD with the deterministic name:

```
./opensearch-migration-<account>-<region>/
```

Example: `./opensearch-migration-123456789012-us-east-1/`. The state file
inside this directory is always `.ma-state.json`.

Two operators running against two accounts in the same parent directory MUST
NOT collide. The TUI's `detectWorkdir` step prefix-matches on
`./opensearch-migration-` and rejects any directory whose `<account>` or
`<region>` does not match the resolved AWS identity.

## §0.5 — TUI version is pinned to MA version (locked rule **r6**)

The TUI binary is built with `-X main.MAVersion=<X.Y.Z>`. That string is the
*single source of truth* for which Migration Assistant chart and CFN templates
the TUI ships. The version-check dialog ("0.5 Update available") compares the
build-time TUI version against the latest GitHub release. The TUI version
check is cached for **24 hours** in `<UserCacheDir>/opensearch-migration-
assistant/version.json`.

There is no path by which a TUI binary deploys an MA version other than the
one its `main.MAVersion` was built with. This is what makes "TUI version =
MA version" a hard invariant rather than a hope.

## §0.6 — Agent-CLI version checks are looser

claude-cli, kiro-cli, etc. detection runs on every TUI start, but the
"newer version available" check is cached for **6 hours**. The TUI never
auto-upgrades an agent CLI; it shows a notice and a documentation link.

## §0.7 — Artifact resolution order with hard-fail on SHA mismatch

When the TUI fetches a CFN template, helm chart, or skill bundle for the
pinned MA version, the resolution order is:

  1. GitHub release asset for the tag (e.g. `helm-chart-3.2.1.tgz`).
  2. Tag-pinned raw repo contents (`raw.githubusercontent.com/.../v3.2.1/...`).
  3. **Hard fail.** No fallback to `main`. No fallback to "latest". No
     auto-retry on a SHA mismatch.

Each artifact has a SHA-256 companion (`<name>.sha256`) published with the
release. The fetcher computes the SHA of the downloaded bytes and compares
to the companion before writing it into the content-addressable cache at:

```
<workdir>/.cache/<sha256>/<name>
```

`<workdir>/artifacts/<name>` is a symlink into that cache. A mismatch
between the live SHA and the cached SHA is reported to the operator as
"this release was retagged — re-run with --reset-cache or report this as a
non-determinism bug." It is never auto-resolved.

Every fallback to step (2) is logged to `internal/log` with a WARN level so
postmortems can see "release asset was missing for v3.2.1, fell back to raw
repo."

## §0.8 — Region resolution surfaces fallback

If the operator has no `AWS_REGION` and no profile-pinned region, the TUI
falls back to **`us-east-1`** but surfaces the fallback as a status-line
notice ("using us-east-1 — set AWS_REGION to override"). The fallback is
never silent.

## r5 — TUI dies before exec (locked rule)

When the wizard reaches handoff, the Update loop emits `HandoffMsg` and the
program calls `tea.Quit`. Control returns to `cmd/tui/main.go`, which:

  1. Logs "handing off to <bin> <argv>" to the file logger.
  2. Runs the optional `Handoff.PreExec` callback (e.g. `aws eks update-kubeconfig`).
  3. `os.Chdir` to the workdir if the handoff requests it.
  4. `syscall.Exec(bin, argv, os.Environ())`.

The TUI process is *replaced* by the target process. Two consequences:

  - The operator's shell history shows the target command, not `migration-assistant`.
  - When the operator exits the target shell, they're at their original
    prompt — not back inside the TUI.

This is non-negotiable. There is no "TUI shells out and waits" code path.

## r7 — Status-line invariants

The bottom status line ALWAYS shows: `<account/region>  •  <workdir>  •  <hint>`.
The status line is never blank, never overflows, and never contains a stack
trace or raw error. Errors are routed to:

  - Toast (transient, 3-second auto-dismiss) for non-blocking warnings.
  - Modal dialog (blocking) for anything the operator must acknowledge.
  - The file log (always) for postmortem.

## r8 — Help is always reachable

`?` opens a dialog with the page-specific key bindings. `q` and `ctrl+c` are
the same: quit the program, after asking for confirmation if the operator is
mid-deploy.

## r9 — No silent destructive actions

Anything that touches AWS state (creating a CFN stack, installing a helm
release, attaching a node group) requires an explicit `Enter` on a review
page that lists exactly what will happen. The review page renders the
artifact SHAs being used.

## r10 — One AWS identity per session

The TUI resolves AWS identity once at startup via `sts:GetCallerIdentity` and
pins `<account, region>` for the rest of the session. If the operator wants
to switch accounts, they quit and re-run. There is no in-TUI account switcher.
This is what makes §0.4 (workdir isolation) a safe assumption.
