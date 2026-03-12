# Python Transformations

Write document and metadata transformations in Python and run them at migration time — no Java required.

This module uses [GraalPy](https://www.graalvm.org/python/) to execute Python code inside the JVM.
You can use the Python standard library, `dataclasses`, and third-party pip packages.

## Quick Start

### 1. Write a transform script

```python
# my_transform.py
def main(context):
    prefix = context.get("prefix", "")

    def transform(document):
        uri = document.get("URI")
        if uri is not None:
            document["URI"] = "/" + prefix + uri[1:]
        return document

    return transform

main  # GraalPy returns the last evaluated expression
```

The script must evaluate to a **`main` function** that:
1. Accepts a `context` dict (parsed from `bindingsObject`)
2. Returns a **`transform` function** that takes a JSON-like object and returns the transformed result

### 2. Pass it at runtime

```bash
# From a file on disk
--doc-transformer-config '[{"JsonPythonTransformerProvider": {
  "bindingsObject": "{}",
  "initializationScriptFile": "/path/to/my_transform.py"
}}]'

# From a config file (recommended)
--doc-transformer-config-file /path/to/transform-config.json
```

The same flags work for both the document backfill (RFS) and the traffic replayer.
For metadata migrations, use `--transformer-config` / `--transformer-config-file`.

## Configuration Reference

| Key | Required | Description |
|-----|----------|-------------|
| `initializationScriptFile` | One of three | Path or `s3://` URI to a `.py` file |
| `initializationScript` | One of three | Inline Python source code |
| `initializationResourcePath` | One of three | Classpath resource path (for bundled transforms) |
| `bindingsObject` | Yes | JSON string parsed and passed to `main(context)` |
| `pythonModulePath` | No | Path, `.tar.gz` file, or `s3://` URI to a [GraalPy venv](https://www.graalvm.org/python/docs/) |

Exactly one of the three script sources must be provided.

## Using Third-Party Pip Packages

If your transformation needs pip packages (e.g. pydantic, requests, etc.), create a
[GraalPy virtual environment](https://www.graalvm.org/python/docs/) and point to it
at runtime via `pythonModulePath`.

### Step 1: Install GraalPy

```bash
# macOS
brew install graalpy

# Linux
curl -L https://github.com/oracle/graalpy/releases/download/graal-25.0.2/graalpy-25.0.2-linux-amd64.tar.gz | tar xz
export PATH=$PWD/graalpy-25.0.2-linux-amd64/bin:$PATH
```

### Step 2: Create a venv and install packages

```bash
graalpy -m venv /opt/transform-venv
/opt/transform-venv/bin/pip install pydantic  # or any packages you need
```

If you have a `requirements.txt` or a pip-installable project:

```bash
/opt/transform-venv/bin/pip install -r requirements.txt
# or
/opt/transform-venv/bin/pip install ./my-transform-package
```

### Step 3: Pass the venv at runtime

```bash
--doc-transformer-config '[{
  "JsonPythonTransformerProvider": {
    "initializationScriptFile": "/opt/transforms/entry_point.py",
    "bindingsObject": "{}",
    "pythonModulePath": "/opt/transform-venv"
  }
}]'
```

### Packaging for distribution

Package the venv as a tarball — `pythonModulePath` accepts `.tar.gz` files directly
and auto-extracts them at startup:

```bash
# Create a tarball
tar czf transform-venv.tar.gz -C /opt transform-venv

# Upload to S3
aws s3 cp transform-venv.tar.gz s3://my-bucket/transforms/
aws s3 cp entry_point.py s3://my-bucket/transforms/
```

### Deploying to EKS / remote environments

Both `initializationScriptFile` and `pythonModulePath` accept `s3://` URIs.
The transformer downloads them automatically at startup — no manual file staging needed:

```bash
--doc-transformer-config '[{
  "JsonPythonTransformerProvider": {
    "initializationScriptFile": "s3://my-bucket/transforms/entry_point.py",
    "bindingsObject": "{}",
    "pythonModulePath": "s3://my-bucket/transforms/transform-venv.tar.gz"
  }
}]'
```

The S3 client uses default credential resolution (IAM role, environment variables, etc.),
so in EKS it picks up the pod's IAM role automatically.

### Local deployment

For local or VM-based deployments, you can point directly to local files:

```bash
--doc-transformer-config '[{
  "JsonPythonTransformerProvider": {
    "initializationScriptFile": "/opt/transforms/entry_point.py",
    "bindingsObject": "{}",
    "pythonModulePath": "/opt/transforms/transform-venv.tar.gz"
  }
}]'
```

## Complete Example: Custom Python Project

See the [`custom_transform/`](./custom_transform/) directory for a complete, standalone
Python project that demonstrates:

- A real Python package with `pyproject.toml` and `Pipfile`
- Pydantic-based config validation
- pytest tests
- A GraalPy entry point script
- Full deployment instructions

This is the recommended pattern for production transformations.

## Bundled Examples

Two example scripts are bundled as classpath resources:

### Document transformation: rewrite index names and add fields

```bash
--doc-transformer-config '[{
  "JsonPythonTransformerProvider": {
    "initializationResourcePath": "python/doc_transform.py",
    "bindingsObject": "{\"index_rewrites\": [{\"source_prefix\": \"logs-2024\", \"target_prefix\": \"logs-migrated-2024\"}], \"add_fields\": {\"migrated\": true}}"
  }
}]'
```

### Metadata transformation: rewrite field types

```bash
--transformer-config '[{
  "JsonPythonTransformerProvider": {
    "initializationResourcePath": "python/metadata_transform.py",
    "bindingsObject": "{\"rules\": [{\"source_type\": \"string\", \"target_type\": \"text\", \"remove_keys\": [\"doc_values\"]}]}"
  }
}]'
```

## Script Contract

### For document backfill (RFS)

The `transform` function receives a **list** of document maps:

```python
[
    {
        "operation": {"_index": "my-index", "_id": "doc1"},
        "document": {"field1": "value1", ...}
    }
]
```

Return the (possibly modified) list.

### For metadata migration

The `transform` function receives a **single map**:

```python
{
    "type": "index",           # or "legacy_template", "index_template", "component_template"
    "name": "my-index",
    "body": {"mappings": {...}, "settings": {...}}
}
```

Return the (possibly modified) map.

### For traffic replay

The `transform` function receives a **single map** representing the HTTP request.
Return the (possibly modified) map.

## Thread Safety

Each `PythonTransformer` instance is **not thread-safe**. The framework creates one instance per
worker thread, so your script does not need to handle concurrency.
