"""
Example: Typed Python document transformation using dataclasses.

Transforms documents during backfill by rewriting index names via prefix rules
and optionally adding/removing fields from document bodies.

Transformer config JSON (for doc backfill --doc-transformer-config):
{
  "JsonPythonTransformerProvider": {
    "initializationScriptFile": "/path/to/doc_transform.py",
    "bindingsObject": "{\"index_rewrites\": [...], \"add_fields\": {...}}"
  }
}

The transform function receives a List of document Maps, each with keys:
- operation: {"index": {"_index": "...", "_id": "..."}}
- document: {field: value, ...}
"""
from dataclasses import dataclass, field


@dataclass
class IndexRewrite:
    """Rewrites index names by prefix substitution."""
    source_prefix: str
    target_prefix: str


@dataclass
class DocTransformConfig:
    """Parsed configuration for document transformations."""
    index_rewrites: list = field(default_factory=list)
    add_fields: dict = field(default_factory=dict)


def main(context):
    config = DocTransformConfig()
    raw_rewrites = context.get('index_rewrites')
    if raw_rewrites is not None:
        for r in raw_rewrites:
            config.index_rewrites.append(IndexRewrite(
                source_prefix=str(r.get('source_prefix')),
                target_prefix=str(r.get('target_prefix'))
            ))
    raw_add = context.get('add_fields')
    if raw_add is not None:
        for key in raw_add.keys():
            config.add_fields[str(key)] = raw_add.get(key)

    def transform(documents):
        """Transform a batch of documents (List of Maps)."""
        for doc in documents:
            _rewrite_index(doc, config.index_rewrites)
            _add_fields(doc, config.add_fields)
        return documents

    return transform


def _rewrite_index(doc, rewrites):
    """Rewrite the _index in the operation metadata."""
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


def _add_fields(doc, add_fields):
    """Add fields to the document body."""
    body = doc.get('document')
    if body is None:
        return
    for key, value in add_fields.items():
        body[key] = value


main  # GraalPy requires the last expression to be the entry point
