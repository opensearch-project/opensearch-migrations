"""Tests for query_converter.py"""
import sys
import os
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))
from query_converter import QueryConverter, _unwrap_parens, _split_boolean


@pytest.fixture
def converter():
    return QueryConverter()


def test_match_all(converter):
    result = converter.convert("*:*")
    assert result == {"query": {"match_all": {}}}


def test_match_all_bare_star(converter):
    result = converter.convert("*")
    assert result == {"query": {"match_all": {}}}


def test_field_value_match(converter):
    result = converter.convert("title:opensearch")
    assert result == {"query": {"match": {"title": "opensearch"}}}


def test_phrase_query(converter):
    result = converter.convert('title:"hello world"')
    assert result == {"query": {"match_phrase": {"title": "hello world"}}}


def test_wildcard_query(converter):
    result = converter.convert("title:open*")
    assert result["query"]["wildcard"]["title"] == "open*"


def test_range_inclusive(converter):
    result = converter.convert("price:[10 TO 100]")
    assert result == {"query": {"range": {"price": {"gte": 10, "lte": 100}}}}


def test_range_exclusive(converter):
    result = converter.convert("price:{10 TO 100}")
    assert result == {"query": {"range": {"price": {"gt": 10, "lt": 100}}}}


def test_range_open_high(converter):
    result = converter.convert("year:[2020 TO *]")
    assert result == {"query": {"range": {"year": {"gte": 2020}}}}


def test_range_open_low(converter):
    result = converter.convert("year:[* TO 2024]")
    assert result == {"query": {"range": {"year": {"lte": 2024}}}}


def test_boolean_and(converter):
    result = converter.convert("title:search AND category:docs")
    assert result["query"]["bool"]["must"] == [
        {"match": {"title": "search"}},
        {"match": {"category": "docs"}},
    ]


def test_boolean_or(converter):
    result = converter.convert("title:search OR title:find")
    should = result["query"]["bool"]["should"]
    assert {"match": {"title": "search"}} in should
    assert {"match": {"title": "find"}} in should
    assert result["query"]["bool"]["minimum_should_match"] == 1


def test_not_prefix(converter):
    result = converter.convert("NOT status:deleted")
    assert result == {"query": {"bool": {"must_not": [{"match": {"status": "deleted"}}]}}}


def test_required_prohibited_prefix(converter):
    result = converter.convert("+title:search -status:deleted")
    bool_q = result["query"]["bool"]
    assert {"match": {"title": "search"}} in bool_q["must"]
    assert {"match": {"status": "deleted"}} in bool_q["must_not"]


def test_bare_term_falls_back_to_query_string(converter):
    result = converter.convert("opensearch")
    assert result == {"query": {"query_string": {"query": "opensearch"}}}


def test_boost_stripped(converter):
    result = converter.convert("title:search^2")
    assert result == {"query": {"match": {"title": "search"}}}


def test_empty_query_raises(converter):
    with pytest.raises(ValueError):
        converter.convert("")


def test_whitespace_only_raises(converter):
    with pytest.raises(ValueError):
        converter.convert("   ")


def test_unwrap_parens_simple():
    assert _unwrap_parens("(hello)") == "hello"


def test_unwrap_parens_no_outer():
    assert _unwrap_parens("(a) OR (b)") == "(a) OR (b)"


def test_split_boolean_and():
    op, parts = _split_boolean("a:1 AND b:2")
    assert op == "AND"
    assert parts == ["a:1", "b:2"]


def test_split_boolean_or():
    op, parts = _split_boolean("a:1 OR b:2")
    assert op == "OR"
    assert parts == ["a:1", "b:2"]


def test_split_boolean_none():
    assert _split_boolean("a:1") is None


# ---------------------------------------------------------------------------
# convert_edismax tests
# ---------------------------------------------------------------------------

def test_edismax_empty_query_raises(converter):
    with pytest.raises(ValueError):
        converter.convert_edismax("")


def test_edismax_whitespace_query_raises(converter):
    with pytest.raises(ValueError):
        converter.convert_edismax("   ")


def test_edismax_q_only_no_qf(converter):
    # Without qf, falls back to standard query conversion
    result = converter.convert_edismax("opensearch")
    assert result == {"query": {"query_string": {"query": "opensearch"}}}


def test_edismax_qf_single_field(converter):
    result = converter.convert_edismax("opensearch", qf="title")
    mm = result["query"]["multi_match"]
    assert mm["query"] == "opensearch"
    assert mm["fields"] == ["title"]
    assert mm["type"] == "best_fields"


def test_edismax_qf_multiple_fields_with_boosts(converter):
    result = converter.convert_edismax("search", qf="title^2 body^0.5 description")
    mm = result["query"]["multi_match"]
    assert mm["fields"] == ["title^2", "body^0.5", "description"]


