"""Replay-quality scoring for TrafficReplayer tuples.

Given a source/target request-response tuple, estimate how similar the two sides' results
were. Every replayed request decomposes into "items" — independently-scored comparison
units — rather than one blended score, since averaging hides exactly the failure that
matters: a badly-diverged item pulled toward 1.0 by good ones still looks fine in aggregate.
An _msearch contributes one group of items per NDJSON sub-query; within any single response
(msearch sub-response or a plain search), each of hits and every named aggregation is its
own item, since a size:0 facet query with two aggregations is really two independent
comparisons, not one.
"""
from typing import Dict, List, Optional, Set, Tuple


def jaccard_sets(a: Set, b: Set) -> Optional[float]:
    if not a and not b:
        return None
    return len(a & b) / len(a | b)


def hit_doc_ids(body: Dict) -> Optional[Set[str]]:
    hits = body.get('hits', {}).get('hits', [])
    ids = {h['_id'] for h in hits if '_id' in h}
    return ids if ids else None


def hit_summaries(body: Dict, limit: int = 30) -> List[Dict]:
    """Ordered (id, score) pairs for a search response's hits, capped so a TUI detail pane
    stays a fixed size regardless of how many hits a query happened to return."""
    hits = body.get('hits', {}).get('hits', [])
    return [{'id': h['_id'], 'score': h.get('_score')} for h in hits[:limit] if '_id' in h]


def hit_count(body: Dict) -> Optional[int]:
    """hits.total.value when present — 0 is a real, valid count (a search that legitimately
    matched nothing) and must be distinguished from "not a search response at all", so this
    checks for the key rather than truthiness. Falls back to a plain count for non-search
    responses like _count."""
    total = body.get('hits', {}).get('total', {})
    if isinstance(total, dict) and 'value' in total:
        return total['value']
    return body.get('count')


def compute_hits_jaccard(src_body: Dict, tgt_body: Dict) -> Tuple[Optional[float], Optional[str]]:
    """Doc-ID overlap for a response's hits, falling back to a hit-count ratio when the hits
    array is empty on both sides (a legitimate "both matched nothing" case, not "no data" —
    see hit_count's docstring)."""
    si = hit_doc_ids(src_body)
    ti = hit_doc_ids(tgt_body)
    if si is not None and ti is not None:
        return jaccard_sets(si, ti), 'doc IDs'
    sv = hit_count(src_body)
    tv = hit_count(tgt_body)
    if sv is not None and tv is not None:
        mx = max(sv, tv)
        return ((min(sv, tv) / mx) if mx > 0 else 1.0), 'hit count ratio'
    return None, None


# Persistence: how much weight RBO gives to agreement at deeper ranks versus the very top.
# 0.9 puts ~65% of the total weight on the first 10 ranks — a reasonable default for the
# top-20-ish result lists this tool typically sees; a longer results page would want it
# pushed closer to 1.0 to avoid over-weighting the first few hits.
RBO_PERSISTENCE = 0.9


def rbo_score(list_a: List[str], list_b: List[str], p: float = RBO_PERSISTENCE) -> Optional[float]:
    """Rank-Biased Overlap (Webber, Moffat & Zobel, 2010) between two ranked ID lists.

    Unlike Jaccard, this is order-sensitive: two lists with identical membership but reversed
    order score 1.0 under Jaccard but well under 1.0 here, since RBO weights agreement at
    shallow depths (rank 1, 2, 3...) more heavily than agreement deep in the list. This is the
    finite-depth base form (evaluated to depth = min(len(a), len(b)), since that's the deepest
    rank both lists can be compared at), normalized so two identical lists score exactly 1.0 —
    the raw un-normalized sum only approaches 1.0 as depth goes to infinity, which would read
    as "diverged" for two short but perfectly-matching lists, the opposite of every other score
    in this tool.
    """
    k = min(len(list_a), len(list_b))
    if k == 0:
        return None
    weighted_sum = 0.0
    weight_total = 0.0
    for d in range(1, k + 1):
        agreement_d = len(set(list_a[:d]) & set(list_b[:d])) / d
        weight = (1 - p) * (p ** (d - 1))
        weighted_sum += weight * agreement_d
        weight_total += weight
    return (weighted_sum / weight_total) if weight_total > 0 else None


