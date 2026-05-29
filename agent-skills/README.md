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
├── skills/                      # agent-agnostic
│   ├── Startup.md               # the agent-handoff briefing the CLI
│   │                            # exec's first
│   ├── migrating-to-opensearch/ # the assessment SOP
│   │   ├── SKILL.md
│   │   ├── references/          # ~18 per-topic deep-dives
│   │   └── assets/              # report templates
│   └── aoss-nextgen/            # AOSS NextGen target operator skill
│       └── SKILL.md
└── kiro/                        # Kiro-specific
    ├── agents/opensearch-migration.json
    ├── prompts/start.md
    ├── settings/hooks.json
    └── steering/
        ├── deployment.md
        ├── migration-prompt.md
        ├── product.md
        └── workflow.md
```

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
