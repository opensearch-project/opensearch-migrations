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
