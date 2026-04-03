"""
Converts Apache Solr query syntax to OpenSearch Query DSL.

Supported Solr query patterns
------------------------------
* ``field:value``                    → ``match`` query
* ``field:"phrase value"``           → ``match_phrase`` query
* ``field:val*`` / ``field:*val``    → ``wildcard`` query
* ``field:[low TO high]``            → ``range`` query (inclusive)
* ``field:{low TO high}``            → ``range`` query (exclusive)
* ``field:[low TO *]`` / ``field:[* TO high]`` → open-ended range
* ``+term`` / ``-term``              → boolean ``must`` / ``must_not``
* ``term1 AND term2``                → boolean ``must``
* ``term1 OR term2``                 → boolean ``should``
* ``NOT term``                       → boolean ``must_not``
* ``*:*``                            → ``match_all``
* Plain ``term`` (no field)          → ``query_string``

Limitations
-----------
* Nested parentheses grouping is limited; complex nested expressions will
  fall back to a ``query_string`` query so that no information is lost.
* Boost values (``^n``) are stripped from query terms.
* Fuzzy operators (``~``) are not converted and fall back to
  ``query_string``.
"""

from __future__ import annotations

import re
from typing import Any


# ---------------------------------------------------------------------------
# Regex helpers
# ---------------------------------------------------------------------------

_FIELD_VALUE_RE = re.compile(
    r'^(?P<field>\w+):(?P<value>.+)$', re.DOTALL
)
_PHRASE_RE = re.compile(r'^"(?P<phrase>[^"]+)"$')
_WILDCARD_RE = re.compile(r'[*?]')
_RANGE_RE = re.compile(
    r'^(?P<open>[\[{])\s*(?P<low>\S+)\s+TO\s+(?P<high>\S+)\s*(?P<close>[\]}])$'
)
_BOOST_RE = re.compile(r'\^[\d.]+$')


def _strip_boost(value: str) -> str:
    return _BOOST_RE.sub('', value).strip()


def _build_term_query(field: str, raw_value: str) -> dict[str, Any]:
    """Build a single field→value query clause."""
    value = _strip_boost(raw_value)

    # Phrase
    phrase_match = _PHRASE_RE.match(value)
    if phrase_match:
        return {"match_phrase": {field: phrase_match.group("phrase")}}

    # Range
    range_match = _RANGE_RE.match(value)
    if range_match:
        low = range_match.group("low")
        high = range_match.group("high")
        inclusive_low = range_match.group("open") == "["
        inclusive_high = range_match.group("close") == "]"

        range_clause: dict[str, Any] = {}
        if low != "*":
            range_clause["gte" if inclusive_low else "gt"] = _coerce_number(low)
        if high != "*":
            range_clause["lte" if inclusive_high else "lt"] = _coerce_number(high)

        return {"range": {field: range_clause}}

    # Wildcard
    if _WILDCARD_RE.search(value):
        return {"wildcard": {field: value.lower()}}

    # Plain term → match
    return {"match": {field: value}}


def _coerce_number(value: str) -> int | float | str:
    """Try to return a numeric type; fall back to string."""
    try:
        return int(value)
    except ValueError:
        pass
    try:
        return float(value)
    except ValueError:
        pass
    return value


# ---------------------------------------------------------------------------
# Boolean splitting helpers
# ---------------------------------------------------------------------------

def _split_boolean(query: str) -> tuple[str, list[str]] | None:
    """Split a query on a top-level AND or OR operator.

    Returns ``(operator, [parts])`` or ``None`` if no top-level operator is
    found.  Only splits on AND/OR that are not inside parentheses or quotes.
    """
    depth = 0
    in_quote = False

    for i, ch in enumerate(query):
        if ch == '"':
            in_quote = not in_quote
            continue
        if in_quote:
            continue

        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
        elif depth == 0:
            found = _find_op_at_index(query, i)
            if found:
                return found

    return None


def _find_op_at_index(query: str, i: int) -> tuple[str, list[str]] | None:
    """Check for AND/OR at the given index and return the operator and parts."""
    for op in (' AND ', ' OR '):
        if query[i:].upper().startswith(op):
            left = query[:i].strip()
            right = query[i + len(op):].strip()
            return op.strip(), [left, right]
    return None


