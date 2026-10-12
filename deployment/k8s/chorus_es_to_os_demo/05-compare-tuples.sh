#!/usr/bin/env bash
# =============================================================================
# 05-compare-tuples.sh
#
# Downloads traffic replay tuples from S3 (LocalStack) and compares the
# Elasticsearch (source) responses against the OpenSearch (target) responses
# for every request captured by the CaptureProxy.
#
# Tuples are written by the TrafficReplayer and contain:
#   sourceRequest   — the original HTTP request
#   sourceResponse  — the ES response at capture time
#   targetRequest   — the replayed request (with OS credentials)
#   targetResponses — the OS response(s) from the replayer
#
# PREREQUISITE: migration-console-0 pod must be running in the ma namespace.
# =============================================================================
set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
S3_BUCKET="migrations-default-123456789012-dev-us-east-2"
S3_PREFIX="tuples/"

# ── Helpers ───────────────────────────────────────────────────────────────────
section() { echo ""; echo "══════════════════════════════════════════════════════"; echo "  $*"; echo "══════════════════════════════════════════════════════"; }

s3() {
  kubectl exec -n ma migration-console-0 -- \
    env AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
    aws --endpoint-url http://localstack:4566 --region us-east-2 \
    s3 "$@"
}

# ── Check pre-requisite ───────────────────────────────────────────────────────
echo "Checking migration-console pod..."
if ! kubectl get pod migration-console-0 -n ma &>/dev/null; then
  echo "ERROR: migration-console-0 pod not found in namespace 'ma'"
  exit 1
fi
echo "OK"

# ── Find replayer prefix ──────────────────────────────────────────────────────
section "Locating tuple files in S3"

REPLAYER_DIR=$(s3 ls "s3://${S3_BUCKET}/${S3_PREFIX}" 2>/dev/null \
  | awk '{print $NF}' | grep -v '^$' | head -1)

if [ -z "$REPLAYER_DIR" ]; then
  echo "No tuple files found at s3://${S3_BUCKET}/${S3_PREFIX}"
  echo "Make sure the TrafficReplayer has processed some requests and its"
  echo "tupleMaxBufferSeconds window (10s) has elapsed since the last request."
  exit 1
fi

echo "Replayer: ${REPLAYER_DIR}"

FILE_LIST=$(s3 ls --recursive "s3://${S3_BUCKET}/${S3_PREFIX}${REPLAYER_DIR}" 2>/dev/null \
  | awk '{print $NF}' | grep '\.log\.gz$')

FILE_COUNT=$(echo "$FILE_LIST" | grep -c '.' || true)
echo "Found ${FILE_COUNT} tuple file(s)"

# ── Download tuple files ──────────────────────────────────────────────────────
section "Downloading and analysing tuples"

TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

echo "$FILE_LIST" | while read -r s3key; do
  local_name="${TMPDIR}/$(basename "$s3key")"
  s3 cp "s3://${S3_BUCKET}/${s3key}" "$local_name" >/dev/null 2>&1
  # Decompress into a single combined file
  kubectl exec -n ma migration-console-0 -- gunzip -c "/$(basename "$local_name")" \
    2>/dev/null >> "${TMPDIR}/all-tuples.ndjson" || true
done

# ── Re-download directly using kubectl cp approach ────────────────────────────
# (simpler: stream gunzip from migration-console for each file)
: > "${TMPDIR}/all-tuples.ndjson"
while read -r s3key; do
  kubectl exec -n ma migration-console-0 -- \
    bash -c "
      env AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
      aws --endpoint-url http://localstack:4566 --region us-east-2 \
      s3 cp 's3://${S3_BUCKET}/${s3key}' - 2>/dev/null | gunzip
    " >> "${TMPDIR}/all-tuples.ndjson" 2>/dev/null || true
done <<< "$FILE_LIST"

TUPLE_COUNT=$(grep -c '{' "${TMPDIR}/all-tuples.ndjson" 2>/dev/null || echo 0)
echo "Total tuples: ${TUPLE_COUNT}"

