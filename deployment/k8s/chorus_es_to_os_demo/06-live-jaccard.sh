#!/usr/bin/env bash
# =============================================================================
# 06-live-jaccard.sh
#
# Live terminal monitor — polls the last N traffic replay tuples from S3
# and renders a Jaccard sparkline so you can watch result quality in real time.
#
# PREREQUISITE: migration-console-0 pod must be running in the ma namespace.
# =============================================================================
set -euo pipefail

WINDOW="${WINDOW:-10}"       # tuples to show in the sparkline
INTERVAL="${INTERVAL:-10}"   # seconds between refreshes

export WINDOW INTERVAL
export S3_BUCKET="migrations-default-123456789012-dev-us-east-2"

# ── Check prereq ──────────────────────────────────────────────────────────────
if ! kubectl get pod migration-console-0 -n ma &>/dev/null; then
  echo "ERROR: migration-console-0 pod not found in namespace 'ma'"
  exit 1
fi

trap 'printf "\033[?25h\n"; echo "Stopped."; exit 0' INT TERM
printf "\033[?25l"   # hide cursor

python3 - << 'PYEOF'
import subprocess, json, os, sys, time, datetime, textwrap

WINDOW   = int(os.environ.get('WINDOW', '10'))
INTERVAL = int(os.environ.get('INTERVAL', '10'))
S3_BUCKET = os.environ.get('S3_BUCKET', 'migrations-default-123456789012-dev-us-east-2')
S3_PREFIX = 'tuples/'
SPARK     = '▁▂▃▄▅▆▇█'
WIDTH     = 70

# ── ANSI helpers ──────────────────────────────────────────────────────────────
def esc(code): return f'\033[{code}m'
RESET  = esc(0);  BOLD  = esc(1);  DIM  = esc(2)
GREEN  = esc(32); YELLOW = esc(33); RED = esc(31); CYAN = esc(36)
def clr(): print('\033[2J\033[H', end='')

def bar(j):
    """Single sparkline character for a Jaccard value, or a dim placeholder."""
    if j is None: return DIM + '░' + RESET
    idx = min(7, int(j * 8))
    # colour: red <0.8, yellow <0.95, green >=0.95
    col = GREEN if j >= 0.95 else (YELLOW if j >= 0.80 else RED)
    return col + BOLD + SPARK[idx] + RESET

# ── S3 helpers ────────────────────────────────────────────────────────────────
def s3_ls(*args):
    cmd = ['kubectl', 'exec', '-n', 'ma', 'migration-console-0', '--',
           'env', 'AWS_ACCESS_KEY_ID=test', 'AWS_SECRET_ACCESS_KEY=test',
           'aws', '--endpoint-url', 'http://localstack:4566',
           '--region', 'us-east-2', 's3', 'ls', *args]
    r = subprocess.run(cmd, capture_output=True, text=True)
    return r.stdout

def s3_gunzip(s3_key):
    """Stream-decompress a gzipped S3 object via migration-console."""
    cmd = ['kubectl', 'exec', '-n', 'ma', 'migration-console-0', '--',
           'bash', '-c',
           f'env AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test '
           f'aws --endpoint-url http://localstack:4566 --region us-east-2 '
           f"s3 cp 's3://{S3_BUCKET}/{s3_key}' - 2>/dev/null | gunzip"]
    r = subprocess.run(cmd, capture_output=True)
    return r.stdout.decode('utf-8', errors='replace')

# ── Jaccard logic (mirrors 05-compare-tuples.sh) ─────────────────────────────
def jaccard_sets(a, b):
    if not a and not b: return None
    return len(a & b) / len(a | b)

def hit_doc_ids(body):
    hits = body.get('hits', {}).get('hits', [])
    ids  = {h['_id'] for h in hits if '_id' in h}
    return ids if ids else None

def weighted_jaccard_aggs(src_aggs, tgt_aggs):
    scores = []
    for name in set(src_aggs) | set(tgt_aggs):
        sa = src_aggs.get(name, {}); ta = tgt_aggs.get(name, {})
        if not isinstance(sa, dict) or not isinstance(ta, dict): continue
        sb = sa.get('buckets', []); tb = ta.get('buckets', [])
        if sb or tb:
            sc = {b['key']: b.get('doc_count', 0) for b in sb if 'key' in b}
            tc = {b['key']: b.get('doc_count', 0) for b in tb if 'key' in b}
            keys = set(sc) | set(tc)
            if keys:
                n = sum(min(sc.get(k,0), tc.get(k,0)) for k in keys)
                d = sum(max(sc.get(k,0), tc.get(k,0)) for k in keys)
                if d > 0: scores.append(n / d)
            continue
        sdc = sa.get('doc_count'); tdc = ta.get('doc_count')
        if sdc is not None and tdc is not None:
            mx = max(sdc, tdc)
            if mx > 0: scores.append(min(sdc, tdc) / mx)
        for sub, ssub in sa.items():
            if not isinstance(ssub, dict) or 'value' not in ssub: continue
            tsub = ta.get(sub, {})
            if not isinstance(tsub, dict): continue
            sv, tv = ssub['value'], tsub.get('value')
            if sv is not None and tv is not None:
                mx = max(sv, tv)
                if mx > 0: scores.append(min(sv, tv) / mx)
    if not scores: return None, None
    return sum(scores)/len(scores), 'agg'

