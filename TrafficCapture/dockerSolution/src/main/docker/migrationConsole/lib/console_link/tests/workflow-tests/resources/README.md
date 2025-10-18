# Workflow Test Resources

This directory contains test resources for the workflow CLI, providing a simple, self-contained testing environment that doesn't depend on any external services or migration-specific code.

## Directory Structure

```
resources/
├── README.md                    # This file
├── sample-config.yaml           # Generic test workflow configuration
├── hello-world-workflow.yaml    # Simple Argo workflow for testing
├── approval-workflow.yaml       # Argo workflow with approval step
└── scripts/                     # Test scripts with standard interface
    ├── getSample.sh            # Returns sample configuration
    ├── transformConfig.sh      # Mock config transformer
    ├── initWorkflow.sh         # Mock workflow initializer
    └── submitWorkflow.sh       # Mock workflow submitter
```

## Test Scripts

All scripts follow a standard interface:
- **Input**: Via stdin or file argument
- **Output**: To stdout
- **Errors**: To stderr with non-zero exit code

### getSample.sh
Returns the sample configuration from `sample-config.yaml`.

```bash
./getSample.sh
```

### transformConfig.sh
Mock transformer that validates and passes through configuration unchanged.

```bash
echo "test: data" | ./transformConfig.sh -
# or
./transformConfig.sh config.yaml
```

### initWorkflow.sh
Mock initializer that generates a test prefix.

```bash
echo "test: data" | ./initWorkflow.sh -
# or with custom prefix
echo "test: data" | ./initWorkflow.sh - custom-prefix
```

### submitWorkflow.sh
Mock submitter that returns fake workflow information as JSON.

```bash
echo "test: data" | ./submitWorkflow.sh - test-12345 ma
```

## Usage in Tests

The `ScriptRunner` service provides a Python interface to these scripts:

```python
from console_link.workflow.services.script_runner import ScriptRunner

runner = ScriptRunner()

# Get sample config
sample = runner.get_sample_config()

# Transform config
transformed = runner.transform_config(config_data)

# Initialize workflow
prefix = runner.init_workflow(config_data)

# Submit workflow
result = runner.submit_workflow(config_data, prefix, namespace="ma")
```

## Usage in CLI

The workflow CLI can use these scripts:

```bash
# View sample configuration
workflow configure sample

# Load sample into session
workflow configure sample --load

# View loaded config
workflow configure view

# Edit config
workflow configure edit
```

## Adding New Test Workflows

To add a new test workflow:

1. Create a new YAML file in this directory (e.g., `my-workflow.yaml`)
2. Follow the Argo Workflow specification
3. Keep it simple and self-contained
4. Document its purpose in this README

## Production Scripts

For production use, create similar scripts in a separate directory (e.g., `migrationScripts/`) that:
- Call the real orchestrationSpecs npm commands
- Connect to actual etcd instances
- Submit to real Kubernetes clusters

The workflow CLI can switch between test and production scripts using the `--script-dir` flag (when implemented).
