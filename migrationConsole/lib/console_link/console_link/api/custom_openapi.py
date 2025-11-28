from __future__ import annotations

from typing import Any, Dict, List
from fastapi import FastAPI
from fastapi.openapi.utils import get_openapi


class OpenApiWithNullables:
    """Build an OpenAPI 3.0.3 schema and normalize nullable unions."""

    def __init__(self, app: FastAPI) -> None:
        self.app = app

    def openapi_with_nullables(self) -> Dict[str, Any]:
        cached = getattr(self.app, "openapi_schema", None)
        if cached:
            return cached

        schema = get_openapi(
            title=self.app.title,
            version=self.app.version,
            description=getattr(self.app, "description", None),
            routes=self.app.routes,
            openapi_version="3.0.3",
        )

        self._normalize_nullables(schema)
        self.app.openapi_schema = schema
        return schema

    def _normalize_nullables(self, root: Any) -> None:
        """Iteratively walk dicts/lists and normalize nullable shapes."""
        stack: List[Any] = [root]
        while stack:
            node = stack.pop()
            if isinstance(node, dict):
                # Apply transforms with early exits to keep complexity low.
                self._make_nullable_from_union(node, "anyOf")
                self._make_nullable_from_union(node, "oneOf")
                self._make_nullable_from_type_array(node)

                # Queue children
                stack.extend(node.values())
            elif isinstance(node, list):
                stack.extend(node)

    def _make_nullable_from_union(self, node: Dict[str, Any], key: str) -> bool:
        """
        If node[key] is a composition that's exactly [X, null] in any order,
        replace it with X + nullable: true. If X is a pure $ref, wrap via allOf.
        """
        comp = node.get(key)
        if not isinstance(comp, list):
            return False

        non_null = [s for s in comp if isinstance(s, dict) and s.get("type") != "null"]
        if len(non_null) != 1 or not self._has_explicit_null(comp):
            return False

        x = non_null[0]
        # Remove the composition key we just handled.
        node.pop(key, None)

        if self._is_pure_ref(x):
            # 3.0: attach nullable to a $ref via allOf wrapper
            node.clear()
            node["allOf"] = [{"$ref": x["$ref"]}]
            node["nullable"] = True
            return True

        # Merge X into node; X wins for overlapping keys.
        self._prune_composition_keys(node)
        node.update(x)
        node["nullable"] = True
        return True

    def _make_nullable_from_type_array(self, node: Dict[str, Any]) -> bool:
        """
        If node.type is ["X","null"] -> set type "X" and nullable true.
        Only for the simple case of exactly two entries.
        """
        t = node.get("type")
        if not (isinstance(t, list) and len(t) == 2 and "null" in t):
            return False

        non_null = next((v for v in t if v != "null"), None)
        if non_null is None:
            return False

        node["type"] = non_null
        node["nullable"] = True
        return True

    @staticmethod
    def _has_explicit_null(comp: List[Any]) -> bool:
        return any(isinstance(s, dict) and s.get("type") == "null" for s in comp)

    @staticmethod
    def _is_pure_ref(schema: Dict[str, Any]) -> bool:
        return "$ref" in schema and len(schema) == 1

    @staticmethod
    def _prune_composition_keys(node: Dict[str, Any]) -> None:
        """Drop keys that can conflict when flattening X into node."""
        for k in ("anyOf", "oneOf"):
            node.pop(k, None)
