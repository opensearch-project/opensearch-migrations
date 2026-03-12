# Custom Python Transformation Example

A complete, standalone Python project that demonstrates how to build a custom
document transformation using third-party pip packages and deploy it for use
with OpenSearch Migrations (RFS backfill or metadata migration).

This project uses [pydantic](https://docs.pydantic.dev/) for config validation
as an example, but you can use any pip packages your transformation needs.

## Project Structure

```
custom_transform/
├── Pipfile                  # Dependencies (pipenv)
├── Pipfile.lock
├── custom_transform/
│   ├── __init__.py
│   └── transform.py         # Your transformation logic
├── tests/
│   └── test_transform.py    # pytest tests
└── entry_point.py           # GraalPy entry point script
```

## Prerequisites

- Python 3.11+
- [pipenv](https://pipenv.pypa.io/) (`pip install pipenv`)
- [GraalPy 25.0+](https://www.graalvm.org/python/) (for building the deployment venv)

## Development

### Install dependencies

```bash
cd custom_transform
pipenv install --dev
```

### Run tests

```bash
pipenv run pytest
```

### Run linting

```bash
pipenv run flake8 custom_transform/ tests/
```

## How It Works

The migration tools (RFS, Metadata Migration) run on the JVM and use
[GraalPy](https://www.graalvm.org/python/) to execute Python transformations.
Your Python code runs inside the JVM process — no separate Python process needed.

The integration works through two files:

1. **`entry_point.py`** — A small script that imports your package and returns
   a `transform` function. This is what the migration tool loads.
2. **`custom_transform/transform.py`** — Your actual transformation logic,
   written as a normal Python module with tests, type hints, etc.

## Building for Deployment

To deploy your transformation, you need to create a
[GraalPy virtual environment](https://www.graalvm.org/python/docs/)
that contains your package and its dependencies.

### Step 1: Install GraalPy

```bash
# macOS
brew install graalpy

# Linux (download from https://github.com/oracle/graalpy/releases)
curl -L https://github.com/oracle/graalpy/releases/download/graal-25.0.2/graalpy-25.0.2-linux-amd64.tar.gz | tar xz
export PATH=$PWD/graalpy-25.0.2-linux-amd64/bin:$PATH
```

### Step 2: Create a GraalPy venv and install your package

```bash
# Create the venv
graalpy -m venv /tmp/transform-venv

# Install your package into it
/tmp/transform-venv/bin/pip install ./custom_transform
```

If your project uses a `Pipfile`, you can also export to requirements.txt:

```bash
pipenv requirements > requirements.txt
/tmp/transform-venv/bin/pip install -r requirements.txt
```

### Step 3: Package and upload to S3

```bash
# Create a tarball
tar czf transform-venv.tar.gz -C /tmp transform-venv

# Upload venv and entry point to S3
aws s3 cp transform-venv.tar.gz s3://my-bucket/transforms/
aws s3 cp entry_point.py s3://my-bucket/transforms/
```

## Running with RFS (Document Backfill)

### On the migration host

Both `initializationScriptFile` and `pythonModulePath` accept `s3://` URIs directly —
no manual file staging needed:

```bash
./runJavaWithClasspath.sh org.opensearch.migrations.RfsMigrateDocuments \
  --doc-transformer-config '[{
    "JsonPythonTransformerProvider": {
      "initializationScriptFile": "s3://my-bucket/transforms/entry_point.py",
      "bindingsObject": "{\"index_rewrites\": [{\"source_prefix\": \"logs-\", \"target_prefix\": \"migrated-logs-\"}], \"add_fields\": {\"migrated\": true}}",
      "pythonModulePath": "s3://my-bucket/transforms/transform-venv.tar.gz"
    }
  }]' \
  --snapshot-name my-snapshot \
  --target-host https://target-opensearch:9200
```

In EKS, the S3 client uses the pod's IAM role automatically.

### Using a config file (recommended for complex configs)

Create `transform-config.json`:

```json
[{
  "JsonPythonTransformerProvider": {
    "initializationScriptFile": "s3://my-bucket/transforms/entry_point.py",
    "bindingsObject": "{\"index_rewrites\": [{\"source_prefix\": \"logs-\", \"target_prefix\": \"migrated-logs-\"}], \"add_fields\": {\"migrated\": true}}",
    "pythonModulePath": "s3://my-bucket/transforms/transform-venv.tar.gz"
  }
}]
```

```bash
./runJavaWithClasspath.sh org.opensearch.migrations.RfsMigrateDocuments \
  --doc-transformer-config-file /opt/transforms/transform-config.json \
  --snapshot-name my-snapshot \
  --target-host https://target-opensearch:9200
```

## Running with Metadata Migration

```bash
./runJavaWithClasspath.sh org.opensearch.migrations.MetadataMigration migrate \
  --transformer-config '[{
    "JsonPythonTransformerProvider": {
      "initializationScriptFile": "s3://my-bucket/transforms/entry_point.py",
      "bindingsObject": "{\"rules\": [{\"source_type\": \"string\", \"target_type\": \"text\", \"remove_keys\": [\"doc_values\"]}]}",
      "pythonModulePath": "s3://my-bucket/transforms/transform-venv.tar.gz"
    }
  }]' \
  --source-host https://source-es:9200 \
  --target-host https://target-opensearch:9200
```

## Configuration Reference

| Key | Required | Description |
|-----|----------|-------------|
| `initializationScriptFile` | One of three | Path or `s3://` URI to the entry point `.py` file |
| `initializationScript` | One of three | Inline Python source code |
| `initializationResourcePath` | One of three | Classpath resource path (for bundled transforms) |
| `bindingsObject` | Yes | JSON string passed to `main(context)` for configuration |
| `pythonModulePath` | No | Path, `.tar.gz` file, or `s3://` URI to a GraalPy venv |

## Writing Your Own Transformation

### Entry point contract

Your entry point script must evaluate to a `main` function:

```python
def main(context):
    # context is a dict parsed from bindingsObject JSON
    # Do any one-time setup here

    def transform(document):
        # Transform the document and return it
        return document

    return transform

main  # GraalPy returns the last evaluated expression
```

### Document backfill shape

For RFS document backfill, `transform` receives a **list** of document dicts:

```python
[
    {
        "operation": {"_index": "my-index", "_id": "doc1"},
        "document": {"field1": "value1", ...}
    },
    ...
]
```

### Metadata migration shape

For metadata migration, `transform` receives a **single dict**:

```python
{
    "type": "index",           # or "legacy_template", "index_template", "component_template"
    "name": "my-index",
    "body": {
        "mappings": {...},
        "settings": {...}
    }
}
```
