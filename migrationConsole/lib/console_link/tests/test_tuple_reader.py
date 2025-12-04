import json
import pathlib
import re

import pytest

from console_link.models.tuple_reader import (DictionaryPathException, get_element_with_regex, get_element,
                                              set_element, Flag, get_flags_for_component,
                                              parse_tuple)


TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"
VALID_TUPLE = TEST_DATA_DIRECTORY / "valid_tuple.json"
VALID_TUPLE_PARSED = TEST_DATA_DIRECTORY / "valid_tuple_parsed.json"
VALID_TUPLE_GZIPPED_CHUNKED = TEST_DATA_DIRECTORY / "valid_tuple_gzipped_and_chunked.json"
VALID_TUPLE_GZIPPED_CHUNKED_PARSED = TEST_DATA_DIRECTORY / "valid_tuple_gzipped_and_chunked_parsed.json"
INVALID_TUPLE = TEST_DATA_DIRECTORY / "invalid_tuple.json"
VALID_TUPLE_MISSING_COMPONENT = TEST_DATA_DIRECTORY / "valid_tuple_missing_component.json"


def test_get_element_with_regex_succeeds():
    d = {
        'A1': 'value',
        '2B': 'not value'
    }
    regex = re.compile(r"\w\d")
    assert get_element_with_regex(regex, d) == "value"


def test_get_element_with_regex_fails_no_raise():
    d = {
        'AA1': 'value',
        '2B': 'not value'
    }
    regex = re.compile(r"\w\d")
    assert get_element_with_regex(regex, d) is None


def test_get_element_with_regex_fails_with_raise():
    d = {
        'AA1': 'value',
        '2B': 'not value'
    }
    regex = re.compile(r"\w\d")
    with pytest.raises(DictionaryPathException):
        get_element_with_regex(regex, d, raise_on_error=True)


def test_get_element_succeeds():
    d = {
        'A': {
            'B': 'value',
            'C': 'not value'
        },
        'B': 'not value'
    }
    assert get_element('A.B', d) == 'value'


def test_get_element_fails_no_raise():
    d = {
        'A': {
            'B': 'value',
            'C': 'not value'
        },
        'B': 'not value'
    }
    assert get_element('B.A', d) is None


def test_get_element_fails_raises():
    d = {
        'A': {
            'B': 'value',
            'C': 'not value'
        },
        'B': 'not value'
    }
    with pytest.raises(DictionaryPathException):
        get_element('B.A', d, raise_on_error=True)


def test_set_element_succeeds():
    d = {
        'A': {
            'B': 'value',
            'C': 'not value'
        },
        'B': 'not value'
    }
    set_element('A.B', d, 'new value')

    assert d['A']['B'] == 'new value'


def test_set_element_fails_and_raises():
    d = {
        'A': {
            'B': 'value',
            'C': 'not value'
        },
        'B': 'not value'
    }
    with pytest.raises(DictionaryPathException):
        set_element('B.A', d, 'new value')


def test_get_flags_none():
    request = {
        'Content-Encoding': 'not-gzip',
        'Content-Type': 'not-json',
        'Transfer-Encoding': 'not-chunked',
        'body': 'abcdefg'
    }
    flags = get_flags_for_component(request, False)
    assert flags == set()


def test_get_flags_for_component_only_bulk():
    request = {
        'Content-Type': 'not-json',
        'body': 'abcdefg'
    }
    flags = get_flags_for_component(request, True)
    assert flags == {Flag.Bulk_Request}


def test_get_flags_for_component_all_present():
    request = {
        'Content-Type': 'application/json',
        'body': 'abcdefg'
    }
    flags = get_flags_for_component(request, True)
    assert flags == {Flag.Bulk_Request, Flag.Json}


def test_get_flags_all_present_alternate_capitalization():
    request = {
        'CONTENT-TYPE': 'application/json',
        'body': 'abcdefg'
    }
    flags = get_flags_for_component(request, True)
    assert flags == {Flag.Bulk_Request, Flag.Json}


def test_parse_tuple_full_example():
    with open(VALID_TUPLE, 'r') as f:
        tuple_ = f.read()
    parsed = parse_tuple(tuple_, 0)

    with open(VALID_TUPLE_PARSED, 'r') as f:
        expected = json.load(f)

    assert parsed == expected


def test_parse_tuple_with_malformed_bodies(caplog):
    with open(INVALID_TUPLE, 'r') as f:
        tuple_ = f.read()

    parsed = parse_tuple(tuple_, 0)
    assert json.loads(tuple_) == parsed  # Values weren't changed if they couldn't be interpreted
    assert "Body value of sourceResponse on line 0 could not be decoded to utf-8" in caplog.text
    assert "Body value of targetResponses item 0 on line 0 should be a json, but could not be parsed" in caplog.text


def test_parse_tuple_with_missing_component():
    with open(VALID_TUPLE_MISSING_COMPONENT, 'r') as f:
        tuple_ = f.read()

    assert 'sourceResponse' not in json.loads(tuple_)
    parsed = parse_tuple(tuple_, 0)

    assert 'sourceResponse' not in parsed
    assert json.loads(tuple_).keys() == parsed.keys()