def _score_agg_buckets(sb: List[Dict], tb: List[Dict]) -> Optional[Tuple[Optional[float], str]]:
    """Weighted per-key overlap for a bucket-based aggregation (terms/date-histogram/...),
    weighted by doc_count so a bucket with 10000 docs counts for more than one with 1. Returns
    None (not a result) when neither side has any buckets, so the caller falls through to the
    next scoring mode."""
    if not sb and not tb:
        return None
    sc = {b['key']: b.get('doc_count', 0) for b in sb if 'key' in b}
    tc = {b['key']: b.get('doc_count', 0) for b in tb if 'key' in b}
    keys = set(sc) | set(tc)
    if not keys:
        return None, 'agg (buckets)'
    n = sum(min(sc.get(k, 0), tc.get(k, 0)) for k in keys)
    d = sum(max(sc.get(k, 0), tc.get(k, 0)) for k in keys)
    return ((n / d) if d > 0 else 1.0), 'agg (buckets)'


def _score_agg_doc_count(sa: Dict, ta: Dict) -> Optional[Tuple[Optional[float], str]]:
    """Ratio-based score for a single-value doc_count aggregation. Returns None when either
    side lacks a doc_count, so the caller falls through to the next scoring mode."""
    sdc, tdc = sa.get('doc_count'), ta.get('doc_count')
    if sdc is None or tdc is None:
        return None
    mx = max(sdc, tdc)
    return ((min(sdc, tdc) / mx) if mx > 0 else 1.0), 'agg (doc_count)'


def _score_agg_metric(sa: Dict, ta: Dict) -> Optional[Tuple[Optional[float], str]]:
    """Ratio-based score for a single-metric aggregation (avg/sum/cardinality/...). Returns
    None when neither side even has a 'value' key, or when either side's value is missing."""
    if 'value' not in sa and 'value' not in ta:
        return None
    sv, tv = sa.get('value'), ta.get('value')
    if sv is None or tv is None:
        return None
    mx = max(sv, tv)
    return ((min(sv, tv) / mx) if mx > 0 else 1.0), 'agg (metric)'


def score_agg(sa: Dict, ta: Dict) -> Tuple[Optional[float], str]:
    """Score ONE named aggregation — bucket-based (terms/date-histogram/...) or a single
    metric (avg/sum/cardinality/...). Tries each scoring mode in turn and uses whichever one
    actually applies to this aggregation's shape."""
    sa = sa if isinstance(sa, dict) else {}
    ta = ta if isinstance(ta, dict) else {}
    sb = sa.get('buckets', [])
    tb = ta.get('buckets', [])
    for result in (_score_agg_buckets(sb, tb), _score_agg_doc_count(sa, ta), _score_agg_metric(sa, ta)):
        if result is not None:
            return result
    return None, 'agg'


def _score_hits_item(sb: Dict, tb: Dict, missing_side: Optional[str], label_prefix: str) -> Dict:
    """Build the single 'hits' item for one response pair — only called when either side has
    a hits section."""
    j, lbl = (None, missing_side) if missing_side else compute_hits_jaccard(sb, tb)
    src_hit_list = hit_summaries(sb)
    tgt_hit_list = hit_summaries(tb)
    if missing_side:
        rbo_j, rbo_lbl = None, missing_side
    else:
        rbo_j = rbo_score([h['id'] for h in src_hit_list], [h['id'] for h in tgt_hit_list])
        # rbo_j is None here specifically when there's no ranked list to compare at all —
        # a size:0 query still has a 'hits' section (so it's still a "hits" item, and
        # Jaccard's hit-count fallback still applies), but an empty hits.hits array means
        # RBO has nothing to measure. Distinct from missing_side: the response is real and
        # complete, RBO just doesn't apply to it.
        rbo_lbl = f'RBO (p={RBO_PERSISTENCE})' if rbo_j is not None else 'RBO (no ranked hits)'
    return {
        'label': f'{label_prefix} · hits'.strip(' ·'), 'kind': 'hits', 'j': j, 'j_label': lbl,
        'rbo_j': rbo_j, 'rbo_label': rbo_lbl,
        'src_hits': hit_count(sb), 'tgt_hits': hit_count(tb),
        'src_hit_list': src_hit_list, 'tgt_hit_list': tgt_hit_list,
        'src_agg': None, 'tgt_agg': None,
    }


