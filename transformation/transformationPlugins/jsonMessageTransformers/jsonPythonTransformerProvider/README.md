# Python Transformations

Write document and metadata transformations in Python and run them at migration time — no Java required.

This module uses [GraalPy](https://www.graalvm.org/python/) to execute Python code inside the JVM.
You can use the Python standard library, `dataclasses`, and even third-party pip packages like `pydantic`.

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

main
```

The script must evaluate to a **`main` function** that:
1. Accepts a `context` dict (parsed from `bindingsObject`)
2. Returns a **`transform` function** that takes a JSON-like object and returns the transformed result

### 2. Pass it at runtime

```bash
# Inline script
--doc-transformer-config '[{"JsonPythonTransformerProvider": {"bindingsObject": "{\"prefix\": \"migrated_\"}", "initializationScript": "def main(ctx):\n    def transform(doc):\n        doc[\"tag\"] = \"migrated\"\n        return doc\n    return transform\nmain"}}]'

# From a file on disk
--doc-transformer-config '[{"JsonPythonTransformerProvider": {"bindingsObject": "{}", "initializationScriptFile": "/path/to/my_transform.py"}}]'

# From a bundled resource on the classpath
--doc-transformer-config '[{"JsonPythonTransformerProvider": {"bindingsObject": "{}", "initializationResourcePath": "python/doc_transform.py"}}]'
```

The same `--doc-transformer-config` / `--doc-transformer-config-file` / `--doc-transformer-config-base64` flags
work for both the document backfill (RFS) and the traffic replayer.
For metadata migrations, use the equivalent metadata transformer config flags.

## Configuration Reference

| Key | Required | Description |
|-----|----------|-------------|
| `initializationScript` | One of three | Inline Python source code |
| `initializationScriptFile` | One of three | Absolute path to a `.py` file on disk |
| `initializationResourcePath` | One of three | Classpath resource path (e.g. `python/doc_transform.py`) |
| `bindingsObject` | Yes | JSON string parsed and passed to `main(context)` |
| `pythonModulePath` | No | Path to a Python venv directory for third-party pip packages |

Exactly one of the three script sources must be provided. Specifying more than one is an error.

## Using Third-Party Pip Packages

### Option A: Build-time packages (bundled in the jar)

The `jsonPythonTransformer` module's `build.gradle` uses the GraalPy Gradle plugin to bundle pip packages:

```groovy
graalPy {
    packages = ["pydantic"]
}
```

Any package listed here is available to all Python scripts without extra configuration.

### Option B: Runtime venv (bring your own packages)

For packages not bundled at build time, create a GraalPy venv and point to it at runtime:

```bash
# Install GraalPy (https://www.graalvm.org/python/)
graalpy -m venv /opt/my-venv
/opt/my-venv/bin/pip install my-custom-package

# Pass the venv path in the transformer config
--doc-transformer-config '[{
  "JsonPythonTransformerProvider": {
    "bindingsObject": "{}",
    "initializationScriptFile": "/path/to/my_transform.py",
    "pythonModulePath": "/opt/my-venv"
  }
}]'
```

Your script can then `import my_custom_package` as usual.

## Examples

### Document transformation: rewrite index names and add fields

Use the bundled `python/doc_transform.py` to rewrite index prefixes and inject fields during backfill:

```bash
--doc-transformer-config '[{
  "JsonPythonTransformerProvider": {
    "initializationResourcePath": "python/doc_transform.py",
    "bindingsObject": "{\"index_rewrites\": [{\"source_prefix\": \"logs-2024\", \"target_prefix\": \"logs-migrated-2024\"}], \"add_fields\": {\"migrated\": true}}"
  }
}]'
```

Input document batch:
```json
[{"operation": {"_index": "logs-2024.01", "_id": "1"}, "document": {"message": "hello"}}]
```

Output:
```json
[{"operation": {"_index": "logs-migrated-2024.01", "_id": "1"}, "document": {"message": "hello", "migrated": true}}]
```

### Metadata transformation: rewrite field types

Use the bundled `python/metadata_transform.py` to convert Elasticsearch field types:

```bash
--doc-transformer-config '[{
  "JsonPythonTransformerProvider": {
    "initializationResourcePath": "python/metadata_transform.py",
    "bindingsObject": "{\"rules\": [{\"source_type\": \"string\", \"target_type\": \"text\", \"remove_keys\": [\"doc_values\"]}]}"
  }
}]'
```

This rewrites `"type": "string"` → `"type": "text"` and removes `doc_values` from matching field definitions.

### Pydantic validation example

For strict config validation, use pydantic (bundled at build time):

```python
from pydantic import BaseModel
from typing import List

class Rule(BaseModel):
    source_type: str
    target_type: str
    remove_keys: List[str] = []

class Config(BaseModel):
    rules: List[Rule]

def main(context):
    config = Config.model_validate_json(str(context))

    def transform(document):
        # Use config.rules to transform...
        return document

    return transform

main
```

Pass the config as a JSON string in `bindingsObject` — pydantic validates it at initialization time,
failing fast if the config is malformed.

## Script Contract

### For document backfill (RFS)

The `transform` function receives a **list** of document maps. Each map has:
- `operation` — `{"_index": "...", "_id": "..."}`
- `document` — the document body as a dict

Return the (possibly modified) list.

### For traffic replay / metadata

The `transform` function receives a **single map** (the request or metadata item).
Return the (possibly modified) map.

### For metadata migration

The `transform` function receives a map with:
- `type` — `"index"`, `"legacy_template"`, `"index_template"`, or `"component_template"`
- `name` — the index or template name
- `body` — the settings/mappings as a nested dict

Return the (possibly modified) map.

## Thread Safety

Each `PythonTransformer` instance is **not thread-safe**. The framework creates one instance per
worker thread, so your script does not need to handle concurrency.
