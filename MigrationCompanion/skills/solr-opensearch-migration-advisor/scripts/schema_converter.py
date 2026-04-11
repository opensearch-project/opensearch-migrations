"""
Converts Apache Solr schema definitions to OpenSearch index mappings.

Supports both Solr schema.xml format and the Solr Schema API JSON format.
"""

import json
import xml.etree.ElementTree as ET
from typing import Any


# Maps Solr field type class names (short and fully-qualified) to OpenSearch
# mapping types.
SOLR_TYPE_TO_OPENSEARCH: dict[str, str] = {
    # Text
    "solr.TextField": "text",
    "TextField": "text",
    # Keyword / string
    "solr.StrField": "keyword",
    "StrField": "keyword",
    # Integers
    "solr.IntPointField": "integer",
    "IntPointField": "integer",
    "solr.TrieIntField": "integer",
    "TrieIntField": "integer",
    # Longs
    "solr.LongPointField": "long",
    "LongPointField": "long",
    "solr.TrieLongField": "long",
    "TrieLongField": "long",
    # Floats
    "solr.FloatPointField": "float",
    "FloatPointField": "float",
    "solr.TrieFloatField": "float",
    "TrieFloatField": "float",
    # Doubles
    "solr.DoublePointField": "double",
    "DoublePointField": "double",
    "solr.TrieDoubleField": "double",
    "TrieDoubleField": "double",
    # Dates
    "solr.DatePointField": "date",
    "DatePointField": "date",
    "solr.TrieDateField": "date",
    "TrieDateField": "date",
    # Booleans
    "solr.BoolField": "boolean",
    "BoolField": "boolean",
    # Binary
    "solr.BinaryField": "binary",
    "BinaryField": "binary",
    # Geo
    "solr.LatLonPointSpatialField": "geo_point",
    "LatLonPointSpatialField": "geo_point",
    "solr.SpatialRecursivePrefixTreeFieldType": "geo_shape",
    "SpatialRecursivePrefixTreeFieldType": "geo_shape",
}

# Solr field attributes that influence OpenSearch mapping options.
_INDEXED_ATTR = "indexed"
_STORED_ATTR = "stored"
_MULTI_VALUED_ATTR = "multiValued"
_DOC_VALUES_ATTR = "docValues"


def _solr_bool(value: str | None, default: bool = True) -> bool:
    """Convert a Solr XML attribute string to a Python bool."""
    if value is None:
        return default
    return value.strip().lower() == "true"


