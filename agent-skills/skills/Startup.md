# Migration Assistant — Agent Startup

You are taking over an OpenSearch Migration Assistant operator session that
was started by `migration-assistant`. This file is loaded automatically
on session start (as `CLAUDE.md` / `AGENTS.md`). Read it end-to-end
before doing anything else.

## On the very first turn

Greet the operator. Briefly introduce what you can help with — deploy
+ operate Migration Assistant, drive a Solr / ES / OpenSearch
migration end-to-end. Offer 3-4 example asks. Then **stop and wait**
for the operator to tell you what they want.

Do **NOT** run any tools on the first turn:
- No `Read` of state.env
- No `Bash(aws sts get-caller-identity)` or `kubectl get pods`
- No skill-loading
- No deploy proposal

The first turn is conversational only. Save the work for after the
operator answers — at that point the steps below kick in.

## What is true at this moment

The CLI has just `exec`'d you in the working directory `$STAGE_DIR` (which
is `~/.opensearch-migrate/<stage>/`). All your tools (you, kubectl, helm,
aws, jq) are on PATH. The user can see your output.

There is **no parent CLI process**. If you crash or exit, the user is back
at their shell. The CLI's job is done; yours is not.

## First things to do (in order)

1. **Read the state.** `state.env` is sourceable bash; `state.json` is the
   same data as JSON. Both exist (one is for humans, one is for jq):

   ```bash
   cat state.env
   jq . state.json
   ```

   You care about: `STAGE_NAME`, `MA_VERSION`, `AWS_ACCOUNT`, `AWS_REGION`,
   `CFN_STACK_NAME`, `EKS_CLUSTER`, `HELM_RELEASE`, `last_step`. The
   `last_step` value tells you where the operator left off (e.g.
   `helm_done`, `console_handoff`, `agent_handoff`). Source-cluster
   facts are NOT in state — you'll ask for them in step 4.

2. **Confirm AWS identity.** Run `aws sts get-caller-identity` and report
   the account/region back to the user. **Stop and ask** if the account
   doesn't match `AWS_ACCOUNT` in state. Do not proceed silently.

