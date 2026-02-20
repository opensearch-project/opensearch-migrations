## Overview

The Cluster Tools is a unified command-line interface designed to manage and execute various utilities related to Opensearch Migrations. The cluster tools dynamically detects available tools in the `tools` directory, allowing for easy extension with new functionalities.

## Tools

The Cluster Tools provides a range of tools to facilitate efficient and reliable Opensearch Migrations.

For a list of available tools run:

```bash
cluster_tools
```

### Adding New Tools

To add a new tool:

1. Create a new Python script in the `src/tools` directory.
2. Define two functions:
    - `define_arguments(parser)`: Set up CLI arguments specific to the tool.
    - `main(args)`: Implement the tool's functionality.

The Migration Console CLI will automatically detect and include the new tool.

**Example:**

```python
from cluster_tools.environment import Environment
import argparse

def define_arguments(parser):
    parser.description = 'An example tool.'
    parser.add_argument('--example', help='An example argument.')

def main(env: Environment, args: argparse.Namespace):
    # Implement tool functionality here
    pass
```

## Installation

To install the Cluster Tools run:

```bash
pipenv install
```

## Usage

### Command Line Interface

The `cluster_tools` command provides a unified interface for all available tools.

It can be run either directly or through `pipenv run`.

For the following examples we will use a shell with `pipenv shell` activated.

**List Available Tools:**

```bash
cluster_tools
```

If no tool is specified, it will display the list of available tools.

**General Syntax:**

```bash
cluster_tools <tool_name> [options]
```

**Example:**

```bash
cluster_tools create_index <index_name> <number_of_shards>
```

## Development

### Project Structure

```
src/migration_console/: Main CLI application.
src/tools/: Individual tool scripts.
tests/: Unit tests for the tools.
setup.py: Installation script.
Pipfile & Pipfile.lock: Dependency management.
.gitignore: Specifies intentionally untracked files.
README.md: Project documentation.
```

### Unit Tests

To run unit tests, first install the development dependencies:

```bash
pipenv install --dev
```

Run the tests using pytest:

```bash
pipenv run test
```

### Coverage

Generate a coverage report to see how much of the code is tested.

**Command Line Report:**

```bash
pipenv run coverage report
```

**Generated HTML**

```bash
pipenv run coverage html
```

### Troubleshooting

#### Docker connection issue
```
docker.errors.DockerException: Error while fetching server API version: ('Connection aborted.', FileNotFoundError(2, 'No such file or directory'))
```

1. Confirm that Docker is running with a command such as `docker ps`
2. If `DOCKER_HOST` environment variable is not set, attempt to set this to the local socket, e.g. on Mac
```
export DOCKER_HOST=unix:///Users/<USER_NAME>/.docker/run/docker.sock
```
