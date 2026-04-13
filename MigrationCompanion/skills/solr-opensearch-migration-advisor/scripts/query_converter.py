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

eDisMax parameters (via ``convert_edismax``)
--------------------------------------------
* ``q``   — query text; translated using standard query conversion
* ``qf``  — query fields with optional per-field boosts (``field^boost``)
             → ``multi_match`` across those fields
* ``mm``  — minimum_should_match passed through to the bool query
* ``pf``  — phrase boost fields → ``should`` ``multi_match`` with type ``phrase``
* ``pf2`` — bigram phrase boost fields → ``should`` ``multi_match`` with type ``phrase``
* ``pf3`` — trigram phrase boost fields → ``should`` ``multi_match`` with type ``phrase``
* ``ps``  — phrase slop applied to ``pf`` phrase clauses
* ``qs``  — query slop applied to ``qf`` match clauses
* ``tie`` — tiebreaker for ``multi_match`` cross-field scoring
* ``bq``  — additive boost query → extra ``should`` clause (Behavioral: additive vs multiplicative)
* ``bf``  — boost function expression → ``script_score`` wrapper (Behavioral: approximation only)

Limitations
-----------
* Nested parentheses grouping is limited; complex nested expressions will
  fall back to a ``query_string`` query so that no information is lost.
* Boost values (``^n``) are stripped from query terms.
* Fuzzy operators (``~``) are not converted and fall back to
  ``query_string``.
* ``bf`` (boost function) is approximated as a Painless ``script_score``
  wrapping the main query; complex Solr function expressions may require
  manual adjustment.
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

    def convert_edismax(
        self,
        q: str,
        *,
        qf: str | None = None,
        mm: str | None = None,
        pf: str | None = None,
        pf2: str | None = None,
        pf3: str | None = None,
        ps: int | None = None,
        qs: int | None = None,
        tie: float | None = None,
        bq: str | list[str] | None = None,
        bf: str | None = None,
    ) -> dict[str, Any]:
        """Convert an eDisMax query to OpenSearch Query DSL.

        Args:
            q:   The main query text (Solr ``q`` parameter).
            qf:  Query fields with optional boosts, e.g. ``"title^2 body^0.5"``.
                 When provided, a ``multi_match`` query is used instead of the
                 standard query conversion.
            mm:  Minimum should match, passed through verbatim to the bool query
                 (e.g. ``"75%"`` or ``"2"``).
            pf:  Phrase boost fields.  Translated to ``should`` ``multi_match``
                 clauses with ``type: phrase``.
            pf2: Bigram phrase boost fields (same translation as ``pf``).
            pf3: Trigram phrase boost fields (same translation as ``pf``).
            ps:  Phrase slop applied to ``pf`` phrase clauses.
            qs:  Query slop applied to the ``qf`` ``multi_match`` clause.
            tie: Tiebreaker for ``multi_match`` cross-field scoring.
            bq:  Additive boost query (or list of queries).  Each is translated
                 and added as a ``should`` clause.  Note: Solr ``bq`` is additive
                 while OpenSearch ``should`` is multiplicative — this is a
                 Behavioral difference.
            bf:  Boost function expression.  Wrapped in a ``script_score`` query
                 as a Painless script approximation.  Complex Solr function
                 expressions will require manual adjustment.

        Returns:
            An OpenSearch Query DSL dict (the full ``{"query": …}`` envelope).

        Raises:
            ValueError: If ``q`` is empty.
        """
        if not q or not q.strip():
            raise ValueError("q must not be empty")

        query_text = q.strip()

        # --- Main query clause ---
        main_clause = _build_edismax_main_clause(
            query_text, qf=qf, tie=tie, qs=qs,
            fallback=self.convert(query_text)["query"] if not qf else None,
        )

        # --- Phrase boost clauses (pf / pf2 / pf3) ---
        should_clauses: list[dict[str, Any]] = []
        for phrase_field_str in filter(None, [pf, pf2, pf3]):
            should_clauses.extend(
                _build_phrase_should_clauses(phrase_field_str, query_text, ps)
            )

        # --- Boost queries (bq) ---
        if isinstance(bq, str):
            bq_list: list[str] = [bq]
        elif bq:
            bq_list = list(bq)
        else:
            bq_list = []
        for bq_item in bq_list:
            should_clauses.append(self.convert(bq_item.strip())["query"])

        # --- Assemble bool query ---
        assembled = _assemble_edismax_bool(main_clause, should_clauses, mm)

        # --- Boost function (bf) wraps everything in script_score ---
        if bf:
            assembled = {
                "script_score": {
                    "query": assembled,
                    "script": {"source": bf},
                }
            }

        return {"query": assembled}


def _build_edismax_main_clause(
    query_text: str,
    *,
    qf: str | None,
    tie: float | None,
    qs: int | None,
    fallback: dict[str, Any] | None,
) -> dict[str, Any]:
    """Build the main query clause for an eDisMax query."""
    if not qf:
        return fallback  # type: ignore[return-value]
    clause: dict[str, Any] = {
        "multi_match": {
            "query": query_text,
            "fields": _parse_qf(qf),
            "type": "best_fields",
        }
    }
    if tie is not None:
        clause["multi_match"]["tie_breaker"] = tie
    if qs is not None:
        clause["multi_match"]["slop"] = qs
    return clause


def _assemble_edismax_bool(
    main_clause: dict[str, Any],
    should_clauses: list[dict[str, Any]],
    mm: str | None,
) -> dict[str, Any]:
    """Assemble the final bool query from main + should clauses, applying mm."""
    if should_clauses:
        bool_query: dict[str, Any] = {
            "bool": {"must": [main_clause], "should": should_clauses}
        }
        if mm is not None:
            bool_query["bool"]["minimum_should_match"] = mm
        return bool_query

    # No should clauses — apply mm directly if possible
    if mm is not None:
        if isinstance(main_clause, dict) and "bool" in main_clause:
            main_clause["bool"]["minimum_should_match"] = mm
            return main_clause
        return {"bool": {"must": [main_clause], "minimum_should_match": mm}}

    return main_clause


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


# ---------------------------------------------------------------------------
# eDisMax helpers
# ---------------------------------------------------------------------------

_QF_FIELD_RE = re.compile(r'^(?P<field>\w+)(?:\^(?P<boost>[\d.]+))?$')


def _parse_qf(qf: str) -> list[str]:
    """Parse a Solr ``qf`` string into a list of ``field^boost`` strings
    suitable for OpenSearch ``multi_match`` ``fields``.

    Examples::

        "title^2 body^0.5" → ["title^2", "body^0.5"]
        "title body"       → ["title", "body"]
    """
    fields: list[str] = []
    for token in qf.split():
        m = _QF_FIELD_RE.match(token)
        if m:
            field = m.group("field")
            boost = m.group("boost")
            fields.append(f"{field}^{boost}" if boost else field)
    return fields


def _build_phrase_should_clauses(
    pf: str, query_text: str, slop: int | None
) -> list[dict[str, Any]]:
    """Build ``should`` ``multi_match`` phrase clauses from a ``pf``/``pf2``/``pf3`` string."""
    fields = _parse_qf(pf)
    if not fields:
        return []
    clause: dict[str, Any] = {
        "multi_match": {
            "query": query_text,
            "type": "phrase",
            "fields": fields,
        }
    }
    if slop is not None:
        clause["multi_match"]["slop"] = slop
    return [clause]