if [ "$TUPLE_COUNT" -eq 0 ]; then
  echo ""
  echo "No tuples to analyse. The replayer buffers tuples for up to 10 seconds"
  echo "before flushing to S3. Wait a moment and re-run this script."
  exit 0
fi

# ── Analyse with Python ───────────────────────────────────────────────────────
python3 - "${TMPDIR}/all-tuples.ndjson" << 'PYEOF'
import sys, json

path = sys.argv[1]

records = []
with open(path) as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            records.append(json.loads(line))
        except json.JSONDecodeError:
            pass

def jaccard_sets(a, b):
    """Standard Jaccard on two sets: |A∩B| / |A∪B|.
    Returns None when both sets are empty (undefined, not 1.0)."""
    if not a and not b:
        return None
    return len(a & b) / len(a | b)

def hit_doc_ids(body):
    """Return the set of _id values from hits.hits, or None if no hits."""
    hits = body.get('hits', {}).get('hits', [])
    ids = {h['_id'] for h in hits if '_id' in h}
    return ids if ids else None

def weighted_jaccard_aggs(src_aggs, tgt_aggs):
    """Weighted Jaccard across all aggregations in a response.

    Terms agg  → Σ min(count_A[k], count_B[k]) / Σ max(count_A[k], count_B[k])
                 over all bucket keys in A∪B.  Penalises count drift per bucket.
    Filter agg → min(doc_count_A, doc_count_B) / max(...)  (single-value ratio)
    Metric agg → min(value_A, value_B) / max(...)

    The per-agg scores are averaged into a single number so that one query
    with three aggregations gets one Jaccard value.
    Returns (score, description) or (None, None)."""
    scores = []

    for agg_name in set(src_aggs) | set(tgt_aggs):
        src_agg = src_aggs.get(agg_name, {})
        tgt_agg = tgt_aggs.get(agg_name, {})
        if not isinstance(src_agg, dict) or not isinstance(tgt_agg, dict):
            continue

        # ── Terms agg: buckets with doc_count ────────────────────────────────
        src_buckets = src_agg.get('buckets', [])
        tgt_buckets = tgt_agg.get('buckets', [])
        if src_buckets or tgt_buckets:
            src_counts = {b['key']: b.get('doc_count', 0) for b in src_buckets if 'key' in b}
            tgt_counts = {b['key']: b.get('doc_count', 0) for b in tgt_buckets if 'key' in b}
            all_keys = set(src_counts) | set(tgt_counts)
            if all_keys:
                numer = sum(min(src_counts.get(k, 0), tgt_counts.get(k, 0)) for k in all_keys)
                denom = sum(max(src_counts.get(k, 0), tgt_counts.get(k, 0)) for k in all_keys)
                if denom > 0:
                    scores.append((numer / denom, f'{agg_name} buckets'))
            continue

        # ── Filter / single-value agg: compare doc_count ─────────────────────
        src_dc = src_agg.get('doc_count')
        tgt_dc = tgt_agg.get('doc_count')
        if src_dc is not None and tgt_dc is not None:
            mx = max(src_dc, tgt_dc)
            if mx > 0:
                scores.append((min(src_dc, tgt_dc) / mx, f'{agg_name} doc_count'))

        # ── Metric sub-aggs: value_count, sum, avg, max, min ─────────────────
        for sub_name, src_sub in src_agg.items():
            if not isinstance(src_sub, dict) or 'value' not in src_sub:
                continue
            tgt_sub = tgt_agg.get(sub_name, {})
            if not isinstance(tgt_sub, dict):
                continue
            sv, tv = src_sub['value'], tgt_sub.get('value')
            if sv is not None and tv is not None:
                mx = max(sv, tv)
                if mx > 0:
                    scores.append((min(sv, tv) / mx, f'{agg_name}.{sub_name}'))

    if not scores:
        return None, None
    avg = sum(s for s, _ in scores) / len(scores)
    labels = ', '.join(dict.fromkeys(lbl for _, lbl in scores))  # deduplicate, keep order
    return avg, labels

