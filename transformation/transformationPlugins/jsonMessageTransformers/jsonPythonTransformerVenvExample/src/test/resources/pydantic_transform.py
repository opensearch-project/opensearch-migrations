"""
Test entry point that uses pydantic for config validation.
Simulates a customer's transform script that depends on a pip package.

Note: The context object is a GraalPy polyglot proxy, not a native Python dict.
Use model_validate_json(str(context)) instead of model_validate(dict(context))
to avoid SystemError from pydantic trying to introspect proxy objects.
"""
from pydantic import BaseModel
from typing import List, Dict


class IndexRewrite(BaseModel):
    source_prefix: str
    target_prefix: str


class DocTransformConfig(BaseModel):
    index_rewrites: List[IndexRewrite] = []
    add_fields: Dict[str, object] = {}


def _to_python(obj):
    """Convert GraalPy foreign objects to native Python types."""
    if hasattr(obj, 'keys'):
        return {str(k): _to_python(obj[k]) for k in obj.keys()}
    if hasattr(obj, '__len__') and not isinstance(obj, (str, bytes)):
        return [_to_python(item) for item in obj]
    return obj


def main(context):
    import json
    config = DocTransformConfig.model_validate_json(
        json.dumps(_to_python(context))
    )

    def transform(documents):
        for doc in documents:
            op = doc.get('operation')
            if op is not None:
                idx = op.get('_index')
                if idx is not None:
                    idx_str = str(idx)
                    for rw in config.index_rewrites:
                        if idx_str.startswith(rw.source_prefix):
                            op['_index'] = rw.target_prefix + idx_str[len(rw.source_prefix):]
                            break
            body = doc.get('document')
            if body is not None:
                for key, value in config.add_fields.items():
                    body[key] = value
        return documents

    return transform


main
