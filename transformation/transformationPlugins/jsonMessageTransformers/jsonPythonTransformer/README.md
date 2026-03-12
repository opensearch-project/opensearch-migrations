# jsonPythonTransformer

Core engine that executes Python transformation scripts inside the JVM using [GraalPy](https://www.graalvm.org/python/).

This module provides `PythonTransformer`, which implements `IJsonTransformer` and evaluates Python code
via the GraalVM Polyglot API. It supports the Python standard library, `dataclasses`, and pip packages
installed at runtime via an external [GraalPy venv](https://www.graalvm.org/python/docs/).

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
│  - pip packages via external GraalPy venv        │
│  - Java ↔ Python interop via polyglot API        │
└─────────────────────────────────────────────────┘
```

## Runtime venv support

Pass a `venvPath` to `PythonTransformer` to use an external GraalPy venv with arbitrary pip packages.
This is exposed to users via the `pythonModulePath` config key in `JsonPythonTransformerProvider`.

See the [jsonPythonTransformerProvider README](../jsonPythonTransformerProvider/README.md) for user-facing
documentation and a [complete example project](../jsonPythonTransformerProvider/custom_transform/).
