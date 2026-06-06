# Agent skills for OpenSearch Migrations

Skill content shared across LLM coding agents (Claude Code, OpenAI
Codex CLI, Kiro CLI). The migration-assistant CLI bundles the
contents of `skills/` and `kiro/` into its release tarball; the same
tree is also packaged on its own for direct use.

This directory was previously named `kiro-cli/` and only held Kiro
config. It now serves all three agents:

- `skills/` — agent-agnostic. Loaded by the CLI for whichever agent
  the operator picks.
- `kiro/` — Kiro CLI–specific (agent definition, steering docs, hooks).

## Layout

```
agent-skills/
├── build.gradle
├── README.md
├── skills/                            # agent-agnostic
│   ├── Startup.md                     # the agent-handoff briefing the CLI
│   │                                  # exec's first
│   ├── manifest.json                  # bundle TOC + extension contract
│   ├── migration-assistant-operator/  # operator runbook
│   │   ├── SKILL.md
│   │   ├── deployment.md
│   │   ├── workflow.md
│   │   ├── product.md
│   │   └── migration-prompt.md
│   └── migration-assistant-cli-reference/
│       └── SKILL.md
└── kiro/                              # Kiro-specific
    ├── agents/opensearch-migration.json
    ├── prompts/start.md
    ├── settings/hooks.json
    └── steering/
        ├── deployment.md
        ├── migration-prompt.md
        ├── product.md
        └── workflow.md
```

## Extensible skill framework

Skills are auto-discovered: every subdirectory of `skills/` that contains
a `SKILL.md` is bundled by the CLI's `agent_setup` step and installed
into the operator's session at handoff time.

To add a partner-distribution skill, drop `skills/<your-skill>/SKILL.md`
into the tree and rebuild — no code change required.

For one-off custom builds without forking the repo, use the
`migration-assistant pack` subcommand against a release tarball:

```bash
migration-assistant pack \
  --input migration-assistant-cli-3.2.1.tar.gz \
  --add-skill ./your-runbook \
  --add-mcp ./your-mcp.json \
  --branding ./your-branding.json \
  --pack-name your-org --pack-version 1.0.0 \
  --output your-migrate-3.2.1+your-1.0.0.tar.gz
```

The bundle contract (manifest.json schema, MCP shape, branding fields)
is documented in `skills/manifest.json` and enforced by the CLI's
`src/manifest.ab` parser (Amber source under deployment/k8s/aws/cli/).

## Gradle tasks

```bash
# Agent-agnostic: package skills/ as agent-skills.tar.gz
./gradlew :agent-skills:agentSkillsTar

# Kiro: same but folds skills/ + agent-sops/ into a runnable .kiro/ tree
./gradlew :agent-skills:setupKiro             # build .kiro/ tree only
./gradlew :agent-skills:kiroChat              # run kiro-cli chat
./gradlew :agent-skills:kiroAgent             # run kiro-cli chat --agent opensearch-migration
./gradlew :agent-skills:packageKiro           # build kiro-assistant.tar.gz
```

The migration-assistant CLI tarball builder
(`:deployment:k8s:aws:packageMigrationAssistantCli`) consumes
`agentSkillsTar` as a producer-→-consumer dependency, so any change
to `skills/` triggers a CLI rebuild without manual copying.

## Direct download (Kiro flow)

```bash
# From the latest release
tar -xzf kiro-assistant.tar.gz -C /path/to/your/workspace

# From main:
git archive --remote=https://github.com/opensearch-project/opensearch-migrations.git \
  HEAD agent-skills/ | tar -x

# From a fork:
curl -L https://github.com/<user>/opensearch-migrations/archive/refs/heads/<branch>.tar.gz \
  | tar -xz --strip-components=2 -C .kiro \
    opensearch-migrations-<branch>/agent-skills/kiro \
    opensearch-migrations-<branch>/agent-skills/skills
```

## Save chat history (Kiro)

Within a Kiro session, use `/save` to save your conversation.

## Requirements

For Kiro tasks: [Kiro CLI](https://kiro.dev/cli/).
The agent-agnostic `skills/` content has no runtime dependencies — it's
plain markdown.
