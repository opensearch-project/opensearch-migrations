"""
Document transformation using pydantic for config validation.

Rewrites index names by prefix and optionally adds fields to documents.
"""
from pydantic import BaseModel
from typing import Dict, List, Optional


class IndexRewrite(BaseModel):
    """Rewrites index names by prefix substitution."""
    source_prefix: str
    target_prefix: str


class DocTransformConfig(BaseModel):
    """Configuration for document transformations."""
    index_rewrites: List[IndexRewrite] = []
    add_fields: Dict[str, object] = {}


def create_transform(config: DocTransformConfig):
    """Create a transform function from a validated config."""

    def transform(documents):
        for doc in documents:
            _rewrite_index(doc, config.index_rewrites)
            _add_fields(doc, config.add_fields)
        return documents

    return transform


def _rewrite_index(doc, rewrites: List[IndexRewrite]):
    op = doc.get('operation')
    if op is None:
        return
    index_name = op.get('_index')
    if index_name is None:
        return
    index_str = str(index_name)
    for rw in rewrites:
        if index_str.startswith(rw.source_prefix):
            op['_index'] = rw.target_prefix + index_str[len(rw.source_prefix):]
            break


def _add_fields(doc, add_fields: Optional[Dict]):
    body = doc.get('document')
    if body is None:
        return
    for key, value in add_fields.items():
        body[key] = value
