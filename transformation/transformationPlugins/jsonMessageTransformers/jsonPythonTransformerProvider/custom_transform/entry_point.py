"""
GraalPy entry point for the custom transformation.

This script is loaded by the migration tool via:
  "initializationScriptFile": "/path/to/entry_point.py"

It must evaluate to a `main` function that accepts a context dict
(parsed from bindingsObject) and returns a transform function.
"""
from custom_transform.transform import DocTransformConfig, create_transform


def main(context):
    config = DocTransformConfig.model_validate(dict(context))
    return create_transform(config)


main  # GraalPy requires the last expression to be the entry point
