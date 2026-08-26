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
    sv = src_body.get('hits', {}).get('total', {}).get('value') or src_body.get('count')
    tv = tgt_body.get('hits', {}).get('total', {}).get('value') or tgt_body.get('count')
    if sv is not None and tv is not None:
        mx = max(sv, tv)
        return ((min(sv, tv) / mx) if mx > 0 else 1.0), 'hit count ratio'
    return None, None


def score_msearch(src_body: Dict, tgt_body: Dict) -> Tuple[Optional[float], Optional[str]]:
    src_subs = src_body.get('responses', [])
    tgt_subs = tgt_body.get('responses', [])
    scores = []
    for sb, tb in zip(src_subs, tgt_subs):
        if not isinstance(sb, dict) or not isinstance(tb, dict):
            continue
        if sb.get('status', 200) >= 400 or tb.get('status', 200) >= 400:
            continue
        j, _ = compute_jaccard(sb, tb)
        if j is not None:
            scores.append(j)
    if not scores:
        return None, None
    return sum(scores) / len(scores), f'msearch ({len(src_subs)} sub-queries)'


def score_tuple(r: Dict) -> Dict:
    """Score one TrafficReplayer tuple record (already JSON-decoded).

    Returns a dict with j (Jaccard score or None), j_label, src/tgt status and hit counts, and
    whether the request was actually replayed — used uniformly by both the summary sparkline and
    the per-tuple table.
    """
    src_resp = r.get('sourceResponse', {})
    tgt_resps = r.get('targetResponses', [])
    method = r.get('sourceRequest', {}).get('Method', '?')
    uri = r.get('sourceRequest', {}).get('Request-URI', '?')
    src_status = src_resp.get('Status-Code', '?')
    if method == 'OPTIONS':
        return dict(method=method, uri=uri, src_status=src_status,
                    tgt_status=None, j=None, j_label='preflight',
                    src_hits=None, tgt_hits=None, replayed=False)
    if not tgt_resps:
        return dict(method=method, uri=uri, src_status=src_status,
                    tgt_status=None, j=None, j_label=None,
                    src_hits=None, tgt_hits=None, replayed=False)
    tgt = tgt_resps[0]
    tgt_status = tgt.get('Status-Code', '?')
    src_body = src_resp.get('payload', {}).get('inlinedJsonBody', {})
    tgt_body = tgt.get('payload', {}).get('inlinedJsonBody', {})
    is_msearch = ('_msearch' in uri or 'responses' in src_body)
    if is_msearch:
        j, lbl = score_msearch(src_body, tgt_body)
        src_hits = sum(
            (s.get('hits', {}).get('total', {}).get('value') or 0)
            for s in src_body.get('responses', []) if isinstance(s, dict)) or None
        tgt_hits = sum(
            (s.get('hits', {}).get('total', {}).get('value') or 0)
            for s in tgt_body.get('responses', []) if isinstance(s, dict)) or None
    else:
        j, lbl = compute_jaccard(src_body, tgt_body)
        src_hits = src_body.get('hits', {}).get('total', {}).get('value') or src_body.get('count')
        tgt_hits = tgt_body.get('hits', {}).get('total', {}).get('value') or tgt_body.get('count')
    return dict(method=method, uri=uri,
                src_status=src_status, tgt_status=tgt_status,
                j=j, j_label=lbl,
                src_hits=src_hits, tgt_hits=tgt_hits,
                replayed=True)
