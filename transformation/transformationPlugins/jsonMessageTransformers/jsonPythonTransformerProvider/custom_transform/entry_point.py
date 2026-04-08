"""
GraalPy entry point for the custom transformation.

This script is loaded by the migration tool via:
  "initializationScriptFile": "/path/to/entry_point.py"

It must evaluate to a `main` function that accepts a context dict
(parsed from bindingsObject) and returns a transform function.
"""
import json
from custom_transform.transform import DocTransformConfig, create_transform


def _to_python(obj):
    """Convert GraalPy foreign objects to native Python types."""
    if hasattr(obj, 'keys'):
        return {str(k): _to_python(obj[k]) for k in obj.keys()}
    if hasattr(obj, '__len__') and not isinstance(obj, (str, bytes)):
        return [_to_python(item) for item in obj]
    return obj


def main(context):
    config = DocTransformConfig.model_validate_json(
        json.dumps(_to_python(context))
    )
    return create_transform(config)


main  # GraalPy requires the last expression to be the entry point
