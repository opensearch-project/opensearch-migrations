#!/usr/bin/env bash
# =============================================================================
# 07-live-jaccard-kafka.sh
#
# Live terminal monitor — consumes from the tuple-output Kafka topic and renders
# a Jaccard sparkline in real time.  No S3 polling lag — tuples arrive within
# seconds of being replayed.  Starts from the latest offset each run (not
# --from-beginning), so restarting the script means "watch from now."
# Topic retention is capped at 10 minutes so it never grows unbounded.
#
# PREREQUISITES:
#   - migration-console-0 pod running in the ma namespace
#   - kafka-tools present at /root/kafka-tools/kafka (already on migration-console)
#   - tuple-output Kafka topic populated by the TrafficReplayer
# =============================================================================
set -euo pipefail

WINDOW="${WINDOW:-15}"
INTERVAL="${INTERVAL:-5}"   # refresh every N seconds even without new data

export WINDOW INTERVAL

# ── Locate the Kafka password ──────────────────────────────────────────────────
KAFKA_PASS=$(kubectl get secret -n ma default-migration-app \
  -o jsonpath='{.data.password}' 2>/dev/null | base64 -d)
if [ -z "$KAFKA_PASS" ]; then
  echo "ERROR: Could not read Kafka password from secret default-migration-app"
  exit 1
fi
export KAFKA_PASS

# ── Check prereq ──────────────────────────────────────────────────────────────
if ! kubectl get pod migration-console-0 -n ma &>/dev/null; then
  echo "ERROR: migration-console-0 pod not found in namespace 'ma'"
  exit 1
fi

# ── Write consumer config to migration-console ────────────────────────────────
kubectl exec -n ma migration-console-0 -- bash -c "
  mkdir -p /tmp/jaccard-kafka
  kubectl get secret -n ma default-cluster-ca-cert \
    -o jsonpath='{.data.ca\\.crt}' 2>/dev/null | base64 -d > /tmp/jaccard-kafka/ca.crt 2>/dev/null || true
" 2>/dev/null || true

# Fetch the CA cert from the k8s secret and push it to the migration-console
kubectl get secret -n ma default-cluster-ca-cert \
  -o jsonpath='{.data.ca\.crt}' | base64 -d | \
  kubectl exec -i -n ma migration-console-0 -- bash -c 'cat > /tmp/jaccard-kafka/ca.crt'

kubectl exec -n ma migration-console-0 -- bash -c "cat > /tmp/jaccard-kafka/consumer.properties << EOF
ssl.truststore.type=PEM
ssl.truststore.location=/tmp/jaccard-kafka/ca.crt
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username=\"default-migration-app\" password=\"${KAFKA_PASS}\";
EOF"

# ── Check the topic exists ──────────────────────────────────────────────────────
if ! kubectl exec -n ma migration-console-0 -- \
  /root/kafka-tools/kafka/bin/kafka-topics.sh \
  --bootstrap-server default-kafka-bootstrap.ma.svc:9093 \
  --command-config /tmp/jaccard-kafka/consumer.properties \
  --list | grep -qx 'tuple-output'; then
  echo "ERROR: Kafka topic 'tuple-output' does not exist yet."
  echo "       Run the TrafficReplayer with --tuple-kafka-topic tuple-output first" \
       "so it can produce at least one tuple (this auto-creates the topic)."
  exit 1
fi

# ── Cap topic retention so it never grows unbounded ────────────────────────────
# Nothing else prunes tuple-output; keep only the last 10 minutes of tuples.
kubectl exec -n ma migration-console-0 -- \
  /root/kafka-tools/kafka/bin/kafka-configs.sh \
  --bootstrap-server default-kafka-bootstrap.ma.svc:9093 \
  --command-config /tmp/jaccard-kafka/consumer.properties \
  --alter --entity-type topics --entity-name tuple-output \
  --add-config retention.ms=600000

trap 'printf "\033[?25h\n"; echo "Stopped."; exit 0' INT TERM
printf "\033[?25l"   # hide cursor

python3 - << PYEOF
import subprocess, json, os, sys, time, datetime, threading, collections

WINDOW   = int(os.environ.get('WINDOW', '15'))
INTERVAL = int(os.environ.get('INTERVAL', '5'))
SPARK     = '▁▂▃▄▅▆▇█'
WIDTH     = 72

# ── ANSI helpers ──────────────────────────────────────────────────────────────
def esc(code): return f'\033[{code}m'
RESET  = esc(0);  BOLD  = esc(1);  DIM  = esc(2)
GREEN  = esc(32); YELLOW = esc(33); RED = esc(31); CYAN = esc(36)
def clr(): print('\033[2J\033[H', end='')

def bar(j):
    if j is None: return DIM + '░' + RESET
    idx = min(7, int(j * 8))
    col = GREEN if j >= 0.95 else (YELLOW if j >= 0.80 else RED)
    return col + BOLD + SPARK[idx] + RESET

# ── Jaccard logic ─────────────────────────────────────────────────────────────
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
    src_body   = src_resp.get('payload', {}).get('inlinedJsonBody', {})
    tgt_body   = tgt.get('payload', {}).get('inlinedJsonBody', {})
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

