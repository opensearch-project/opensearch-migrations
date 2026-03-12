# jsonPythonTransformer

Core engine that executes Python transformation scripts inside the JVM using [GraalPy](https://www.graalvm.org/python/).

This module provides `PythonTransformer`, which implements `IJsonTransformer` and evaluates Python code
via the GraalVM Polyglot API. It supports the Python standard library, `dataclasses`, and pip packages
installed either at build time (via the GraalPy Gradle plugin) or at runtime (via an external venv).

## Architecture

```
┌─────────────────────────────────────────────────┐
│  JsonPythonTransformerProvider (SPI discovery)   │
│  - Parses config (script source, bindings, venv)│
│  - Creates PythonTransformer instances           │
└──────────────────────┬──────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────┐
│  PythonTransformer (this module)                 │
│  - Creates GraalPy polyglot context              │
│  - Evaluates Python script                       │
│  - Calls main(context) → transform function      │
│  - Delegates transformJson() to Python           │
└──────────────────────┬──────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────┐
│  GraalPy (Python on GraalVM)                     │
│  - Full Python 3 compatibility                   │
│  - pip packages via VirtualFileSystem or venv    │
│  - Java ↔ Python interop via polyglot API        │
└─────────────────────────────────────────────────┘
```

## Build-time pip packages

The `graalPy` block in `build.gradle` bundles pip packages into the jar:

```groovy
graalPy {
    packages = ["pydantic"]
}
```

These are available to all scripts without any runtime configuration.

## Runtime venv support

Pass a `venvPath` to `PythonTransformer` to use an external GraalPy venv with arbitrary pip packages.
This is exposed to users via the `pythonModulePath` config key in `JsonPythonTransformerProvider`.

See the [jsonPythonTransformerProvider README](../jsonPythonTransformerProvider/README.md) for user-facing documentation.
