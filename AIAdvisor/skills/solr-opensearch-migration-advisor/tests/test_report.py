"""Tests for report.py"""
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))
from report import MigrationReport
from storage import Incompatibility, ClientIntegration


def test_generate_contains_header():
    assert "# Solr to OpenSearch Migration Report" in MigrationReport().generate()


def test_generate_milestones():
    output = MigrationReport(milestones=["Step A", "Step B"]).generate()
    assert "1. Step A" in output
    assert "2. Step B" in output


def test_generate_blockers():
    output = MigrationReport(blockers=["Blocker 1", "Blocker 2"]).generate()
    assert "- Blocker 1" in output
    assert "- Blocker 2" in output


def test_generate_no_blockers_default_message():
    assert "No immediate blockers identified." in MigrationReport().generate()


def test_generate_implementation_points():
    output = MigrationReport(implementation_points=["Do X", "Do Y"]).generate()
    assert "- Do X" in output
    assert "- Do Y" in output


def test_generate_cost_estimates():
    output = MigrationReport(cost_estimates={"Infrastructure": "$500/mo"}).generate()
    assert "Infrastructure" in output
    assert "$500/mo" in output


def test_generate_no_cost_estimates_default_message():
    assert "TBD" in MigrationReport().generate()


def test_generate_all_sections_present():
    output = MigrationReport().generate()
    assert "## Incompatibilities" in output
    assert "## Client & Front-end Impact" in output
    assert "## Major Milestones" in output
    assert "## Potential Blockers" in output
    assert "## Implementation Points" in output
    assert "## Cost Estimates" in output


def test_generate_returns_string():
    assert isinstance(MigrationReport(milestones=["m1"], blockers=["b1"]).generate(), str)


# ---------------------------------------------------------------------------
# Incompatibilities section
# ---------------------------------------------------------------------------

def test_incompatibilities_section_no_items():
    output = MigrationReport().generate()
    assert "No incompatibilities identified." in output


def test_incompatibilities_breaking_appears_first():
    incs = [
        Incompatibility("query", "Behavioral", "BM25 vs TF-IDF", "Configure similarity"),
        Incompatibility("schema", "Breaking", "copyField unsupported", "Use copy_to"),
    ]
    output = MigrationReport(incompatibilities=incs).generate()
    breaking_pos = output.index("Breaking")
    behavioral_pos = output.index("Behavioral")
    assert breaking_pos < behavioral_pos


def test_incompatibilities_unsupported_before_behavioral():
    incs = [
        Incompatibility("query", "Behavioral", "desc b", "rec b"),
        Incompatibility("plugin", "Unsupported", "desc u", "rec u"),
    ]
    output = MigrationReport(incompatibilities=incs).generate()
    assert output.index("Unsupported") < output.index("Behavioral")


def test_incompatibilities_description_and_recommendation_present():
    incs = [Incompatibility("schema", "Breaking", "My description", "My recommendation")]
    output = MigrationReport(incompatibilities=incs).generate()
    assert "My description" in output
    assert "My recommendation" in output


def test_incompatibilities_action_required_for_breaking():
    incs = [Incompatibility("schema", "Breaking", "desc", "rec")]
    output = MigrationReport(incompatibilities=incs).generate()
    assert "Action required" in output


def test_incompatibilities_action_required_for_unsupported():
    incs = [Incompatibility("plugin", "Unsupported", "desc", "rec")]
    output = MigrationReport(incompatibilities=incs).generate()
    assert "Action required" in output


def test_incompatibilities_no_action_required_for_behavioral_only():
    incs = [Incompatibility("query", "Behavioral", "desc", "rec")]
    output = MigrationReport(incompatibilities=incs).generate()
    assert "Action required" not in output


def test_incompatibilities_category_shown():
    incs = [Incompatibility("mycat", "Behavioral", "desc", "rec")]
    output = MigrationReport(incompatibilities=incs).generate()
    assert "mycat" in output


# ---------------------------------------------------------------------------
# Client & Front-end Impact section
# ---------------------------------------------------------------------------

def test_client_section_no_integrations():
    output = MigrationReport().generate()
    assert "No client or front-end integrations recorded." in output


def test_client_section_library_shown():
    clients = [ClientIntegration("SolrJ", "library", "Java search client", "Replace with opensearch-java")]
    output = MigrationReport(client_integrations=clients).generate()
    assert "Client Libraries" in output
    assert "SolrJ" in output
    assert "Replace with opensearch-java" in output


def test_client_section_ui_shown():
    clients = [ClientIntegration("React Search UI", "ui", "Solr widgets", "Rewrite with OpenSearch JS client")]
    output = MigrationReport(client_integrations=clients).generate()
    assert "Front-end / UI" in output
    assert "React Search UI" in output


def test_client_section_http_shown():
    clients = [ClientIntegration("Custom HTTP", "http", "Raw requests to /select", "Update endpoint to /_search")]
    output = MigrationReport(client_integrations=clients).generate()
    assert "HTTP / Custom Clients" in output
    assert "Custom HTTP" in output


def test_client_section_notes_shown():
    clients = [ClientIntegration("pysolr", "library", "Python search client", "Replace with opensearch-py")]
    output = MigrationReport(client_integrations=clients).generate()
    assert "Python search client" in output


def test_client_section_migration_action_shown():
    clients = [ClientIntegration("pysolr", "library", "", "Replace with opensearch-py")]
    output = MigrationReport(client_integrations=clients).generate()
    assert "Replace with opensearch-py" in output


def test_client_section_multiple_kinds():
    clients = [
        ClientIntegration("SolrJ", "library", "", "Replace with opensearch-java"),
        ClientIntegration("Velocity UI", "ui", "", "Rewrite with OpenSearch Dashboards"),
    ]
    output = MigrationReport(client_integrations=clients).generate()
    assert "Client Libraries" in output
    assert "Front-end / UI" in output
    assert "SolrJ" in output
    assert "Velocity UI" in output


def test_client_section_ordering_library_before_ui():
    clients = [
        ClientIntegration("My UI", "ui", "", "action"),
        ClientIntegration("My Lib", "library", "", "action"),
    ]
    output = MigrationReport(client_integrations=clients).generate()
    assert output.index("Client Libraries") < output.index("Front-end / UI")
