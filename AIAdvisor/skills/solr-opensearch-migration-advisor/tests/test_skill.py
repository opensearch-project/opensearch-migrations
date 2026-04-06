"""Tests for skill.py"""
import sys
import os
import json
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))
from skill import SolrToOpenSearchMigrationSkill
from storage import InMemoryStorage


@pytest.fixture
def skill():
    return SolrToOpenSearchMigrationSkill(storage=InMemoryStorage())


SIMPLE_SCHEMA_XML = """<schema name="test" version="1.6">
  <fieldType name="string" class="solr.StrField"/>
  <field name="id" type="string" indexed="true" stored="true"/>
  <field name="title" type="string" indexed="true" stored="true"/>
</schema>"""


# ---------------------------------------------------------------------------
# convert_schema_xml
# ---------------------------------------------------------------------------

def test_convert_schema_xml_returns_json(skill):
    result = skill.convert_schema_xml(SIMPLE_SCHEMA_XML)
    parsed = json.loads(result)
    assert "mappings" in parsed
    assert parsed["mappings"]["properties"]["id"]["type"] == "keyword"


def test_convert_schema_xml_invalid_raises(skill):
    with pytest.raises(ValueError):
        skill.convert_schema_xml("not xml")


# ---------------------------------------------------------------------------
# convert_schema_json
# ---------------------------------------------------------------------------

def test_convert_schema_json_returns_json(skill):
    schema_json = json.dumps({
        "schema": {
            "fieldTypes": [{"name": "string", "class": "solr.StrField"}],
            "fields": [{"name": "title", "type": "string"}],
        }
    })
    result = skill.convert_schema_json(schema_json)
    parsed = json.loads(result)
    assert parsed["mappings"]["properties"]["title"]["type"] == "keyword"


def test_convert_schema_json_invalid_raises(skill):
    with pytest.raises(ValueError):
        skill.convert_schema_json("{bad}")


# ---------------------------------------------------------------------------
# convert_query
# ---------------------------------------------------------------------------

def test_convert_query_match_all(skill):
    assert json.loads(skill.convert_query("*:*")) == {"query": {"match_all": {}}}


def test_convert_query_field_value(skill):
    parsed = json.loads(skill.convert_query("title:opensearch"))
    assert parsed["query"]["match"]["title"] == "opensearch"


def test_convert_query_empty_raises(skill):
    with pytest.raises(ValueError):
        skill.convert_query("")


# ---------------------------------------------------------------------------
# get_migration_checklist
# ---------------------------------------------------------------------------

def test_get_migration_checklist_contains_sections(skill):
    checklist = skill.get_migration_checklist()
    assert "PREPARATION" in checklist
    assert "SCHEMA" in checklist
    assert "QUERY MIGRATION" in checklist
    assert "CUTOVER" in checklist


# ---------------------------------------------------------------------------
# get_field_type_mapping_reference
# ---------------------------------------------------------------------------

def test_get_field_type_mapping_reference_is_markdown_table(skill):
    ref = skill.get_field_type_mapping_reference()
    assert "| Solr Field Type | OpenSearch Type |" in ref
    assert "text" in ref
    assert "keyword" in ref


# ---------------------------------------------------------------------------
# handle_message — routing
# ---------------------------------------------------------------------------

def test_handle_message_schema_conversion(skill):
    response = skill.handle_message(f"Please convert this schema: {SIMPLE_SCHEMA_XML}", "s1")
    assert "mappings" in response or "OpenSearch" in response


def test_handle_message_query_translation(skill):
    response = skill.handle_message("translate query: title:opensearch", "s2")
    assert "match" in response or "OpenSearch" in response


def test_handle_message_checklist(skill):
    response = skill.handle_message("show me the checklist", "s3")
    assert "PREPARATION" in response or "checklist" in response.lower()


def test_handle_message_report(skill):
    response = skill.handle_message("generate report", "s4")
    assert "Migration Report" in response


def test_handle_message_unknown_returns_greeting(skill):
    response = skill.handle_message("hello there", "s5")
    assert len(response) > 0


