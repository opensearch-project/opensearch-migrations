"""Tests for schema_converter.py"""
import sys
import os
import json
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))
from schema_converter import SchemaConverter, SOLR_TYPE_TO_OPENSEARCH


SIMPLE_SCHEMA_XML = """<?xml version="1.0" encoding="UTF-8" ?>
<schema name="test" version="1.6">
  <fieldType name="string" class="solr.StrField"/>
  <fieldType name="text_general" class="solr.TextField"/>
  <fieldType name="pint" class="solr.IntPointField"/>
  <fieldType name="plong" class="solr.LongPointField"/>
  <fieldType name="pfloat" class="solr.FloatPointField"/>
  <fieldType name="pdouble" class="solr.DoublePointField"/>
  <fieldType name="pdate" class="solr.DatePointField"/>
  <fieldType name="boolean" class="solr.BoolField"/>
  <fieldType name="location" class="solr.LatLonPointSpatialField"/>

  <field name="id" type="string" indexed="true" stored="true"/>
  <field name="title" type="text_general" indexed="true" stored="true"/>
  <field name="count" type="pint" indexed="true" stored="false"/>
  <field name="score" type="pfloat" indexed="false" stored="true"/>
  <field name="active" type="boolean" indexed="true" stored="true"/>
  <field name="location" type="location" indexed="true" stored="true"/>
  <field name="_version_" type="plong" indexed="true" stored="false"/>
</schema>"""

DYNAMIC_FIELD_SCHEMA_XML = """<?xml version="1.0" encoding="UTF-8" ?>
<schema name="test" version="1.6">
  <fieldType name="string" class="solr.StrField"/>
  <fieldType name="pint" class="solr.IntPointField"/>
  <field name="id" type="string"/>
  <dynamicField name="*_i" type="pint"/>
  <dynamicField name="*_s" type="string"/>
</schema>"""

SIMPLE_SCHEMA_JSON = json.dumps({
    "schema": {
        "fieldTypes": [
            {"name": "string", "class": "solr.StrField"},
            {"name": "text_general", "class": "solr.TextField"},
            {"name": "plong", "class": "solr.LongPointField"},
        ],
        "fields": [
            {"name": "id", "type": "string", "indexed": True, "stored": True},
            {"name": "title", "type": "text_general", "indexed": True, "stored": True},
            {"name": "count", "type": "plong", "indexed": True, "stored": False},
            {"name": "_version_", "type": "plong"},
        ],
        "dynamicFields": [
            {"name": "*_s", "type": "string"},
        ],
    }
})


@pytest.fixture
def converter():
    return SchemaConverter()


# --- convert_xml ---

def test_xml_basic_field_types(converter):
    mapping = converter.convert_xml(SIMPLE_SCHEMA_XML)
    props = mapping["mappings"]["properties"]
    assert props["id"]["type"] == "keyword"
    assert props["title"]["type"] == "text"
    assert props["count"]["type"] == "integer"
    assert props["score"]["type"] == "float"
    assert props["active"]["type"] == "boolean"
    assert props["location"]["type"] == "geo_point"


def test_xml_internal_fields_excluded(converter):
    mapping = converter.convert_xml(SIMPLE_SCHEMA_XML)
    assert "_version_" not in mapping["mappings"]["properties"]


def test_xml_stored_false(converter):
    mapping = converter.convert_xml(SIMPLE_SCHEMA_XML)
    assert mapping["mappings"]["properties"]["count"]["store"] is False


def test_xml_indexed_false(converter):
    mapping = converter.convert_xml(SIMPLE_SCHEMA_XML)
    assert mapping["mappings"]["properties"]["score"]["index"] is False


def test_xml_dynamic_templates(converter):
    mapping = converter.convert_xml(DYNAMIC_FIELD_SCHEMA_XML)
    templates = mapping["mappings"]["dynamic_templates"]
    names = [list(t.keys())[0] for t in templates]
    assert "dynamic_i" in names
    assert "dynamic_s" in names


def test_xml_invalid_raises(converter):
    with pytest.raises(ValueError, match="Invalid XML"):
        converter.convert_xml("not xml at all <<<")


def test_xml_wrong_root_raises(converter):
    with pytest.raises(ValueError, match="Expected root element"):
        converter.convert_xml("<notschema/>")


# --- convert_json ---

def test_json_basic_field_types(converter):
    mapping = converter.convert_json(SIMPLE_SCHEMA_JSON)
    props = mapping["mappings"]["properties"]
    assert props["id"]["type"] == "keyword"
    assert props["title"]["type"] == "text"
    assert props["count"]["type"] == "long"


def test_json_internal_fields_excluded(converter):
    mapping = converter.convert_json(SIMPLE_SCHEMA_JSON)
    assert "_version_" not in mapping["mappings"]["properties"]


def test_json_stored_false(converter):
    mapping = converter.convert_json(SIMPLE_SCHEMA_JSON)
    assert mapping["mappings"]["properties"]["count"]["store"] is False


def test_json_dynamic_templates(converter):
    mapping = converter.convert_json(SIMPLE_SCHEMA_JSON)
    templates = mapping["mappings"]["dynamic_templates"]
    names = [list(t.keys())[0] for t in templates]
    assert "dynamic_s" in names


def test_json_invalid_raises(converter):
    with pytest.raises(ValueError, match="Invalid JSON"):
        converter.convert_json("{bad json")


def test_json_no_schema_wrapper(converter):
    # Schema API JSON without the outer "schema" key should still work.
    raw = json.dumps({
        "fieldTypes": [{"name": "string", "class": "solr.StrField"}],
        "fields": [{"name": "title", "type": "string"}],
        "dynamicFields": [],
    })
    mapping = converter.convert_json(raw)
    assert mapping["mappings"]["properties"]["title"]["type"] == "keyword"


# --- SOLR_TYPE_TO_OPENSEARCH coverage ---

def test_type_map_contains_common_types():
    assert SOLR_TYPE_TO_OPENSEARCH["solr.TextField"] == "text"
    assert SOLR_TYPE_TO_OPENSEARCH["solr.StrField"] == "keyword"
    assert SOLR_TYPE_TO_OPENSEARCH["solr.IntPointField"] == "integer"
    assert SOLR_TYPE_TO_OPENSEARCH["solr.BoolField"] == "boolean"
    assert SOLR_TYPE_TO_OPENSEARCH["solr.LatLonPointSpatialField"] == "geo_point"
