# Kiro AI Assistant for OpenSearch Migrations

Configuration files for [Kiro](https://kiro.dev), an AI-powered development assistant for OpenSearch Migrations.

## Quick Start (Gradle)

```bash
# Run kiro chat with the opensearch-migration agent
./gradlew :kiro-cli:kiroAgent

# Run kiro chat without a specific agent
./gradlew :kiro-cli:kiroChat

# Create kiro-assistant.tar.gz for distribution
./gradlew :kiro-cli:packageKiro
```

### With Audit Logging

```bash
script -q ~/kiro-audit/$(date +%Y%m%d-%H%M%S).log ./gradlew :kiro-cli:kiroAgent
```

## Download from Release

Download `kiro-assistant.tar.gz` from the [latest release](https://github.com/opensearch-project/opensearch-migrations/releases) and extract:

```bash
tar -xzf kiro-assistant.tar.gz -C /path/to/your/workspace
```

## Copy from Repository

```bash
# From main branch
git archive --remote=https://github.com/opensearch-project/opensearch-migrations.git HEAD kiro-cli/kiro-cli-config | \
  tar -x --strip-components=2 -C .kiro

# From a fork
curl -L https://github.com/<username>/opensearch-migrations/archive/refs/heads/<branch>.tar.gz | \
  tar -xz --strip-components=3 -C .kiro opensearch-migrations-<branch>/kiro-cli/kiro-cli-config
```

## Save Chat History

Within a Kiro session, use `/save` to save your conversation.

## Directory Structure

```
kiro-cli/
├── build.gradle
├── README.md
└── kiro-cli-config/
    ├── agents/opensearch-migration.json
    ├── prompts/start.md
    ├── settings/hooks.json
    └── steering/
        ├── deployment.md
        ├── migration-prompt.md
        ├── product.md
        └── workflow.md
```

## Requirements

- [Kiro CLI](https://kiro.dev) installed