class SchemaConverter:
    """Converts a Solr schema to an OpenSearch index mapping.

    Usage::

        converter = SchemaConverter()

        # From schema.xml content
        mapping = converter.convert_xml(schema_xml_string)

        # From Solr Schema API JSON
        mapping = converter.convert_json(schema_api_json_string)

        print(json.dumps(mapping, indent=2))
    """

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def _get_field_type_map_xml(self, root: ET.Element) -> dict[str, str]:
        """Build a lookup from field-type name → Solr class name from XML."""
        field_type_map: dict[str, str] = {}
        for ft in root.iter("fieldType"):
            name = ft.get("name")
            class_ = ft.get("class", "")
            if name:
                field_type_map[name] = class_
        return field_type_map

    def _process_fields_xml(
        self,
        root: ET.Element,
        field_type_map: dict[str, str]
    ) -> dict[str, Any]:
        """Convert Solr fields to OpenSearch properties from XML."""
        properties: dict[str, Any] = {}
        for field in root.iter("field"):
            field_name = field.get("name")
            if not field_name or field_name.startswith("_"):
                # Skip internal Solr fields (e.g. _version_)
                continue

            field_type_name = field.get("type", "")
            solr_class = field_type_map.get(field_type_name, field_type_name)
            os_type = SOLR_TYPE_TO_OPENSEARCH.get(solr_class, "keyword")

            prop: dict[str, Any] = {"type": os_type}

            # Propagate store/index hints where relevant.
            if not _solr_bool(field.get(_STORED_ATTR)):
                prop["store"] = False

            if not _solr_bool(field.get(_INDEXED_ATTR)):
                prop["index"] = False

            if _solr_bool(field.get(_DOC_VALUES_ATTR), default=False):
                prop["doc_values"] = True

            properties[field_name] = prop
        return properties

    def _process_dynamic_fields_xml(
        self,
        root: ET.Element,
        field_type_map: dict[str, str]
    ) -> list[dict[str, Any]]:
        """Convert Solr dynamic fields to OpenSearch dynamic templates from XML."""
        dynamic_templates: list[dict[str, Any]] = []
        for df in root.iter("dynamicField"):
            name_pattern = df.get("name", "")
            field_type_name = df.get("type", "")
            solr_class = field_type_map.get(field_type_name, field_type_name)
            os_type = SOLR_TYPE_TO_OPENSEARCH.get(solr_class, "keyword")

            # Build a best-effort dynamic template.
            if name_pattern.startswith("*_"):
                suffix = name_pattern[2:]
                template_name = f"dynamic_{suffix}"
                dynamic_templates.append(
                    {
                        template_name: {
                            "match": name_pattern,
                            "match_pattern": "wildcard",
                            "mapping": {"type": os_type},
                        }
                    }
                )
        return dynamic_templates

    def convert_xml(self, schema_xml: str) -> dict[str, Any]:
        """Convert a Solr ``schema.xml`` document to an OpenSearch mapping.

        Args:
            schema_xml: The full text content of a Solr ``schema.xml`` file.

        Returns:
            A dict representing the OpenSearch index mapping, suitable for
            serialisation with :func:`json.dumps`.

        Raises:
            ValueError: If the XML cannot be parsed or does not look like a
                Solr schema document.
        """
        try:
            root = ET.fromstring(schema_xml)
        except ET.ParseError as exc:
            raise ValueError(f"Invalid XML: {exc}") from exc

        if root.tag != "schema":
            raise ValueError(
                f"Expected root element <schema>, got <{root.tag}>"
            )

        field_type_map = self._get_field_type_map_xml(root)
        properties = self._process_fields_xml(root, field_type_map)
        dynamic_templates = self._process_dynamic_fields_xml(root, field_type_map)

        mapping: dict[str, Any] = {"mappings": {"properties": properties}}
        if dynamic_templates:
            mapping["mappings"]["dynamic_templates"] = dynamic_templates

        return mapping

    def _get_field_type_map(self, schema: dict[str, Any]) -> dict[str, str]:
        """Build a lookup from field-type name → Solr class name."""
        field_type_map: dict[str, str] = {}
        for ft in schema.get("fieldTypes", []):
            name = ft.get("name")
            class_ = ft.get("class", "")
            if name:
                field_type_map[name] = class_
        return field_type_map

    def _process_fields(
        self,
        schema: dict[str, Any],
        field_type_map: dict[str, str]
    ) -> dict[str, Any]:
        """Convert Solr fields to OpenSearch properties."""
        properties: dict[str, Any] = {}
        for field in schema.get("fields", []):
            field_name = field.get("name")
            if not field_name or field_name.startswith("_"):
                continue

            field_type_name = field.get("type", "")
            solr_class = field_type_map.get(field_type_name, field_type_name)
            os_type = SOLR_TYPE_TO_OPENSEARCH.get(solr_class, "keyword")

            prop: dict[str, Any] = {"type": os_type}

            if not field.get("stored", True):
                prop["store"] = False

            if not field.get("indexed", True):
                prop["index"] = False

            if field.get("docValues", False):
                prop["doc_values"] = True

            properties[field_name] = prop
        return properties

    def _process_dynamic_fields(
        self,
        schema: dict[str, Any],
        field_type_map: dict[str, str]
    ) -> list[dict[str, Any]]:
        """Convert Solr dynamic fields to OpenSearch dynamic templates."""
        dynamic_templates: list[dict[str, Any]] = []
        for df in schema.get("dynamicFields", []):
            name_pattern = df.get("name", "")
            field_type_name = df.get("type", "")
            solr_class = field_type_map.get(field_type_name, field_type_name)
            os_type = SOLR_TYPE_TO_OPENSEARCH.get(solr_class, "keyword")

            if name_pattern.startswith("*_"):
                suffix = name_pattern[2:]
                template_name = f"dynamic_{suffix}"
                dynamic_templates.append(
                    {
                        template_name: {
                            "match": name_pattern,
                            "match_pattern": "wildcard",
                            "mapping": {"type": os_type},
                        }
                    }
                )
        return dynamic_templates

    def convert_json(self, schema_api_json: str) -> dict[str, Any]:
        """Convert a Solr Schema API JSON document to an OpenSearch mapping.

        The Solr Schema API returns JSON that looks like::

            {
              "schema": {
                "fieldTypes": [...],
                "fields": [...],
                "dynamicFields": [...]
              }
            }

        Args:
            schema_api_json: JSON string from the Solr Schema API
                (``/solr/<collection>/schema``).

        Returns:
            A dict representing the OpenSearch index mapping.

        Raises:
            ValueError: If the JSON cannot be parsed or is missing required
                keys.
        """
        try:
            data = json.loads(schema_api_json)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid JSON: {exc}") from exc

        schema = data.get("schema", data)

        field_type_map = self._get_field_type_map(schema)
        properties = self._process_fields(schema, field_type_map)
        dynamic_templates = self._process_dynamic_fields(schema, field_type_map)

        mapping: dict[str, Any] = {"mappings": {"properties": properties}}
        if dynamic_templates:
            mapping["mappings"]["dynamic_templates"] = dynamic_templates

        return mapping