def test_edismax_tie(converter):
    result = converter.convert_edismax("search", qf="title body", tie=0.3)
    assert result["query"]["multi_match"]["tie_breaker"] == 0.3


def test_edismax_qs_slop(converter):
    result = converter.convert_edismax("search", qf="title body", qs=2)
    assert result["query"]["multi_match"]["slop"] == 2


def test_edismax_mm_with_qf(converter):
    result = converter.convert_edismax("search", qf="title body", mm="75%")
    bool_q = result["query"]["bool"]
    assert bool_q["minimum_should_match"] == "75%"
    assert any("multi_match" in c for c in bool_q["must"])


def test_edismax_mm_without_qf_wraps_in_bool(converter):
    result = converter.convert_edismax("title:search", mm="2")
    bool_q = result["query"]["bool"]
    assert bool_q["minimum_should_match"] == "2"
    assert {"match": {"title": "search"}} in bool_q["must"]


def test_edismax_pf_adds_phrase_should(converter):
    result = converter.convert_edismax("hello world", qf="title body", pf="title^1.5")
    bool_q = result["query"]["bool"]
    phrase_clause = bool_q["should"][0]["multi_match"]
    assert phrase_clause["type"] == "phrase"
    assert phrase_clause["query"] == "hello world"
    assert "title^1.5" in phrase_clause["fields"]


def test_edismax_pf2_adds_phrase_should(converter):
    result = converter.convert_edismax("hello world", qf="title", pf2="title body")
    should = result["query"]["bool"]["should"]
    assert any(c["multi_match"]["type"] == "phrase" for c in should)


def test_edismax_pf3_adds_phrase_should(converter):
    result = converter.convert_edismax("hello world", qf="title", pf3="title")
    should = result["query"]["bool"]["should"]
    assert any(c["multi_match"]["type"] == "phrase" for c in should)


def test_edismax_ps_slop_on_phrase_clauses(converter):
    result = converter.convert_edismax("hello world", qf="title", pf="title body", ps=3)
    should = result["query"]["bool"]["should"]
    phrase_clauses = [c for c in should if c["multi_match"]["type"] == "phrase"]
    assert all(c["multi_match"]["slop"] == 3 for c in phrase_clauses)


def test_edismax_pf_pf2_pf3_all_present(converter):
    result = converter.convert_edismax(
        "hello world", qf="title", pf="title", pf2="body", pf3="description"
    )
    should = result["query"]["bool"]["should"]
    # One phrase clause per pf/pf2/pf3
    phrase_clauses = [c for c in should if c["multi_match"]["type"] == "phrase"]
    assert len(phrase_clauses) == 3


def test_edismax_bq_string(converter):
    result = converter.convert_edismax("search", qf="title", bq="category:docs")
    should = result["query"]["bool"]["should"]
    assert {"match": {"category": "docs"}} in should


def test_edismax_bq_list(converter):
    result = converter.convert_edismax(
        "search", qf="title", bq=["category:docs", "status:published"]
    )
    should = result["query"]["bool"]["should"]
    assert {"match": {"category": "docs"}} in should
    assert {"match": {"status": "published"}} in should


def test_edismax_bf_wraps_in_script_score(converter):
    result = converter.convert_edismax("search", qf="title", bf="log(popularity)")
    ss = result["query"]["script_score"]
    assert ss["script"]["source"] == "log(popularity)"
    # Inner query should still be the assembled bool/multi_match
    assert "multi_match" in ss["query"] or "bool" in ss["query"]


def test_edismax_bf_with_pf_wraps_bool_in_script_score(converter):
    result = converter.convert_edismax(
        "search", qf="title", pf="title", bf="doc['rank'].value"
    )
    ss = result["query"]["script_score"]
    assert ss["script"]["source"] == "doc['rank'].value"
    assert "bool" in ss["query"]


def test_edismax_combined_params(converter):
    # Smoke test: all major params together
    result = converter.convert_edismax(
        "hello world",
        qf="title^2 body",
        mm="1",
        pf="title^1.5",
        ps=2,
        tie=0.1,
        bq="featured:true",
    )
    bool_q = result["query"]["bool"]
    assert bool_q["minimum_should_match"] == "1"
    main = bool_q["must"][0]["multi_match"]
    assert main["tie_breaker"] == 0.1
    assert main["fields"] == ["title^2", "body"]
    should = bool_q["should"]
    phrase_clauses = [c for c in should if "multi_match" in c and c["multi_match"]["type"] == "phrase"]
    assert len(phrase_clauses) == 1
    assert phrase_clauses[0]["multi_match"]["slop"] == 2
    assert {"match": {"featured": "true"}} in should
