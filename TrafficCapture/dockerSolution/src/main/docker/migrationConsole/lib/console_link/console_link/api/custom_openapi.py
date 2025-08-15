from fastapi import FastAPI
from fastapi.openapi.utils import get_openapi


class OpenApiWithNullables:
    app: FastAPI

    def __init__(self, app: FastAPI) -> None:
        self.app = app

    def openapi_with_nullables(self):
        if self.app.openapi_schema:
            return self.app.openapi_schema
        schema = get_openapi(
            title=self.app.title,
            version=self.app.version,
            description=getattr(self.app, "description", None),
            routes=self.app.routes,
            openapi_version="3.0.3",
        )

        def make_nullable_from_union(node: dict, key: str) -> bool:
            """
            If node[key] is anyOf/oneOf and is exactly [X, null] (in any order),
            replace it with X (+ nullable: true). If X is a pure $ref, wrap in allOf.
            Returns True if a transform happened.
            """
            comp = node.get(key)
            if not isinstance(comp, list):
                return False
            non_null = [s for s in comp if isinstance(s, dict) and s.get("type") != "null"]
            has_null = any(isinstance(s, dict) and s.get("type") == "null" for s in comp)
            if len(non_null) != 1 or not has_null:
                return False
            x = non_null[0]
            # remove the composition
            node.pop(key, None)

            if "$ref" in x and len(x.keys()) == 1:
                # 3.0: attach nullable to a $ref via allOf wrapper
                node.clear()
                node["allOf"] = [{"$ref": x["$ref"]}]
                node["nullable"] = True
            else:
                # merge X into node
                for k in list(node.keys()):
                    # clear conflicting composition remnants if any
                    if k in ("anyOf", "oneOf", "allOf", "type", "format", "enum", "items",
                             "properties", "additionalProperties", "required", "pattern",
                             "minLength", "maxLength", "minimum", "maximum", "title",
                             "description", "default", "examples"):
                        # keep existing node keys unless you prefer X to win; here X wins
                        pass
                node.update(x)
                node["nullable"] = True
            return True

        def make_nullable_from_type_array(node: dict) -> bool:
            """
            If node.type is ["X","null"] -> type "X", nullable true.
            Only for the simple case of exactly two entries.
            """
            t = node.get("type")
            if isinstance(t, list) and len(t) == 2 and "null" in t:
                non_null = [v for v in t if v != "null"][0]
                node["type"] = non_null
                node["nullable"] = True
                return True
            return False

        def visit(n):
            if isinstance(n, dict):
                # Convert anyOf/oneOf unions with null
                changed = make_nullable_from_union(n, "anyOf") or make_nullable_from_union(n, "oneOf")
                # Convert type: ["X","null"]
                changed = make_nullable_from_type_array(n) or changed
                # Recurse
                for v in list(n.values()):
                    visit(v)
            elif isinstance(n, list):
                for v in n:
                    visit(v)

        visit(schema)
        self.app.openapi_schema = schema
        return schema