# ---------------------------------------------------------------------------
# handle_message — session state persistence
# ---------------------------------------------------------------------------

def test_handle_message_persists_history(skill):
    skill.handle_message("hello", "persist-test")
    state = skill._storage.load("persist-test")
    assert state is not None
    assert len(state.history) == 1
    assert state.history[0]["user"] == "hello"


def test_handle_message_schema_sets_fact_and_progress(skill):
    skill.handle_message(f"convert: {SIMPLE_SCHEMA_XML}", "schema-session")
    state = skill._storage.load("schema-session")
    assert state.get_fact("schema_migrated") is True
    assert state.progress >= 1


def test_handle_message_query_advances_progress(skill):
    skill.handle_message("translate query: title:test", "q-session")
    state = skill._storage.load("q-session")
    assert state.progress >= 3


def test_session_resumes_across_calls(skill):
    skill.handle_message("hello", "resume-test")
    skill.handle_message("world", "resume-test")
    state = skill._storage.load("resume-test")
    assert len(state.history) == 2


# ---------------------------------------------------------------------------
# generate_report
# ---------------------------------------------------------------------------

def test_generate_report_no_session(skill):
    report = skill.generate_report("empty-session")
    assert "Migration Report" in report


def test_generate_report_flags_missing_schema(skill):
    report = skill.generate_report("no-schema-session")
    assert "Schema not yet analyzed" in report


def test_generate_report_no_missing_schema_blocker_when_migrated(skill):
    state = skill._storage.load_or_new("migrated-session")
    state.set_fact("schema_migrated", True)
    skill._storage.save(state)
    report = skill.generate_report("migrated-session")
    assert "Schema not yet analyzed" not in report


def test_generate_report_includes_incompatibilities(skill):
    state = skill._storage.load_or_new("inc-session")
    state.add_incompatibility("schema", "Breaking", "copyField unsupported", "Use copy_to")
    state.add_incompatibility("query", "Behavioral", "TF-IDF vs BM25", "Configure similarity")
    skill._storage.save(state)
    report = skill.generate_report("inc-session")
    assert "Breaking" in report
    assert "copyField unsupported" in report
    assert "Behavioral" in report
    assert "TF-IDF vs BM25" in report


def test_generate_report_breaking_incompatibility_appears_as_blocker(skill):
    state = skill._storage.load_or_new("blocker-session")
    state.add_incompatibility("plugin", "Breaking", "Custom plugin X has no equivalent", "Rewrite")
    skill._storage.save(state)
    report = skill.generate_report("blocker-session")
    # Breaking items should appear in both the Incompatibilities section and Blockers
    assert "Custom plugin X has no equivalent" in report
    assert "Potential Blockers" in report


def test_generate_report_includes_customizations(skill):
    state = skill._storage.load_or_new("custom-session")
    state.set_fact("customizations", {"Custom SearchHandler": "Use Search API"})
    skill._storage.save(state)
    report = skill.generate_report("custom-session")
    assert "Custom SearchHandler" in report
    assert "Use Search API" in report


def test_generate_report_no_incompatibilities_message(skill):
    report = skill.generate_report("clean-session")
    assert "No incompatibilities identified." in report


# ---------------------------------------------------------------------------
# generate_report — client integrations
# ---------------------------------------------------------------------------

def test_generate_report_no_client_integrations_message(skill):
    report = skill.generate_report("no-clients-session")
    assert "No client or front-end integrations recorded." in report


def test_generate_report_includes_client_integrations(skill):
    state = skill._storage.load_or_new("client-session")
    state.add_client_integration("SolrJ", "library", "Java client", "Replace with opensearch-java")
    state.add_client_integration("React UI", "ui", "Solr widgets", "Rewrite with OpenSearch JS")
    skill._storage.save(state)
    report = skill.generate_report("client-session")
    assert "SolrJ" in report
    assert "Replace with opensearch-java" in report
    assert "React UI" in report
    assert "Rewrite with OpenSearch JS" in report


def test_generate_report_client_section_present(skill):
    report = skill.generate_report("section-check")
    assert "## Client & Front-end Impact" in report