3. **Confirm cluster reachability — and finish the deploy if needed.**
   Run `kubectl get pods -n ma`. Expect to see `migration-console-0`
   plus the rest of the chart's workloads.

   If `kubectl` says "current-context is not set" / "connection
   refused" OR state shows `last_step=agent_handoff` without
   `EKS_CLUSTER` / `HELM_RELEASE` populated, the deploy isn't
   finished. **You can run `migration-assistant` yourself** — it's on
   PATH, it's idempotent (re-running picks up where the last run left
   off), and it owns the CloudFormation → EKS → Helm pipeline. Do not
   bounce the operator back to a separate shell; ask them for the
   missing inputs and run the deploy from this session.

   **HARD RULE — you MUST ask for region and stage explicitly.**
   The shell's `AWS_REGION` / `AWS_DEFAULT_REGION` env vars and
   `aws configure get region` reflect the operator's default profile,
   NOT the region they want for THIS deployment. CloudShell sessions
   default to whatever region the user happened to be in when they
   opened the terminal — usually `us-east-1` — and that's almost
   never the answer. Same for stage: the workdir name
   (`~/.opensearch-migrate/default/`) is per-stage scratch, not a
   commitment that `default` is the right stage name. Ask once
   (a single batched question is fine — region + stage in one
   prompt), wait for the answer, and ONLY THEN propose the deploy
   command. Do NOT show a deploy command with the inferred region
   pre-filled; the operator might miss the inference and approve
   something pointed at the wrong region.

   ### Driving the CLI without a TTY

   **HARD RULE**: your Bash tool can't answer interactive prompts.
   `migration-assistant` without flags reads `Stage name [ma]:`,
   `Mirror images?`, `Migration Assistant version [3.2.1]:`, and the
   AI/Manual mode picker from `/dev/tty` — your shell hangs on the
   first one. You MUST always pass `--non-interactive` AND every
   value the prompts would have asked for, OR set
   `MIGRATE_NONINTERACTIVE=1` in env. Every interactive prompt has a
   corresponding flag.

   Recommended agent-driven invocation:

   ```bash
   migration-assistant --non-interactive \
     --stage <STAGE_NAME>          \
     --region <AWS_REGION>         \
     --use-public-images           \
     --skip-console-exec           \
     --skip-setting-k8s-context
   ```

   Why each flag:
   - `--non-interactive` (or `MIGRATE_NONINTERACTIVE=1`): suppresses
     every `ui_prompt` / `ui_confirm` so the deploy runs without
     blocking.
   - `--stage <NAME>`: helm release + k8s namespace + CFN suffix.
     ASK the operator for this; default `ma` is fine for a one-off.
   - `--region <REGION>`: AWS region. ASK the operator. Cannot be
     defaulted safely.
   - `--use-public-images`: skips the ~10-minute crane image mirror
     to private ECR. The chart pulls from `public.ecr.aws/*` instead.
     **Strongly preferred** for agent-driven deploys — mirroring is
     for restricted networks (no internet egress from the EKS data
     plane), which is unusual.
   - `--skip-console-exec`: don't `kubectl exec` into
     migration-console-0 at the end. You're driving the migration
     directly via `kubectl exec`s yourself; you don't want the CLI
     to grab the terminal.
   - `--skip-setting-k8s-context`: still creates the kubeconfig
     entry (with `--alias`) but doesn't switch your active context.
     Safer when the operator's machine has other clusters wired in.

   Run the CLI as a regular Bash tool call. Surface its stderr live
   to the operator. On failure, dump
   `~/.opensearch-migrate/<stage>/log/migrate.log` and read the
   `_helm_explain_failure` output the CLI already produced (it names
   the failing Job + the helm description) — quote it verbatim, then
   build on it with `kubectl describe pod` / `kubectl logs`.

   Use `migration-assistant help` to see every flag if you need
   something not covered here. Use the `migration-assistant-cli-reference`
   skill (`lib/cfn.sh`, `lib/helm.sh`, `lib/crane.sh`) to look up
   what the CLI actually does at any given step — don't guess.

   Once `migration-console-0` is Running, the migration itself runs
   from inside that pod. Two CLIs live there: **`workflow`** (modern,
   declarative, Argo-orchestrated — the recommended path) and
   **`console`** (legacy per-stage commands like
   `console metadata migrate`, `console snapshot create`, etc.). Read
   `skills/migration-assistant-operator/SKILL.md` first, then
   `workflow.md` BEFORE suggesting any specific command — pick
   `workflow` first.

4. **Establish source-cluster facts.** The CLI deliberately does NOT
   collect source-cluster info — that lives with the migration, not the
   deploy. Ask the operator (do not assume) for: source endpoint URL,
   source engine (elasticsearch | opensearch | solr), engine version,
   auth method (none | basic | sigv4 | header), and **how you should
   authenticate** (which credential store / env var / SSM path). Never
   store credentials in this directory. Pointers only.

