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


def score_agg(sa: Dict, ta: Dict) -> Tuple[Optional[float], str]:
    """Score ONE named aggregation — bucket-based (terms/date-histogram/...) or a single
    metric (avg/sum/cardinality/...). Bucket comparison is a weighted per-key overlap, same
    idea as Jaccard but weighted by doc_count so a bucket with 10000 docs counts for more
    than one with 1."""
    sa = sa if isinstance(sa, dict) else {}
    ta = ta if isinstance(ta, dict) else {}
    sb = sa.get('buckets', [])
    tb = ta.get('buckets', [])
    if sb or tb:
        sc = {b['key']: b.get('doc_count', 0) for b in sb if 'key' in b}
        tc = {b['key']: b.get('doc_count', 0) for b in tb if 'key' in b}
        keys = set(sc) | set(tc)
        if not keys:
            return None, 'agg (buckets)'
        n = sum(min(sc.get(k, 0), tc.get(k, 0)) for k in keys)
        d = sum(max(sc.get(k, 0), tc.get(k, 0)) for k in keys)
        return ((n / d) if d > 0 else 1.0), 'agg (buckets)'
    sdc, tdc = sa.get('doc_count'), ta.get('doc_count')
    if sdc is not None and tdc is not None:
        mx = max(sdc, tdc)
        return ((min(sdc, tdc) / mx) if mx > 0 else 1.0), 'agg (doc_count)'
    if 'value' in sa or 'value' in ta:
        sv, tv = sa.get('value'), ta.get('value')
        if sv is not None and tv is not None:
            mx = max(sv, tv)
            return ((min(sv, tv) / mx) if mx > 0 else 1.0), 'agg (metric)'
    return None, 'agg'


def _response_items(sb: Dict, tb: Dict, missing_side: Optional[str], label_prefix: str) -> List[Dict]:
    """Decompose one response pair (a plain search's body, or one _msearch sub-response) into
    independently-scored items: one for hits if either side has a hits section, plus one per
    aggregation name (union of both sides) — so an agg-only query's N aggregations each get
    their own item instead of being blended into one averaged score."""
    sb = sb if isinstance(sb, dict) else {}
    tb = tb if isinstance(tb, dict) else {}
    items = []

    if 'hits' in sb or 'hits' in tb:
        j, lbl = (None, missing_side) if missing_side else compute_hits_jaccard(sb, tb)
        items.append(dict(
            label=f'{label_prefix} · hits'.strip(' ·'), kind='hits', j=j, j_label=lbl,
            src_hits=hit_count(sb), tgt_hits=hit_count(tb),
            src_hit_list=hit_summaries(sb), tgt_hit_list=hit_summaries(tb),
            src_agg=None, tgt_agg=None,
        ))

    src_aggs = sb.get('aggregations', {}) or {}
    tgt_aggs = tb.get('aggregations', {}) or {}
    for name in sorted(set(src_aggs) | set(tgt_aggs)):
        sa, ta = src_aggs.get(name), tgt_aggs.get(name)
        j, lbl = (None, missing_side) if missing_side else score_agg(sa, ta)
        items.append(dict(
            label=f'{label_prefix} · agg:{name}'.strip(' ·'), kind='agg', j=j, j_label=lbl,
            src_hits=None, tgt_hits=None, src_hit_list=[], tgt_hit_list=[],
            src_agg=sa, tgt_agg=ta,
        ))

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
    base = dict(method=method, uri=uri, src_status=src_status,
                src_request=src_request, tgt_request=tgt_request, src_response=src_resp)
    if method == 'OPTIONS':
        return dict(base, tgt_status=None, tgt_response=None,
                    j_label='preflight', replayed=False, subqueries=[])
    if not tgt_resps:
        return dict(base, tgt_status=None, tgt_response=None,
                    j_label=None, replayed=False, subqueries=[])
    tgt = tgt_resps[0]
    tgt_status = tgt.get('Status-Code', '?')
    src_body = src_resp.get('payload', {}).get('inlinedJsonBody', {})
    tgt_body = tgt.get('payload', {}).get('inlinedJsonBody', {})
    # A missing sourceResponse/targetResponse (e.g. the browser aborted the request before its
    # response arrived — this is real, observed capture data, not hypothetical) leaves src_body
    # or tgt_body as {}. Comparing real data against nothing would mathematically bottom out at
    # 0.0 — indistinguishable from "genuinely diverged" in the display. Detected once here
    # (Status-Code is only absent when the response itself never arrived) and forced to an
    # explicit unscored label instead, for every item at once.
    missing_side = (
        'no source or target data' if 'Status-Code' not in src_resp and 'Status-Code' not in tgt else
        'no source data' if 'Status-Code' not in src_resp else
        'no target data' if 'Status-Code' not in tgt else
        None
    )
    is_msearch = ('_msearch' in uri or 'responses' in src_body)
    if is_msearch:
        src_subs = src_body.get('responses', [])
        tgt_subs = tgt_body.get('responses', [])
        n = max(len(src_subs), len(tgt_subs))
        labels = _msearch_sub_labels(src_request, n)
        # Both sides' NDJSON pairs are carried separately, not assumed identical — a
        # transformation plugin can legitimately rewrite the body on its way to the target.
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
    else:
        subqueries = _response_items(src_body, tgt_body, missing_side, '')
        for item in subqueries:
            item['src_sub_ndjson'] = None
            item['tgt_sub_ndjson'] = None
    return dict(base, tgt_status=tgt_status, tgt_response=tgt,
                j_label=None, replayed=True, subqueries=subqueries)
