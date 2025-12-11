# Workflow Builder

A schema-driven form builder for migration workflow configurations using Zod 4, React 19, and Cloudscape Design System.

## Quick Start

From the repository root:

```bash
# Start development server
./gradlew :workflowBuilder:npmDev

# Build for production
./gradlew :workflowBuilder:build

# Run tests
./gradlew :workflowBuilder:npmTest
```

## Gradle Tasks

| Task | Description |
|------|-------------|
| `./gradlew :workflowBuilder:npmDev` | Start development server |
| `./gradlew :workflowBuilder:build` | Build for production |
| `./gradlew :workflowBuilder:npmTest` | Run tests |
| `./gradlew :workflowBuilder:generateSchema` | Generate workflow schema from orchestrationSpecs |

## Schema Generation

The workflow JSON schema is generated from `orchestrationSpecs/packages/schemas` and output to `generated/schemas/workflow-schema.json`. This runs automatically as part of the build.

## Features

- **Schema-Driven Forms**: Form structure driven by JSON schema
- **Bidirectional Sync**: Edit via form fields or directly in YAML/JSON
- **Real-time Validation**: Validation with error messages mapped to form fields
- **YAML/JSON Toggle**: Switch between formats with automatic conversion

## Architecture

```
workflowBuilder/
├── src/
│   ├── components/           # React components
│   ├── hooks/                # React hooks
│   ├── lib/                  # Utilities
│   ├── schemas/              # Local schema definitions
│   └── styles/               # Styles
├── generated/
│   └── schemas/
│       └── workflow-schema.json  # Generated from orchestrationSpecs
└── build.gradle              # Gradle build configuration
```

## Technologies

- **React 19** - UI framework
- **Zod 4** - Schema validation
- **Cloudscape Design System** - AWS UI components
- **Vite** - Build tool
- **Vitest** - Testing framework
- **TypeScript** - Type safety