def compute_jaccard(src_body, tgt_body):
    si = hit_doc_ids(src_body); ti = hit_doc_ids(tgt_body)
    if si is not None and ti is not None:
        j = jaccard_sets(si, ti)
        return j, 'doc IDs'
    j, lbl = weighted_jaccard_aggs(
        src_body.get('aggregations', {}),
        tgt_body.get('aggregations', {}))
    if j is not None: return j, lbl
    sv = (src_body.get('hits', {}).get('total', {}).get('value')
          or src_body.get('count'))
    tv = (tgt_body.get('hits', {}).get('total', {}).get('value')
          or tgt_body.get('count'))
    if sv is not None and tv is not None:
        mx = max(sv, tv)
        return ((min(sv, tv)/mx) if mx > 0 else 1.0), 'hit count ratio'
    return None, None

def score_msearch(src_body, tgt_body):
    """Average Jaccard across _msearch sub-responses."""
    src_subs = src_body.get('responses', [])
    tgt_subs = tgt_body.get('responses', [])
    scores = []
    for sb, tb in zip(src_subs, tgt_subs):
        if not isinstance(sb, dict) or not isinstance(tb, dict): continue
        if sb.get('status', 200) >= 400 or tb.get('status', 200) >= 400: continue
        j, _ = compute_jaccard(sb, tb)
        if j is not None:
            scores.append(j)
    if not scores:
        return None, None
    n = len(src_subs)
    return sum(scores) / len(scores), f'msearch ({n} sub-queries)'

def score_tuple(r):
    src_resp  = r.get('sourceResponse', {})
    tgt_resps = r.get('targetResponses', [])
    method    = r.get('sourceRequest', {}).get('Method', '?')
    uri       = r.get('sourceRequest', {}).get('Request-URI', '?')
    src_status = src_resp.get('Status-Code', '?')
    src_ms     = src_resp.get('response_time_ms', '?')
    # Skip CORS preflight — no comparable result
    if method == 'OPTIONS':
        return dict(method=method, uri=uri, src_status=src_status,
                    tgt_status=None, src_ms=src_ms, tgt_ms=None,
                    j=None, j_label='preflight', replayed=False)
    if not tgt_resps:
        return dict(method=method, uri=uri, src_status=src_status,
                    tgt_status=None, src_ms=src_ms, tgt_ms=None,
                    j=None, j_label=None, replayed=False)
    tgt = tgt_resps[0]
    tgt_status = tgt.get('Status-Code', '?')
    tgt_ms     = tgt.get('response_time_ms', '?')
    src_body   = src_resp.get('payload', {}).get('inlinedJsonBody', {})
    tgt_body   = tgt.get('payload', {}).get('inlinedJsonBody', {})
    # _msearch: body has a top-level "responses" array
    is_msearch = ('_msearch' in uri or 'responses' in src_body)
    if is_msearch:
        j, lbl = score_msearch(src_body, tgt_body)
        src_hits = sum(
            (s.get('hits', {}).get('total', {}).get('value') or 0)
            for s in src_body.get('responses', []) if isinstance(s, dict))
        tgt_hits = sum(
            (s.get('hits', {}).get('total', {}).get('value') or 0)
            for s in tgt_body.get('responses', []) if isinstance(s, dict))
        src_hits = src_hits or None
        tgt_hits = tgt_hits or None
    else:
        j, lbl = compute_jaccard(src_body, tgt_body)
        src_hits = src_body.get('hits', {}).get('total', {}).get('value') or src_body.get('count')
        tgt_hits = tgt_body.get('hits', {}).get('total', {}).get('value') or tgt_body.get('count')
    return dict(method=method, uri=uri,
                src_status=src_status, tgt_status=tgt_status,
                src_ms=src_ms, tgt_ms=tgt_ms,
                j=j, j_label=lbl,
                src_hits=src_hits, tgt_hits=tgt_hits,
                replayed=True)

# ── Fetch recent tuples ───────────────────────────────────────────────────────
def fetch_recent_tuples(n):
    # Collect prefixes for ALL replayer pods (pod restarts create new prefixes)
    replayers = []
    for line in s3_ls(f's3://{S3_BUCKET}/{S3_PREFIX}').splitlines():
        part = line.strip().rstrip('/')
        if part: replayers.append(part.split()[-1])
    if not replayers: return [], '(no replayer prefix found)'

    all_keys = []
    for replayer in replayers:
        for line in s3_ls('--recursive', f's3://{S3_BUCKET}/{S3_PREFIX}{replayer}').splitlines():
            parts = line.split()
            if parts and parts[-1].endswith('.log.gz'):
                all_keys.append(parts[-1])
    all_keys.sort()
    if not all_keys: return [], ','.join(replayers)

    # Pull from the last few files until we have enough tuples
    records = []
    for key in reversed(all_keys):
        text = s3_gunzip(key)
        batch = []
        for line in text.splitlines():
            line = line.strip()
            if not line: continue
            try: batch.append(json.loads(line))
            except: pass
        records = batch + records
        if len(records) >= n * 3:   # grab extras to ensure enough scoreable ones
            break
    return records, replayer

