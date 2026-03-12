"""
Example: Typed Python metadata transformation using dataclasses.

Transforms index metadata by rewriting field types according to
configurable rules. Each rule specifies a source_type to match,
a target_type to replace it with, and optional keys to remove.

Transformer config JSON:
{
  "JsonPythonTransformerProvider": {
    "initializationScriptFile": "/path/to/metadata_transform.py",
    "bindingsObject": "{\"rules\": [{...}]}"
  }
}

Rule format: {"source_type": "string", "target_type": "text",
              "remove_keys": ["doc_values"]}

The transform function receives a Map with keys: type, name, body
- type: "index", "legacy_template", "index_template", "component_template"
- name: the index/template name
- body: the index settings/mappings as a nested Map
"""
from dataclasses import dataclass, field


@dataclass
class FieldTypeRule:
    """A rule that rewrites a field's 'type' and removes keys."""
    source_type: str
    target_type: str
    remove_keys: list = field(default_factory=list)


def _apply_rules(node, rules):
    """Recursively walk a mapping tree and apply type-rewrite rules."""
    if hasattr(node, 'get') and hasattr(node, '__setitem__'):
        for rule in rules:
            current_type = node.get('type')
            if current_type is not None and str(current_type) == rule.source_type:
                node['type'] = rule.target_type
                for k in rule.remove_keys:
                    if k in node:
                        del node[k]
        for key in node.keys():
            _apply_rules(node[key], rules)
    elif hasattr(node, '__iter__') and not isinstance(node, (str, bytes)):
        for item in node:
            _apply_rules(item, rules)


def main(context):
    rules = []
    for r in context.get('rules'):
        rules.append(FieldTypeRule(
            source_type=str(r.get('source_type')),
            target_type=str(r.get('target_type')),
            remove_keys=[str(k) for k in r.get('remove_keys')]
        ))

    def transform(document):
        body = document.get('body')
        if body is not None:
            _apply_rules(body, rules)
        return document

    return transform


main