def _score_agg_items(sb: Dict, tb: Dict, missing_side: Optional[str], label_prefix: str) -> List[Dict]:
    """Build one item per named aggregation — the union of both sides' aggregation names, so
    an agg-only query's N aggregations each get their own item instead of being blended into
    one averaged score."""
    src_aggs = sb.get('aggregations', {}) or {}
    tgt_aggs = tb.get('aggregations', {}) or {}
    items = []
    for name in sorted(set(src_aggs) | set(tgt_aggs)):
        sa, ta = src_aggs.get(name), tgt_aggs.get(name)
        j, lbl = (None, missing_side) if missing_side else score_agg(sa, ta)
        items.append({
            'label': f'{label_prefix} · agg:{name}'.strip(' ·'), 'kind': 'agg', 'j': j, 'j_label': lbl,
            'rbo_j': None, 'rbo_label': None,
            'src_hits': None, 'tgt_hits': None, 'src_hit_list': [], 'tgt_hit_list': [],
            'src_agg': sa, 'tgt_agg': ta,
        })
    return items


def _response_items(sb: Dict, tb: Dict, missing_side: Optional[str], label_prefix: str) -> List[Dict]:
    """Decompose one response pair (a plain search's body, or one _msearch sub-response) into
    independently-scored items: one for hits if either side has a hits section, plus one per
    aggregation name (union of both sides) — so an agg-only query's N aggregations each get
    their own item instead of being blended into one averaged score."""
    sb = sb if isinstance(sb, dict) else {}
    tb = tb if isinstance(tb, dict) else {}
    items = []
    if 'hits' in sb or 'hits' in tb:
        items.append(_score_hits_item(sb, tb, missing_side, label_prefix))
    items.extend(_score_agg_items(sb, tb, missing_side, label_prefix))
    return items


def _msearch_sub_bodies(src_request: Dict, count: int) -> List[Optional[List[Dict]]]:
    """The raw NDJSON (header, body) pair behind each _msearch sub-query, for the detail
    pane's request view — an _msearch has no per-sub-query HTTP request of its own, just its
    slice of the outer POST body, so this is what "the request" means for one sub-query."""
    seq = (src_request.get('payload', {}) or {}).get('inlinedJsonSequenceBodies', [])
    pairs: List[Optional[List[Dict]]] = []
    for i in range(count):
        pair = seq[2 * i:2 * i + 2]
        pairs.append(pair if pair else None)
    return pairs


def _msearch_sub_labels(src_request: Dict, count: int) -> List[str]:
    seq = (src_request.get('payload', {}) or {}).get('inlinedJsonSequenceBodies', [])
    labels = []
    for i in range(count):
        header = seq[2 * i] if 2 * i < len(seq) else None
        pref = header.get('preference') if isinstance(header, dict) else None
        labels.append(f'{i + 1}/{count} ({pref})' if pref else f'{i + 1}/{count}')
    return labels


def _missing_side(src_resp: Dict, tgt: Dict) -> Optional[str]:
    """Detect a response that never arrived at all (e.g. the browser aborted the request
    before its response arrived — this is real, observed capture data, not hypothetical).
    Status-Code is only absent when the response itself never arrived; forced to an explicit
    unscored label instead of comparing real data against nothing, which would mathematically
    bottom out at 0.0 — indistinguishable from "genuinely diverged" in the display."""
    src_missing = 'Status-Code' not in src_resp
    tgt_missing = 'Status-Code' not in tgt
    if src_missing and tgt_missing:
        return 'no source or target data'
    if src_missing:
        return 'no source data'
    if tgt_missing:
        return 'no target data'
    return None