# ── Render ────────────────────────────────────────────────────────────────────
def render(records):
    scored    = [score_tuple(r) for r in records]
    # Separate scoreable from non-scoreable for the sparkline
    scoreable = [s for s in scored if s['j'] is not None]
    window    = scoreable[-WINDOW:]  # last N scoreable tuples

    now  = datetime.datetime.now().strftime('%H:%M:%S')
    hdr  = f'  ⚡  Live Replay Quality Monitor'
    ts   = f'[{now}]'
    pad  = WIDTH - len(hdr) - len(ts) - 2
    print(BOLD + CYAN + '╔' + '═'*(WIDTH-2) + '╗' + RESET)
    print(BOLD + CYAN + '║' + RESET + hdr + ' '*pad + ts + BOLD + CYAN + '║' + RESET)
    print(BOLD + CYAN + '╚' + '═'*(WIDTH-2) + '╝' + RESET)

    # Sparkline
    print()
    js_vals = [s['j'] for s in window]
    spark   = ''.join(bar(j) for j in js_vals)
    blanks  = WINDOW - len(js_vals)
    placeholder = DIM + '░'*blanks + RESET
    label   = f'  Jaccard  {placeholder}{spark}'
    print(label)
    if js_vals:
        avg_j = sum(js_vals) / len(js_vals)
        mn, mx = min(js_vals), max(js_vals)
        col = GREEN if avg_j >= 0.95 else (YELLOW if avg_j >= 0.80 else RED)
        print(f'           {"oldest ←" + "─"*(WINDOW-14) + "→ newest":<{WINDOW}}')
        print(f'  avg {col}{BOLD}{avg_j:.3f}{RESET}   min {mn:.3f}   max {mx:.3f}   '
              f'({len(window)} of last {len(scored)} tuples scored)')
    else:
        print('  No scored tuples yet — waiting for replayer to flush...')

    # Per-tuple table (last WINDOW, excluding OPTIONS preflights)
    print()
    print(DIM + '  ' + '─'*(WIDTH-4) + RESET)
    show = [s for s in scored if s.get('j_label') != 'preflight'][-WINDOW:]
    for s in show:
        j      = s['j']
        method = (s['method'] or '?')[:6]
        uri    = s['uri'] or '?'
        if len(uri) > 38: uri = uri[:35] + '...'

        # Jaccard column
        if j is None:
            j_col = DIM + '  -   ' + RESET
        else:
            col   = GREEN if j >= 0.95 else (YELLOW if j >= 0.80 else RED)
            j_col = col + BOLD + f'{j:.3f}' + RESET

        # Hit counts (colour: green=match, yellow=close, red=diverged)
        sh = s.get('src_hits'); th = s.get('tgt_hits')
        if sh is None and th is None:
            continue
        if sh is not None and th is not None:
            if sh == th:
                hits_col = GREEN
            elif max(sh, th) > 0 and min(sh, th) / max(sh, th) >= 0.95:
                hits_col = YELLOW
            else:
                hits_col = RED
            hits_str = f'  {hits_col}{sh}→{th}{RESET}'
        else:
            hits_str = ''

        spark_c = bar(j)
        print(f'  {spark_c} {j_col}  {BOLD}{method:<6}{RESET}  {uri:<38}{hits_str}')

    print(DIM + '  ' + '─'*(WIDTH-4) + RESET)

    # Footer
    n_options = sum(1 for s in scored if s.get('j_label') == 'preflight')
    no_score  = [s for s in scored if s['j'] is None and s.get('j_label') != 'preflight']
    if n_options:
        print(f'  {DIM}{n_options}× OPTIONS preflight (skipped){RESET}')
    if no_score:
        methods = {}
        for s in no_score:
            methods[s['method']] = methods.get(s['method'], 0) + 1
        breakdown = ', '.join(f"{c}× {m}" for m, c in sorted(methods.items()))
        print(f'  {DIM}No score: {breakdown} (DELETE/root — no comparable result set){RESET}')

    print()
    print(f'  {DIM}Refreshing every {INTERVAL}s  ·  Ctrl-C to stop{RESET}')

# ── Main loop ─────────────────────────────────────────────────────────────────
while True:
    clr()
    try:
        records, _ = fetch_recent_tuples(WINDOW * 3)
        render(records)
    except Exception as e:
        print(f'\033[31mError: {e}\033[0m')
        import traceback; traceback.print_exc()
    time.sleep(INTERVAL)

PYEOF