# ── Per-request table ─────────────────────────────────────────────────────────
print()
print(f"  {'✓/✗':<4} {'Method':<8} {'URI':<42} {'ES':>6} {'OS':>6}  {'ES ms':>6} {'OS ms':>6}  {'ES hits':>9} {'OS hits':>9}  {'Jaccard':>7}  notes")
print(f"  {'-'*4} {'-'*8} {'-'*42} {'-'*6} {'-'*6}  {'-'*6} {'-'*6}  {'-'*9} {'-'*9}  {'-'*7}")

mismatches     = 0
status_matches = 0
hit_diffs      = 0
total_es_ms    = 0
total_os_ms    = 0
no_target      = 0
n_options      = 0
jaccard_scores = []

def compute_jaccard_single(src_body, tgt_body):
    """Jaccard for a single search response body. Returns (score, label)."""
    src_ids = hit_doc_ids(src_body)
    tgt_ids = hit_doc_ids(tgt_body)
    if src_ids is not None and tgt_ids is not None:
        return jaccard_sets(src_ids, tgt_ids), 'doc IDs'
    j, lbl = weighted_jaccard_aggs(src_body.get('aggregations', {}),
                                    tgt_body.get('aggregations', {}))
    if j is not None:
        return j, lbl
    sv = src_body.get('hits', {}).get('total', {}).get('value') or src_body.get('count')
    tv = tgt_body.get('hits', {}).get('total', {}).get('value') or tgt_body.get('count')
    if sv is not None and tv is not None:
        mx = max(sv, tv)
        return ((min(sv, tv) / mx) if mx > 0 else 1.0), 'hit count ratio'
    return None, None

def score_msearch(src_body, tgt_body):
    """Average Jaccard across _msearch sub-responses."""
    src_subs = src_body.get('responses', [])
    tgt_subs = tgt_body.get('responses', [])
    scores = []
    for sb, tb in zip(src_subs, tgt_subs):
        if not isinstance(sb, dict) or not isinstance(tb, dict): continue
        if sb.get('status', 200) >= 400 or tb.get('status', 200) >= 400: continue
        j, _ = compute_jaccard_single(sb, tb)
        if j is not None:
            scores.append(j)
    if not scores:
        return None, None
    n = len(src_subs)
    return sum(scores) / len(scores), f'msearch ({n} sub-queries, {len(scores)} scored)'