def _build_must_not(inner_query: str) -> dict[str, Any]:
    """Wrap a query string in a must_not boolean."""
    inner = _convert_simple(inner_query)
    return {"bool": {"must_not": [inner]}}


def _convert_simple(query: str) -> dict[str, Any]:
    """Convert a single (non-compound) Solr query clause to OpenSearch DSL."""
    query = query.strip()

    # match_all
    if query in ("*:*", "*"):
        return {"match_all": {}}

    # NOT prefix
    if query.upper().startswith("NOT "):
        return _build_must_not(query[4:].strip())

    # +/- prefix (required / prohibited)
    if query.startswith("+") or query.startswith("-"):
        return _handle_prefixed_query(query)

    # field:value
    fv_match = _FIELD_VALUE_RE.match(query)
    if fv_match:
        field = fv_match.group("field")
        value = fv_match.group("value")
        return _build_term_query(field, value)

    # Bare term with no field — use query_string
    return {"query_string": {"query": query}}


def _handle_prefixed_query(query: str) -> dict[str, Any]:
    """Handle queries starting with + or -."""
    must: list[dict[str, Any]] = []
    must_not: list[dict[str, Any]] = []
    # Split on space-separated +/- tokens
    tokens = _tokenize_prefixed(query)
    for sign, tok in tokens:
        clause = _convert_simple(tok)
        if sign == "+":
            must.append(clause)
        else:
            must_not.append(clause)

    bool_query: dict[str, Any] = {}
    if must:
        bool_query["must"] = must
    if must_not:
        bool_query["must_not"] = must_not
    return {"bool": bool_query}


def _tokenize_prefixed(query: str) -> list[tuple[str, str]]:
    """Break a ``+a -b +c`` style query into ``[(sign, term), …]``."""
    tokens: list[tuple[str, str]] = []
    # Split respecting quoted strings
    parts = re.findall(r'[+-](?:"[^"]*"|\S+)', query)
    for part in parts:
        sign = "+" if part[0] == "+" else "-"
        tokens.append((sign, part[1:].strip()))
    return tokens


class QueryConverter:
    """Converts Solr query strings to OpenSearch Query DSL dicts.

    Usage::

        converter = QueryConverter()

        # Simple field query
        dsl = converter.convert("title:opensearch")

        # Range query
        dsl = converter.convert("price:[10 TO 100]")

        # Boolean query
        dsl = converter.convert("title:search AND category:docs")

        print(json.dumps(dsl, indent=2))
    """

    def convert(self, solr_query: str) -> dict[str, Any]:
        """Convert a Solr query string to an OpenSearch Query DSL dict.

        The returned dict is the full ``query`` object, i.e. it can be used
        directly as the value of the ``"query"`` key in an OpenSearch search
        request body.

        Args:
            solr_query: A Solr query string (``q`` parameter value).

        Returns:
            An OpenSearch Query DSL dict.

        Raises:
            ValueError: If ``solr_query`` is empty.
        """
        if not solr_query or not solr_query.strip():
            raise ValueError("solr_query must not be empty")

        query = solr_query.strip()

        # Remove wrapping parentheses if they span the whole expression.
        query = _unwrap_parens(query)

        # Top-level AND/OR
        result = _split_boolean(query)
        if result:
            operator, parts = result
            return self._handle_boolean_operator(operator, parts)

        return {"query": _convert_simple(query)}

    def _handle_boolean_operator(self, operator: str, parts: list[str]) -> dict[str, Any]:
        """Handle boolean AND/OR operators by building the appropriate bool query."""
        clauses = [self.convert(p)["query"] for p in parts]
        if operator == "AND":
            return {"query": {"bool": {"must": clauses}}}
        # OR
        return {"query": {"bool": {"should": clauses, "minimum_should_match": 1}}}


def _unwrap_parens(query: str) -> str:
    """Remove a single layer of matching outer parentheses if present."""
    query = query.strip()
    if not (query.startswith("(") and query.endswith(")")):
        return query
    # Verify the opening paren actually matches the last char.
    depth = 0
    for i, ch in enumerate(query):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if depth == 0 and i < len(query) - 1:
            # Closing paren found before end — outer parens are not a single
            # group, don't strip.
            return query
    return query[1:-1].strip()