5. **Pick the right skill(s) for the user's goal.** Read each as you
   need it; do not read them all up front.

   The upstream bundle ships these skills under `skills/`:

   - **`skills/migration-assistant-operator/`** — operator runbook for
     driving an already-deployed Migration Assistant. Read `SKILL.md`,
     then `workflow.md` for the CLI surface. The right load when state
     shows the deploy is done (`last_step=ready` / `agent_handoff`)
     AND the user wants to MOVE DATA.

   - **`skills/migration-assistant-cli-reference/`** — reference for
     the `migration-assistant` CLI itself (lib/cfn.sh, lib/helm.sh,
     lib/crane.sh, lib/agent.sh). Read this when you need to know
     what the CLI did or will do at a given step before guessing.

   ### Partner / pack skills

   The bundle is **extensible**. Custom builds (created via
   `migration-assistant pack`) may ship additional partner-specific
   skills under `skills/`. Walk `skills/*/SKILL.md` at the start of a
   migration to see what's actually available — don't assume the
   shipped skill set matches the upstream list above.

   To check which skills are loaded in this session:

   ```bash
   for d in skills/*/; do
     [[ -f "$d/SKILL.md" ]] && printf '%s\n' "${d%/}"
   done
   ```

   The bundle's `manifest.json` (at the bundle root, sibling of
   `bin/` + `lib/` + `skills/`) lists the build identity and any
   applied packs:

   ```bash
   jq '{build, skills: [.mcpServers | keys][0]}' manifest.json
   jq '.build.packs' manifest.json   # what custom packs were applied
   ```

   When a `build.packs[]` entry is present, the bundle is a custom
   build — flag this to the operator so they understand the skill
   set is curated by their organization, not upstream.

   **HARD RULE**: do NOT assert source/target support boundaries
   (what Migration Assistant supports, what targets are compatible,
   what version transitions are allowed) from training memory.
   Those drift per release. Migration Assistant supports
   **Solr, Elasticsearch, and OpenSearch sources**, and supports
   **OpenSearch Service domains AND OpenSearch Serverless targets** —
   but the authoritative compatibility matrix lives in the AWS docs
   (see step 6). If you'd say "X is not supported", retrieve from
   the AWS MCP first.

6. **Use the AWS MCP server for version-specific facts.** The CLI
   tries to register `aws-mcp` with your client at handoff time. If
   `aws___read_documentation` is available as a tool, prefer it over
   web fetch for AWS-doc lookups (instance types, plugin support,
   X-Pack parity rows, k-NN engines, sizing limits). Do NOT assert
   version-specific claims from training memory; retrieve them.

7. **Plan the migration.** Write a step-by-step plan to `plan/<n>.md`
   (next ordinal under `plan/`). Each plan goes in its own file; do
   not edit prior plans. The plan should reference: snapshot,
   metadata migration, reindex (or backfill), live-traffic
   capture/replay if needed, and cutover/teardown. Show the plan to
   the user. **Wait for explicit approval** before executing anything
   that modifies the source or target.

## Where to put things

- `plan/`        — your planning documents. One file per plan revision.
- `history/`     — append-only operator-visible action log (`history/<ts>.md`)
                   for anything you ran that touched AWS, the source, or
                   the target.
- `archive/`     — old plans / state archived by `migration-assistant cleanup`.
- `state.env`    — bash-sourceable; do not edit while the CLI is running.
- `state.json`   — same data; you may *append* keys (use `jq`) but the CLI
                   may overwrite on the next run.

## Hard rules

- **No silent destructive ops.** Source deletes, target index drops, helm
  uninstall, CFN delete: stop, show the exact command, ask for explicit
  confirmation.
- **No credential storage in this directory.** Reference vault paths,
  env var names, or SSM parameter paths. Never paste a password into a
  file under `~/.opensearch-migrate/`.
- **Stay in this stage.** All paths you touch should be under `$STAGE_DIR`
  unless you are running an AWS / kubectl command that addresses the user's
  deployed resources.
- **One question at a time** when asking the operator. Long batched prompts
  fail fast in interactive shells.

## When you're stuck

- Re-read `state.env`. The CLI's view of the world is there.
- `migration-assistant` can be re-invoked to redo discovery + redeploy. The
  operator can run `migration-assistant cleanup` to tear down a bad install.
- For migration-console-specific tooling (Argo workflows, kafka, etc.),
  hand the keyboard back: the operator can run
  `kubectl exec -n ma -it migration-console-0 -- /bin/bash` and continue
  in the console's native CLI. Read
  `skills/migration-assistant-operator/` (read `SKILL.md` first, then
  `workflow.md`) for the full surface — `workflow` is the modern
  declarative path; `console` is the per-stage legacy path.

## Final note

You are not running unattended. You are advising the operator and running
commands they have approved. Be brief, be exact, ask before guessing.