for r in records:
    src_req   = r.get('sourceRequest', {})
    src_resp  = r.get('sourceResponse', {})
    tgt_resps = r.get('targetResponses', [])

    method = src_req.get('Method', '?')
    uri    = src_req.get('Request-URI', '?')
    if len(uri) > 42:
        uri = uri[:39] + '...'

    src_status = src_resp.get('Status-Code', '?')
    src_ms     = src_resp.get('response_time_ms', '?')

    # Skip CORS preflights — no result set to compare
    if method == 'OPTIONS':
        n_options += 1
        print(f"  {'–':<4} {method:<8} {uri:<42} {str(src_status):>6} {'(preflight)':>6}")
        continue

    if not tgt_resps:
        no_target += 1
        print(f"  {'?':<4} {method:<8} {uri:<42} {str(src_status):>6} {'(no replay)':>6}")
        continue

    tgt = tgt_resps[0]
    tgt_status = tgt.get('Status-Code', '?')
    tgt_ms     = tgt.get('response_time_ms', '?')

    if isinstance(src_ms, (int, float)):
        total_es_ms += src_ms
    if isinstance(tgt_ms, (int, float)):
        total_os_ms += tgt_ms

    status_ok = (src_status == tgt_status)
    status_matches += 1 if status_ok else 0
    mismatches     += 0 if status_ok else 1
    icon = '✓' if status_ok else '✗'

    src_body = src_resp.get('payload', {}).get('inlinedJsonBody', {})
    tgt_body = tgt.get('payload', {}).get('inlinedJsonBody', {})

    is_msearch = ('_msearch' in (src_req.get('Request-URI', '')) or 'responses' in src_body)

    if is_msearch:
        j, j_label = score_msearch(src_body, tgt_body)
        # Aggregate hit counts across all sub-responses for display
        src_hits = sum(
            (s.get('hits', {}).get('total', {}).get('value') or 0)
            for s in src_body.get('responses', []) if isinstance(s, dict)) or ''
        tgt_hits = sum(
            (s.get('hits', {}).get('total', {}).get('value') or 0)
            for s in tgt_body.get('responses', []) if isinstance(s, dict)) or ''
    else:
        j, j_label = compute_jaccard_single(src_body, tgt_body)
        src_hits = src_body.get('hits', {}).get('total', {}).get('value', '')
        tgt_hits = tgt_body.get('hits', {}).get('total', {}).get('value', '')

    src_hits_str = str(src_hits) if src_hits != '' else '-'
    tgt_hits_str = str(tgt_hits) if tgt_hits != '' else '-'

    if j is not None:
        jaccard_str = f'{j:.3f}'
        jaccard_scores.append((j, j_label))
    else:
        jaccard_str = '  -  '

    # Notes
    notes = []
    if not status_ok:
        notes.append('status mismatch')
    if src_hits != '' and tgt_hits != '' and src_hits != tgt_hits:
        hit_diffs += 1
        notes.append(f'hits diff {tgt_hits - src_hits:+d}')

    if not is_msearch:
        # Filter-agg nested value differences (single-response only)
        src_aggs = src_body.get('aggregations', {})
        tgt_aggs = tgt_body.get('aggregations', {})
        for agg_name, src_agg in src_aggs.items():
            if not isinstance(src_agg, dict):
                continue
            tgt_agg = tgt_aggs.get(agg_name, {})
            for sub_name, src_sub in src_agg.items():
                if isinstance(src_sub, dict) and 'value' in src_sub:
                    tgt_sub = tgt_agg.get(sub_name, {}) if isinstance(tgt_agg, dict) else {}
                    tgt_val = tgt_sub.get('value', '')
                    if tgt_val != '' and src_sub['value'] != tgt_val:
                        notes.append(f'{agg_name}.{sub_name}: {src_sub["value"]}→{tgt_val}')

    if j_label and is_msearch and not notes:
        notes.append(j_label)

    notes_str = ', '.join(notes)
    print(f"  {icon:<4} {method:<8} {uri:<42} {str(src_status):>6} {str(tgt_status):>6}  {str(src_ms):>6} {str(tgt_ms):>6}  {src_hits_str:>9} {tgt_hits_str:>9}  {jaccard_str:>7}  {notes_str}")

# ── Summary ───────────────────────────────────────────────────────────────────
total    = len(records)
compared = total - no_target - n_options
print()
print("══════════════════════════════════════════════════════")
print("  SUMMARY")
print("══════════════════════════════════════════════════════")
print(f"  Total tuples analysed : {total}  ({n_options} OPTIONS preflight, {no_target} not yet replayed)")
print(f"  Status code matches   : {status_matches}/{compared} ({100*status_matches//compared if compared else 0}%)")
if mismatches:
    print(f"  Status mismatches     : {mismatches}  ← review these")
if hit_diffs:
    print(f"  Hit count differences : {hit_diffs}  ← document counts differ between ES and OS")
if no_target:
    print(f"  Not yet replayed      : {no_target}  ← still in replayer buffer")
if jaccard_scores:
    avg_j = sum(s for s, _ in jaccard_scores) / len(jaccard_scores)
    perfect = sum(1 for s, _ in jaccard_scores if s == 1.0)
    label_example = jaccard_scores[0][1]
    print(f"  Avg Jaccard similarity: {avg_j:.3f}  (on {label_example}; {perfect}/{len(jaccard_scores)} perfect)")
    print(f"  Jaccard = 1.0 means ES and OS returned identical result sets")
    print(f"  Jaccard < 1.0 means the result sets diverge — worth investigating")
if compared > 0:
    avg_es = total_es_ms // compared
    avg_os = total_os_ms // compared
    print(f"  Avg response time ES  : {avg_es} ms")
    print(f"  Avg response time OS  : {avg_os} ms")
    if avg_os > avg_es:
        print(f"  OS is {avg_os - avg_es} ms slower on average (expected during migration warmup)")
print()
PYEOF