def _msearch_subqueries(
    src_request: Dict, tgt_request: Dict, src_body: Dict, tgt_body: Dict, missing_side: Optional[str],
) -> List[Dict]:
    """Decompose an _msearch response into one item-group per NDJSON sub-query, labeled with
    its position (e.g. "1/2 (typefilter)") so each item stays traceable to which sub-query it
    came from. Both sides' NDJSON pairs are carried separately, not assumed identical — a
    transformation plugin can legitimately rewrite the body on its way to the target."""
    src_subs = src_body.get('responses', [])
    tgt_subs = tgt_body.get('responses', [])
    n = max(len(src_subs), len(tgt_subs))
    labels = _msearch_sub_labels(src_request, n)
    src_sub_bodies = _msearch_sub_bodies(src_request, n)
    tgt_sub_bodies = _msearch_sub_bodies(tgt_request, n)
    subqueries = []
    for i in range(n):
        sb = src_subs[i] if i < len(src_subs) else {}
        tb = tgt_subs[i] if i < len(tgt_subs) else {}
        for item in _response_items(sb, tb, missing_side, labels[i]):
            item['src_sub_ndjson'] = src_sub_bodies[i]
            item['tgt_sub_ndjson'] = tgt_sub_bodies[i]
            subqueries.append(item)
    return subqueries


def _plain_search_subqueries(src_body: Dict, tgt_body: Dict, missing_side: Optional[str]) -> List[Dict]:
    """A plain (non-_msearch) search's items — same decomposition as one _msearch sub-query,
    just without a position label or NDJSON detail."""
    subqueries = _response_items(src_body, tgt_body, missing_side, '')
    for item in subqueries:
        item['src_sub_ndjson'] = None
        item['tgt_sub_ndjson'] = None
    return subqueries


def score_tuple(r: Dict) -> Dict:
    """Score one TrafficReplayer tuple record (already JSON-decoded).

    Returns "subqueries": a flat list of independently-scored items. A plain search
    contributes 1+ items (hits, plus one per aggregation); an _msearch contributes that same
    decomposition per NDJSON sub-query, labeled with its position (e.g. "1/2 (typefilter)")
    so each item stays traceable to which sub-query it came from.
    """
    src_request = r.get('sourceRequest', {})
    tgt_request = r.get('targetRequest', {})
    src_resp = r.get('sourceResponse', {})
    tgt_resps = r.get('targetResponses', [])
    method = src_request.get('Method', '?')
    uri = src_request.get('Request-URI', '?')
    src_status = src_resp.get('Status-Code', '?')
    base = {
        'method': method, 'uri': uri, 'src_status': src_status,
        'src_request': src_request, 'tgt_request': tgt_request, 'src_response': src_resp,
    }
    if method == 'OPTIONS':
        return {**base, 'tgt_status': None, 'tgt_response': None,
                'j_label': 'preflight', 'replayed': False, 'subqueries': []}
    if not tgt_resps:
        return {**base, 'tgt_status': None, 'tgt_response': None,
                'j_label': None, 'replayed': False, 'subqueries': []}
    tgt = tgt_resps[0]
    tgt_status = tgt.get('Status-Code', '?')
    src_body = src_resp.get('payload', {}).get('inlinedJsonBody', {})
    tgt_body = tgt.get('payload', {}).get('inlinedJsonBody', {})
    missing_side = _missing_side(src_resp, tgt)
    is_msearch = '_msearch' in uri or 'responses' in src_body
    if is_msearch:
        subqueries = _msearch_subqueries(src_request, tgt_request, src_body, tgt_body, missing_side)
    else:
        subqueries = _plain_search_subqueries(src_body, tgt_body, missing_side)
    return {**base, 'tgt_status': tgt_status, 'tgt_response': tgt,
            'j_label': None, 'replayed': True, 'subqueries': subqueries}
