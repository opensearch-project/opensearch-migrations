"""Replay-quality scoring for TrafficReplayer tuples.

Ported unchanged from 07-live-jaccard-kafka.sh's embedded Python: given a source/target
request-response tuple, estimate how similar the two result sets were. Doc-ID overlap is
preferred when both sides returned hits; aggregation buckets and raw hit counts are the
fallbacks, in that order, since a search can be "correct" without returning any hits at all
(an empty result set matched on both sides is a Jaccard of 1.0, not "no data").
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


def weighted_jaccard_aggs(src_aggs: Dict, tgt_aggs: Dict) -> Tuple[Optional[float], Optional[str]]:
    scores = []
    for name in set(src_aggs) | set(tgt_aggs):
        sa = src_aggs.get(name, {})
        ta = tgt_aggs.get(name, {})
        if not isinstance(sa, dict) or not isinstance(ta, dict):
            continue
        sb = sa.get('buckets', [])
        tb = ta.get('buckets', [])
        if sb or tb:
            sc = {b['key']: b.get('doc_count', 0) for b in sb if 'key' in b}
            tc = {b['key']: b.get('doc_count', 0) for b in tb if 'key' in b}
            keys = set(sc) | set(tc)
            if keys:
                n = sum(min(sc.get(k, 0), tc.get(k, 0)) for k in keys)
                d = sum(max(sc.get(k, 0), tc.get(k, 0)) for k in keys)
                if d > 0:
                    scores.append(n / d)
            continue
        sdc = sa.get('doc_count')
        tdc = ta.get('doc_count')
        if sdc is not None and tdc is not None:
            mx = max(sdc, tdc)
            if mx > 0:
                scores.append(min(sdc, tdc) / mx)
        for sub, ssub in sa.items():
            if not isinstance(ssub, dict) or 'value' not in ssub:
                continue
            tsub = ta.get(sub, {})
            if not isinstance(tsub, dict):
                continue
            sv, tv = ssub['value'], tsub.get('value')
            if sv is not None and tv is not None:
                mx = max(sv, tv)
                if mx > 0:
                    scores.append(min(sv, tv) / mx)
    if not scores:
        return None, None
    return sum(scores) / len(scores), 'agg'


def compute_jaccard(src_body: Dict, tgt_body: Dict) -> Tuple[Optional[float], Optional[str]]:
    si = hit_doc_ids(src_body)
    ti = hit_doc_ids(tgt_body)
    if si is not None and ti is not None:
        return jaccard_sets(si, ti), 'doc IDs'
    j, lbl = weighted_jaccard_aggs(
        src_body.get('aggregations', {}), tgt_body.get('aggregations', {}))
    if j is not None:
        return j, lbl
    sv = hit_count(src_body)
    tv = hit_count(tgt_body)
    if sv is not None and tv is not None:
        mx = max(sv, tv)
        return ((min(sv, tv) / mx) if mx > 0 else 1.0), 'hit count ratio'
    return None, None


def _subquery_score(sb: Dict, tb: Dict) -> Tuple[Optional[float], Optional[str]]:
    if not isinstance(sb, dict) or not isinstance(tb, dict):
        return None, 'malformed'
    if sb.get('status', 200) >= 400 or tb.get('status', 200) >= 400:
        return None, 'error'
    return compute_jaccard(sb, tb)


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

    Every replayed request decomposes into one or more "subqueries": a plain search is
    exactly one (the request itself); an _msearch is one per NDJSON search action, each
    scored independently. A single blended average across an msearch's sub-queries hides
    exactly the failure that matters — one badly-diverged sub-query pulled toward 1.0 by two
    good ones still looks fine in aggregate — so subqueries are returned as a list rather
    than pre-averaged, leaving any aggregation to the display layer.
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
    # or tgt_body as {}. compute_jaccard would then compare real data against nothing and
    # mathematically bottom out at 0.0 — indistinguishable from "genuinely diverged" in the
    # display. Detected once here (Status-Code is only absent when the response itself never
    # arrived) and forced to an explicit unscored label instead, for every subquery at once.
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
            j, lbl = (None, missing_side) if missing_side else _subquery_score(sb, tb)
            subqueries.append(dict(
                label=labels[i], j=j, j_label=lbl,
                src_hits=hit_count(sb) if isinstance(sb, dict) else None,
                tgt_hits=hit_count(tb) if isinstance(tb, dict) else None,
                src_hit_list=hit_summaries(sb) if isinstance(sb, dict) else [],
                tgt_hit_list=hit_summaries(tb) if isinstance(tb, dict) else [],
                src_sub_ndjson=src_sub_bodies[i], tgt_sub_ndjson=tgt_sub_bodies[i],
            ))
    else:
        j, lbl = (None, missing_side) if missing_side else compute_jaccard(src_body, tgt_body)
        subqueries = [dict(
            label='query', j=j, j_label=lbl,
            src_hits=hit_count(src_body), tgt_hits=hit_count(tgt_body),
            src_hit_list=hit_summaries(src_body), tgt_hit_list=hit_summaries(tgt_body),
            src_sub_ndjson=None, tgt_sub_ndjson=None,
        )]
    return dict(base, tgt_status=tgt_status, tgt_response=tgt,
                j_label=None, replayed=True, subqueries=subqueries)