# ── Kafka consumer thread ──────────────────────────────────────────────────────
# We run kafka-console-consumer.sh in a subprocess and stream lines from it.
# A shared deque holds the last N*3 scored tuples for rendering.

scored_buf = collections.deque(maxlen=WINDOW * 6)
# 'connected' flips once the consumer subprocess is launched (the topic itself was already
# confirmed to exist and be reachable in bash, above) — it does NOT wait for the first tuple.
# That keeps "still setting up" and "set up, but nothing's arrived yet" as distinct states
# instead of blending them into one "connecting…" message.
kafka_status = {'lag': 0, 'total': 0, 'errors': 0, 'running': True, 'connected': False}
buf_lock = threading.Lock()

def kafka_reader():
    cmd = [
        'kubectl', 'exec', '-n', 'ma', 'migration-console-0', '--',
        '/root/kafka-tools/kafka/bin/kafka-console-consumer.sh',
        '--bootstrap-server', 'default-kafka-bootstrap.ma.svc:9093',
        '--topic', 'tuple-output',
        '--consumer.config', '/tmp/jaccard-kafka/consumer.properties',
        '--timeout-ms', '3600000',  # keep alive for 1 hour
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            bufsize=1, text=True)
    kafka_status['connected'] = True
    try:
        for line in proc.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
            except Exception:
                kafka_status['errors'] += 1
                continue
            s = score_tuple(r)
            with buf_lock:
                scored_buf.append(s)
                kafka_status['total'] += 1
    finally:
        proc.terminate()
        kafka_status['running'] = False

t = threading.Thread(target=kafka_reader, daemon=True)
t.start()

# ── Render ────────────────────────────────────────────────────────────────────
def render():
    with buf_lock:
        buf = list(scored_buf)

    scored    = buf
    scoreable = [s for s in scored if s['j'] is not None]
    window    = scoreable[-WINDOW:]

    now  = datetime.datetime.now().strftime('%H:%M:%S')
    hdr  = f'  ⚡  Live Replay Quality Monitor (Kafka)'
    ts   = f'[{now}]'
    pad  = WIDTH - len(hdr) - len(ts) - 2
    print(BOLD + CYAN + '╔' + '═'*(WIDTH-2) + '╗' + RESET)
    print(BOLD + CYAN + '║' + RESET + hdr + ' '*max(0,pad) + ts + BOLD + CYAN + '║' + RESET)
    print(BOLD + CYAN + '╚' + '═'*(WIDTH-2) + '╝' + RESET)

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
              f'({len(window)} of {kafka_status["total"]} tuples seen)')
    elif not kafka_status['connected']:
        print('  Confirming topic tuple-output exists and is reachable…')
    else:
        note = '' if kafka_status['running'] else '  (consumer disconnected)'
        print(f'  0 tuples seen yet — waiting for live traffic on \'tuple-output\'{note}')

    # Per-tuple table
    print()
    print(DIM + '  ' + '─'*(WIDTH-4) + RESET)
    show = [s for s in scored if s.get('j_label') != 'preflight'][-WINDOW:]
    for s in show:
        j      = s['j']
        sh     = s.get('src_hits')
        th     = s.get('tgt_hits')
        if sh is None and th is None:
            continue
        method = (s['method'] or '?')[:6]
        uri    = s['uri'] or '?'
        if len(uri) > 36: uri = uri[:33] + '...'

        if j is None:
            j_col = DIM + '  -   ' + RESET
        else:
            col   = GREEN if j >= 0.95 else (YELLOW if j >= 0.80 else RED)
            j_col = col + BOLD + f'{j:.3f}' + RESET

        if sh is not None and th is not None:
            if sh == th:
                hcol = GREEN
            elif max(sh, th) > 0 and min(sh, th) / max(sh, th) >= 0.95:
                hcol = YELLOW
            else:
                hcol = RED
            hits_str = f'  {hcol}{sh}→{th}{RESET}'
        else:
            hits_str = ''

        print(f'  {bar(j)} {j_col}  {BOLD}{method:<6}{RESET}  {uri:<36}{hits_str}')

    print(DIM + '  ' + '─'*(WIDTH-4) + RESET)

    n_options = sum(1 for s in scored if s.get('j_label') == 'preflight')
    no_score  = [s for s in scored if s['j'] is None and s.get('j_label') != 'preflight']
    if n_options:
        print(f'  {DIM}{n_options}× OPTIONS preflight (skipped){RESET}')
    if no_score:
        methods = {}
        for s in no_score:
            methods[s['method']] = methods.get(s['method'], 0) + 1
        breakdown = ', '.join(f"{c}× {m}" for m, c in sorted(methods.items()))
        print(f'  {DIM}No score: {breakdown}{RESET}')

    print()
    print(f'  {DIM}Kafka: tuple-output · {kafka_status["total"]} tuples · refreshing every {INTERVAL}s · Ctrl-C to stop{RESET}')

# ── Main loop ─────────────────────────────────────────────────────────────────
import sys
sys.stdout.flush()  # ensure initial output not buffered

while True:
    clr()
    try:
        render()
    except Exception as e:
        print(f'\033[31mError: {e}\033[0m')
        import traceback; traceback.print_exc()
    sys.stdout.flush()
    time.sleep(INTERVAL)

PYEOF
