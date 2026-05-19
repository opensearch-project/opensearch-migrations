# RFC 0001: HTTP/2 Support for TrafficCapture

- **Status**: Draft
- **Authors**: andre.kurait@
- **Date**: 2026-05-18
- **Tracking issue**: TBD

---

# HTTP/2 Support for TrafficCapture: Capture Proxy + Replayer
**Low-Level Design / Implementation Plan**

> Scope: full end-to-end HTTP/2 support in the `TrafficCapture/` subtree of
> [opensearch-project/opensearch-migrations](https://github.com/opensearch-project/opensearch-migrations).
> Target audience: engineers familiar with the existing HTTP/1.1 capture/replay pipeline.
> Intent: surface every place the HTTP/1.1 assumption is baked in, present
> design alternatives, and lay out an incremental implementation roadmap.

---

## 1. TL;DR

The current pipeline is **structurally HTTP/1.1**: every layer — proxy parser,
on-the-wire protobuf schema, replayer accumulator state machine, target
connection — assumes a single, serial request → EOM → response → EOM cycle per
connection. HTTP/2 violates this at four levels simultaneously: (1) ALPN
negotiation, (2) frame multiplexing, (3) stateful HPACK header compression, and
(4) flow-control / lifecycle (RST_STREAM, GOAWAY, PUSH_PROMISE).

The cleanest path is a **minimal-parse tee at the proxy** — bytes are
forwarded between client and upstream verbatim, and a sniffer with an
HPACK *decoder* (no encoder) parses HEADERS frames just enough to make
capture and offload-blocking decisions. The capture format records
**decoded per-stream frame events**, the protobuf schema evolves
additively, and the replayer accumulator is rebuilt as a
**per-connection demultiplexer** that emits one logical request/response
pair per stream. The replayer fully terminates H2 toward its target
(generating fresh requests, not forwarding bytes), so termination logic
lives in the replayer where it belongs. The
existing JSON transformation pipeline is unaffected — H2 streams are
materialized into `HttpRequest + HttpContent` at the boundary.

Byte-exact reproduction is impossible under H2 (HPACK + flow control are
peer-stateful). We will produce **logically faithful** replay: the same method,
URI, headers (after HPACK decode), and body for each logical request, with
inter-stream order preserved by timestamp.

---

## 2. Goals & Non-goals

### Goals
- Capture proxy negotiates ALPN (h2 + http/1.1) and proxies H2 traffic from
  client to upstream cluster, recording every logical request/response.
- Capture format losslessly preserves enough state to replay each logical H2
  request, including frame ordering across streams.
- Replayer reconstructs logical requests from H2 captures and replays them to
  a target cluster, picking H2 or H1 based on ALPN negotiation with the target.
- All existing JSON transformations continue to work unchanged for H2 captures.
- Feature is gated behind `--enableHttp2` flag, off by default for one minor
  version, then default-on.

### Non-goals (v1)
- **Byte-exact** wire reproduction of H2 traffic. HPACK and per-peer SETTINGS
  make this impractical and offer little value.
- **HTTP/3 / QUIC.** Out of scope; mention only as future work.
- **h2c via prior knowledge or Upgrade.** OpenSearch endpoints are TLS in
  practice; opt-in cleartext is post-v1.
- **Server push (PUSH_PROMISE).** OpenSearch does not initiate push. We tolerate
  and discard, with a counter.
- **Stream priorities** (PRIORITY frames, RFC 7540). Capture for fidelity logs;
  replay ignores.
- Replaying a *subset* of streams from a connection while preserving HPACK
  decode of skipped streams. (Subset replay is achievable; preserving HPACK
  state requires replaying *all prior* HEADERS frames, which v1 will do
  implicitly by replaying connections whole.)

---

## 3. Current architecture (HTTP/1.1) — quick recap

A pointer map for readers; line numbers may drift.

### 3.1 Capture proxy
- `CaptureProxy.java` — entrypoint; builds `SslEngine` from PEM, no ALPN
  configured.
- `NettyScanningHttpProxy.java` — server bootstrap; per-connection child
  initializer.
- `ProxyChannelInitializer.java` — pipeline:
  ```
  [SslHandler?] → ConditionallyReliableLoggingHttpHandler → HeaderAdder/Remover → FrontsideHandler → (forward to backside Channel)
  ```
- `LoggingHttpHandler.java` — owns an internal `EmbeddedChannel(HttpRequestDecoder, SimpleDecodedHttpRequestHandler)` purely for request boundary detection; the *captured bytes* are the raw `ByteBuf` passed to `addReadEvent` / `addWriteEvent`.
- `ConditionallyReliableLoggingHttpHandler.java` — for mutating requests
  (POST/PUT/DELETE/PATCH), holds the forward to the backside until
  `flushCommitAndResetStream(false)` resolves.
- `HeaderValueFilteringCapturePredicate.java` — already drops captures matching
  `protocolPattern("HTTP/2.*")` (see `CaptureProxy.java`). This is a defensive
  guard today and becomes a feature gate later.
- `BacksideConnectionPool.java` — opens TLS to upstream with
  `useClientMode(true)`, **no ALPN advertised** → upstream always speaks H1.
- `BacksideHandler.java` — pure passthrough back to client.

### 3.2 Capture wire format
`TrafficCapture/captureProtobufs/src/main/proto/TrafficCaptureStream.proto`
defines:

```
TrafficStream {
  connectionId, nodeId, priorRequestsReceived,
  lastObservationWasUnterminatedRead,
  repeated TrafficObservation subStream,
  oneof index { number, numberOfThisLastChunk }
}

TrafficObservation = oneof {
  bind, connect, read, readSegment, requestReleasedDownstream,
  write, writeSegment, disconnect, close, connectionException,
  segmentEnd, endOfMessageIndicator{firstLineByteLength, headersByteLength},
  requestDropped
}
```

The schema describes *bytes flowing through one TCP connection*. There is no
notion of streamId, frame type, HPACK state, SETTINGS, WINDOW_UPDATE, RST_STREAM,
or GOAWAY.

### 3.3 Replayer
- `CapturedTrafficToHttpTransactionAccumulator.java` — explicit comment: *"Today
  this class expects traffic to be from HTTP/1.1 or lower."*
  Per-connection state machine `Accumulation.State`:
  ```
  WAITING_FOR_NEXT_READ_CHUNK
      → ACCUMULATING_READS  (on first Read after EOM)
      → ACCUMULATING_WRITES (on EOM after reads)
      → WAITING_FOR_NEXT_READ_CHUNK (on EOM after writes, keep-alive)
  ```
  Every read between EOMs is appended to the **single in-flight**
  `HttpMessageAndTimestamp.Request`; same for writes/response.
- `HttpMessageAndTimestamp.java` — `RawPackets` (list of byte[]); `asByteBuf()`
  is composite. No header parsing here.
- `HttpJsonTransformingConsumer.java` + `RequestPipelineOrchestrator.java` — an
  EmbeddedChannel rooted at `HttpRequestDecoder` parses the bytes back into
  `HttpRequest + HttpContent + LastHttpContent`, runs the JSON transformer over
  headers (and body, lazily on PayloadNotLoadedException), and reserializes.
- `NettyPacketToHttpConsumer.java` — opens a single TCP/TLS channel to the
  target, writes the transformed bytes, sniffs the response with
  `HttpResponseDecoder + BacksideHttpWatcherHandler`. **No ALPN, no H2 codec.**
- `ConnectionReplaySession` ↔ one source connection ↔ one target connection.
- `UniqueReplayerRequestKey = (trafficStreamKey, sourceRequestIndex)` — a
  per-connection monotonically increasing request index.

The above is the entire H1.1-shaped scaffold. Every box needs to know about H2.

---

## 3.4 Four state spaces (and why we need to name them)

The system has **four independent state spaces** for connection / stream
lifecycle. Conflating any two causes design errors.

```
┌─────────────────┐    proxy capture    ┌──────────────────┐    replayer accum    ┌─────────────────────┐    replay outbound    ┌─────────────────┐
│  source client  │═══════════════════>│  CAPTURE PROXY   │====TrafficStream===>│   REPLAYER          │═════════════════════>│  target server  │
│  (peer of S₁)   │  H2 conn / streams  │  state: S₁ + S₂  │   protobuf records  │  state: S₃          │  H2 (or H1) conn / streams │  (peer of S₄)   │
└─────────────────┘                     └──────────────────┘                     └─────────────────────┘                       └─────────────────┘
                    S₁: source-side capture       S₂: forwarded-to-source state            S₃: per-source-connection accumulation        S₄: replayer→target outbound
```

| State space | Owned by | Lifetime | Includes |
|---|---|---|---|
| **S₁: source-side capture state** | Proxy's H2 codec, source-facing | Source TCP connection | SETTINGS exchange, HPACK encoder *to* client, HPACK decoder *from* client, source-assigned streamIds, flow-control windows source ↔ proxy |
| **S₂: proxy→upstream forwarded state** | Proxy sniffer (decoder only) + upstream byte-forwarder | Upstream TCP connection | Upstream HPACK *decoder* state (for capture), no encoder. Bytes forwarded verbatim from client. SETTINGS exchanged directly between client and upstream — proxy observes only. |
| **S₃: replayer accumulator** | `H2Accumulation` per source connection | Lifetime of source connection in capture | `streamId → H2StreamAccumulation`, source-side SETTINGS for reference, GOAWAY watermark, per-stream lifecycle |
| **S₄: replayer→target outbound** | Replayer's H2 codec, target-facing | Target TCP connection (per `ConnectionReplaySession`) | Target SETTINGS, replayer's HPACK tables (independent of source), replayer-assigned streamIds (NOT the same numbers as source!) |

**Critical implications:**

- **streamId is NOT a stable identifier across boundaries.** Source streamId 7
  becomes target streamId 3 (or 11, or whatever the target H2 codec picks).
  The replayer's logical request key (`UniqueReplayerRequestKey`) tracks the
  mapping; tuple output records both.
- **HPACK state never crosses boundaries.** Each codec instance maintains its
  own. We capture *decoded* headers precisely so the replayer doesn't need a
  matching encoder.
- **SETTINGS frames are per-connection.** Capturing source SETTINGS is for
  forensics + future use; the replayer→target connection negotiates fresh.
- **Capture stream ≠ H2 stream.** `TrafficStream` is the protobuf record,
  `Http2Stream` is an H2 logical sub-channel. Naming everywhere keeps these
  distinct.

## 3.5 Multiplexing vs pipelining (in this codebase)

A frequently-conflated pair worth pinning down:

- **HTTP/1.1 keep-alive (serial)**: req1 → resp1 → req2 → resp2 on one TCP
  connection. **Supported today.** Triggers `rotateAccumulationOnReadIfNecessary`
  in `CapturedTrafficToHttpTransactionAccumulator`.
- **HTTP/1.1 pipelining**: req1, req2, req3 sent back-to-back; responses must
  return *in order* (RFC 7230). **NOT supported today** — a Read observation
  arriving while in `ACCUMULATING_WRITES` rotates the accumulator and treats
  it as a new keep-alive cycle, which works only because OpenSearch clients
  rarely pipeline. A pipelined burst would be silently miscaptured.
- **HTTP/2 multiplexing**: many concurrent streams, responses in any order.
  Stream identity is explicit (frame header `streamId`).

The new per-stream accumulation model (§8.1–8.2) is a strict superset of all
three. **As a side-effect, this work fixes pipelining support** — the same
abstract `Accumulation` with `streamId → in-flight request` map handles
H1 pipelining (treat sequential requests as virtual streams 1, 2, 3...) just
as well as H2 multiplexing.

We will *not* explicitly advertise pipelining as a v1 deliverable, but we
**will** add a regression test for it, because the architecture demands it.

## 4. Why HTTP/2 breaks the current pipeline

| Layer | HTTP/1.1 assumption | HTTP/2 reality | Impact |
|---|---|---|---|
| ALPN | Not configured; client always speaks H1 | Client offers `h2,http/1.1` | Without ALPN, H2 clients fall back to H1; capture works but cluster never sees H2. With ALPN, current pipeline corrupts. |
| Wire framing | `HttpRequestDecoder` parses ASCII request line + headers + body | Binary-framed: 24-byte preface (`PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`) + `SETTINGS` then HEADERS/DATA/...| Decoder fails on preface; capture suppressed. |
| Multiplexing | Serial: req(1), resp(1), req(2), resp(2)... | Interleaved: HEADERS(s1), HEADERS(s3), DATA(s1), HEADERS(s5), DATA(s3,END), ... | Accumulator's "ACCUMULATING_READS = current request" assumption collapses. |
| Header encoding | UTF-8 ASCII, line-delimited | HPACK with stateful per-direction dynamic table | Cannot decode HEADERS frame N without N-1; cannot drop frames mid-stream without breaking subsequent decode. |
| Connection lifecycle | TCP FIN ↔ connection close | RST_STREAM kills one stream, GOAWAY initiates orderly shutdown after lastStreamId | `connectionException` is too coarse. |
| Settings | None | `SETTINGS` exchanged at start, can update mid-connection | `SETTINGS_HEADER_TABLE_SIZE` drives HPACK; `SETTINGS_MAX_CONCURRENT_STREAMS`, `SETTINGS_INITIAL_WINDOW_SIZE` affect pacing. |
| Flow control | TCP only | Per-connection AND per-stream WINDOW_UPDATE | Replay pacing differs; not a correctness issue but observable. |
| Server-initiated | None (in OpenSearch traffic) | PUSH_PROMISE, GOAWAY, PING | Need to record + tolerate. |
| Capture-block on mutating | Hold *the whole connection's writes* until offload of "the request" | Multiple streams in flight; "the request" is one stream | Need per-stream gating, or accept connection-wide stall. |

---

## 5. Key design decisions (and the alternatives considered)

### D1. Capture model: **minimal-parse tee — pass bytes through, decode HEADERS for capture decisions only**

**Chosen.** The proxy decrypts TLS, then forwards H2 frame bytes verbatim
between client and upstream. Frame boundaries are identified at the byte
level (9-byte fixed frame header carries the length). On the client→proxy
direction, an HPACK *decoder* (no encoder) runs on HEADERS/CONTINUATION
frames to extract decoded headers for two purposes:

1. **Capture predicate**: deciding whether to capture this stream
   (`HeaderValueFilteringCapturePredicate`).
2. **Offload-blocking decision**: identifying mutating-method streams whose
   forwarding must be gated until durable capture commits.

Captured proto observations carry **decoded** headers (for replay portability
across HPACK regimes) plus the original raw frame bytes (for forensics).

**This is the same pattern the H1 capture proxy already follows today.** A
re-read of `LoggingHttpHandler.channelRead` shows it parses bytes through an
internal `EmbeddedChannel` for capture decisions but forwards the original
`ByteBuf` to the next handler unchanged via `super.channelRead`. The proxy
is *not* a terminating endpoint for H1 — it's a transparent forwarder that
parses for *decisions*. Generalizing to H2 with HPACK-aware HEADERS decoding
keeps the architecture consistent.

#### Alternatives considered

**Alt A — Full termination.** Proxy is an H2 endpoint to both peers, with two
H2 codecs (one client-facing, one upstream-facing) and frame re-encoding in
between.

**Alt B — Blind tee (decrypt only, no parse).** Proxy decrypts TLS, captures
raw frame bytes into `ReadObservation`/`WriteObservation`, and forwards
without any frame-level awareness.

|                                                | Alt A: Terminate              | **Chosen: Minimal-parse tee**           | Alt B: Blind tee              |
|------------------------------------------------|-------------------------------|----------------------------------------|-------------------------------|
| Buffering for non-gated traffic                | 2 flow-control state machines mediated, `~min(client_win, upstream_win) × streams` per conn | **Zero** (TCP backpressure does it) | Zero                          |
| Buffering for offload-gated streams            | Per-stream sub-channel buffer | **Bounded** by client per-stream window (`SETTINGS_INITIAL_WINDOW_SIZE`, default 64KB) | Cannot gate                   |
| Re-encoding cost (per frame)                   | HPACK encode + frame serialize | **None**                              | None                          |
| Retains offload-block guarantee for mutations  | ✓                             | ✓                                      | ✗                             |
| Capture-loss tolerance (lost TrafficStream)    | ✓ (decoded capture)           | ✓ (decoded capture)                   | ✗ (HPACK state breaks decoding for all subsequent HEADERS) |
| Survives extension frames (RFC 7540 §5.5)      | Must implement or reject      | ✓ Pass through                        | ✓ Pass through                |
| Survives unknown SETTINGS                      | Mostly OK (ignored)           | ✓ Pass through                        | ✓ Pass through                |
| Survives PUSH_PROMISE / ORIGIN / ALTSVC        | Must implement                | ✓ Pass through                        | ✓ Pass through                |
| Per-frame proxy overhead                       | High (decode + re-encode)     | Low (parse on HEADERS only)           | Lowest                        |
| Architectural symmetry with H1 path            | Diverges                      | **Same pattern**                      | Diverges                      |
| Replayer complexity                            | Same (decoded events)         | Same (decoded events)                 | Higher (replayer must HPACK-decode and track state) |

#### Why minimal-parse tee wins on the four criteria

1. **Reduce buffering.** Non-gated streams: zero proxy buffering — TCP
   backpressure handles slow consumers. Gated streams: bound is the
   client-advertised per-stream flow-control window, which is a
   client-side knob the user can tune via the source's H2 settings. There's
   no proxy-internal buffer that grows independently. Termination, by
   contrast, decouples the two flow-control regimes and can buffer
   unboundedly between them when peer rates differ.

2. **Reliability.** No re-encoding means no encoder bugs, no codec
   incompatibility surprises, no SETTINGS-state desyncs. A frame the proxy
   doesn't understand (extension frame, future RFC frame type, custom
   experimental SETTINGS) is forwarded unchanged. The proxy never has to
   pick between "implement this correctly" and "drop the connection."

3. **Non-impacting on the wire.** Client and upstream see each other's
   actual SETTINGS exchanges, actual stream IDs, actual flow-control
   windows. Errors and trace data attribute correctly. Termination is
   observable: a streamId in a server-side log is the *proxy*'s assigned
   number, not the client's.

4. **Weird stuff.** Extension frames (per RFC 7540 §5.5), unknown SETTINGS
   identifiers, ALTSVC, ORIGIN, draft frame types, server-initiated frames
   — all pass through without code paths. A terminating proxy must
   maintain a story for each.

#### Costs of minimal-parse tee, and how we mitigate

- **HPACK statefulness on the inbound HEADERS decoder.** The proxy must
  maintain per-direction HPACK decoder state for the client→proxy direction
  for the life of the connection. If the proxy crashes / restarts, in-flight
  connections are lost (same as today's H1 — connections don't survive proxy
  restart). Memory cost: ~4KB per connection (default `SETTINGS_HEADER_TABLE_SIZE`).
- **No HPACK encoder needed**, so no symmetric state on the outbound side.
- **The proxy doesn't observe HEADERS sent from upstream→client until
  decoded.** We *also* run an HPACK decoder on the upstream→proxy direction
  to capture response headers in decoded form. Same mechanism, different
  direction.
- **Extension frames or future frame types could in principle disguise a
  stream-creating event** (RFC 7540 §5.1.1 says only HEADERS and PUSH_PROMISE
  open streams in current spec, but the spec is extensible). Mitigation:
  the proxy treats any frame on a previously-unseen streamId as
  stream-opening for the purpose of capture lifecycle. Forwarding remains
  byte-identical regardless.

#### When termination would be required (post-v1)

- **Active traffic shaping** (rate limiting, fault injection, request
  rewriting *before* upstream sees it).
- **Independent flow-control to a different target topology** (e.g. one
  upstream H2 connection per source stream).
- **H2 → H1 protocol downgrade at the proxy** (currently we require both
  sides match; if upstream is H1-only and clients are H2, a terminating
  proxy could mediate, at the cost of all the buffering and state above).

These remain available as future work. Nothing in the v1 design forecloses
them; the minimal-parse tee handler can be replaced by a terminating one
behind the same `--enableHttp2` flag if a future requirement demands it.

### D2. Reproduction fidelity: **logical, not byte-exact**

**Chosen.** A captured H2 request becomes `(method, scheme, authority, path,
headers[], body[], trailers?)`. The replayer rebuilds it from the decoded form.

**Alternative considered:** Byte-exact replay of HEADERS frames. *Rejected*
because the source and target servers will have different HPACK dynamic-table
states; we'd have to capture and replay both sides' SETTINGS exchanges and
seed an HPACK encoder identically — expensive and brittle.

### D3. Multiplex resolution: **demux at replayer; replay each stream as a logical request**

**Chosen.** The capture preserves per-frame timestamps; the replayer demuxes
streams and emits one logical `RequestResponsePacketPair` per stream. The
existing JSON transformation pipeline runs unchanged on each pair.

**Alternative considered:** Replay frame-by-frame to a target H2 endpoint to
preserve interleaving. *Rejected* for v1 because:
1. The target is a *different* server; its scheduling/RTT make exact frame
   replay impossible anyway.
2. The tuple-comparison output (source vs target) is request-oriented today;
   keeping that unit means JSON transforms, retries, and metrics all keep
   working.

We *do* preserve **start-time order across streams** so the target sees
requests in approximately the source order.

### D4. Target wire protocol: **ALPN-negotiated; downgrade allowed**

**Chosen.** The replayer opens a target connection per ConnectionReplaySession
(as today). ALPN advertises `h2,http/1.1`. If the target picks h2, we open a
new H2 stream per logical request via `Http2StreamChannelBootstrap`. If h1, we
serialize as H1 (which is exactly what we do today). In both cases the
intermediate transformation pipeline operates on `HttpRequest + HttpContent`.

### D5. Schema evolution: **keep the H1 disk interface, extend additively**

**Chosen.** One schema, one Kafka topic, one set of consumer paths. The H1
on-disk format is preserved verbatim — every existing field tag and
semantic is locked. New `oneof` arms (`Http2FrameObservation`,
`AlpnNegotiationObservation`) are added at unused tags, and
`captureFormatVersion` + `negotiatedAlpn` are added to the `TrafficStream`
envelope.

#### Alternatives considered

**Alt B — Bifurcate (separate schema, separate topic for H2).** Cleanest
separation. *Rejected* because:
- The `TrafficStream` envelope is already protocol-agnostic
  (`connectionId`, `nodeId`, ordering, segmentation) — bifurcation
  duplicates it for no gain.
- Doubles the Kafka operational surface (two topics, two consumer groups,
  two retention policies).
- Connection IDs never cross the boundary anyway — ALPN picks one
  protocol per TCP connection — so there's no mixing benefit.

**Alt C — Unify into a single decoded-event schema (rewrite H1 too).**
Theoretically clean. *Rejected* because:
- Forces re-encoding of every existing capture on every customer's Kafka
  topic.
- Loses H1 byte-level fidelity. Today's H1 capture records the exact
  bytes the proxy saw; tuple comparison and byte-passthrough transforms
  rely on this. H2 needs decoded events because HPACK makes raw capture
  fragile, but H1 has no equivalent problem — forcing decoded-event
  capture for H1 is medicine for a disease it doesn't have.
- Premature abstraction: the two protocols are genuinely different
  framing models, and pretending otherwise will leak in unintended
  places.

#### Compatibility rules (load-bearing for "additive")

1. **No existing field tag is ever reused.** All existing tags 1-16 in
   `TrafficObservation` and 1-7 in `TrafficStream` are permanent. New
   `TrafficObservation` arms start at tag 17.
2. **No existing field semantics ever changes.** `ReadObservation.data` is
   forever "raw bytes from the client, post-TLS, pre-protocol-decode."
3. **`captureFormatVersion` is the dispatch key.**
   - Absent or `"v1"`: H1-only capture. Identical to today's format.
   - `"v2"`: capture *may* contain H2 frame observations on connections
     where ALPN picked h2. H1 connections within a v2 capture are
     byte-identical to v1.
4. **`negotiatedAlpn` on `TrafficStream`** lets readers fast-path-skip
   H2 records without scanning every observation.
5. **Old replayer + v2 capture**: today's accumulator's default branch
   already logs `"unaccounted for observation type {}"` for unknown
   `oneof` arms — it warns but doesn't crash. We harden this: on
   reading a record whose `captureFormatVersion == "v2"`, an
   H2-unaware replayer fails fast at startup with a clear error
   referencing the version mismatch. Better to fail visible than to
   silently drop H2 captures.
6. **New replayer + v1 capture**: trivially works. The new H2 oneof arms
   are simply never populated; the existing H1 dispatch path runs
   unchanged.
7. **Mixed traffic on one topic**: a v2 replayer reading a topic that
   contains both v1 records (from older proxies) and v2 records (from
   newer proxies) handles them per-record. The dispatch is on the
   `captureFormatVersion` field of each `TrafficStream`, not on the
   topic.

#### What this means operationally

- **No customer migration needed.** Existing captures keep working with
  upgraded replayers. Existing replayers keep working with H1-only
  proxies (which is what they're seeing today).
- **One Kafka topic per cluster, regardless of how many H2 connections
  pass through.** Partitioning still by `connectionId`. Capacity planning
  is unchanged.
- **The TrafficStream envelope is reusable for future H3.** Whatever H3
  brings, it gets a new oneof arm at tag 19+ and a new
  `captureFormatVersion` value. Same envelope, same topic, same dispatch
  pattern.

### D6. Per-stream offload-blocking: **scoped to the offending stream; rest of connection flows freely**

**Chosen.** When a HEADERS+END_STREAM (or HEADERS+END_HEADERS pending body) is
identified as mutating, the per-stream gate handler holds *the upstream
forwarding* of that stream's frames until `flushCommitAndResetStream` resolves.
Other streams on the same connection continue.

**Alternative considered:** Connection-wide block (cheaper). *Rejected* because
H2 connections multiplex unrelated requests; one slow Kafka offload would
deadlock dozens of GETs.

### D7. Capture suppression on H2: **flip the sense of the existing predicate**

Today `CaptureProxy.java` builds the predicate with
`protocolPattern("HTTP/2.*")` to *drop* H2. We:
1. Remove that pattern when `--enableHttp2` is set.
2. Keep the protocol pattern as a general-purpose suppression knob for users
   who want to drop one protocol.

### D8. **No protocol translation in the proxy** (H2↔H1 cross-side translation is out of scope)

**Chosen.** The proxy requires ALPN to match between client side and upstream
side. If clients want h2 and upstream is H1-only, the proxy doesn't advertise
h2 to clients (startup probe of upstream determines what the proxy offers).
Clients transparently fall back to h1. No translation occurs.

**Why explicit.** The minimal-parse tee from D1 is only viable because the
proxy has no H2 encoder. Adding cross-protocol translation requires a full H2
codec instance, which reintroduces every cost the tee model eliminates:
unbounded buffering between independent flow-control regimes, encoder
state desync risk, re-encoding cost per frame, code paths for extension
frames, stream-ID remapping, and architectural asymmetry with the H1 path.

**Why acceptable.** The deployment matrix doesn't need translation:

| Scenario | Source supports | Client wants | Proxy behavior |
|---|---|---|---|
| Modern matched | h1 + h2 | h2 | Advertise h2 to client; tee. ✓ |
| Modern matched | h1 + h2 | h1 | Advertise both; client picks h1; tee. ✓ |
| Legacy matched | h1 | h1 | Advertise h1 only; tee H1. ✓ |
| Legacy with H2-eager client | h1 | h2 | Probe says h1; advertise h1; client falls back to h1; tee H1. ✓ |
| Misconfigured | h1 (probed) | h2 (admin set `--enableHttp2`) | Startup error: "upstream does not support h2; remove `--enableHttp2`." ✓ Fail-fast at config time, not runtime. |

The "H2-eager client wants h2 to a legacy upstream" case is an API gateway
problem, not a capture proxy problem. Customers who genuinely need that
should put a translating gateway in front of the capture proxy.

**Escape hatch preserved.** The H2 forwarding handler in §7.3 is a
swappable component. If a future requirement demands proxy translation,
add an opt-in `--terminateHttp2` flag that replaces the sniffer with a
full H2 endpoint pair. v1 ships sniffer-only; that work is not done now
and not foreclosed.

**Replayer unaffected.** H2↔H1 *replay* works end-to-end because the
replayer materializes logical requests (`H2ToH1ObjectAdapter` in §8.4) —
its boundary is request-shaped, not byte-shaped. A capture taken as H2
replays cleanly to an H1 target and vice versa, regardless of what
the proxy did.

---

## 6. Schema evolution (`TrafficCaptureStream.proto`)

Additive changes. Existing fields untouched. Reserved tags are honored; new
tags are 17+ to leave room.

```protobuf
syntax = "proto3";
import "google/protobuf/timestamp.proto";

option java_multiple_files = true;
option java_package = "org.opensearch.migrations.trafficcapture.protos";
option java_outer_classname = "TrafficCapture";

// ---------- existing observations unchanged ----------
message ReadObservation { bytes data = 1; }
message WriteObservation { bytes data = 1; }
// ... etc ...

// ---------- new: H2-specific observations ----------

// One H2 frame as decoded by Netty's Http2FrameCodec.
// `rawFrame` is the original 9-byte-header + payload bytes for forensics.
// `streamId` of 0 = connection-scoped (SETTINGS, PING, GOAWAY, WINDOW_UPDATE@conn).
message Http2FrameObservation {
  uint32 streamId = 1;
  Http2FrameType type = 2;
  uint32 flags = 3;
  bytes rawFrame = 4;          // for binary fidelity / forensic replay
  oneof payload {
    Http2HeadersPayload headers = 5;        // HPACK-decoded
    Http2DataPayload data = 6;
    Http2SettingsPayload settings = 7;
    Http2WindowUpdatePayload windowUpdate = 8;
    Http2RstStreamPayload rstStream = 9;
    Http2GoAwayPayload goAway = 10;
    Http2PingPayload ping = 11;
    Http2PushPromisePayload pushPromise = 12;
    Http2PriorityPayload priority = 13;     // recorded, not replayed
    Http2ContinuationPayload continuation = 14; // typically merged into headers
  }
  bool truncated = 15;          // set when frame exceeded TrafficStream buffer; payload empty
}

enum Http2FrameType {
  H2_DATA = 0; H2_HEADERS = 1; H2_PRIORITY = 2; H2_RST_STREAM = 3;
  H2_SETTINGS = 4; H2_PUSH_PROMISE = 5; H2_PING = 6; H2_GOAWAY = 7;
  H2_WINDOW_UPDATE = 8; H2_CONTINUATION = 9;
}

message Http2HeaderField { bytes name = 1; bytes value = 2; bool sensitive = 3; }
message Http2HeadersPayload {
  repeated Http2HeaderField fields = 1;     // already HPACK-decoded
  bool endStream = 2;
  bool endHeaders = 3;
  uint32 dependsOnStreamId = 4;             // optional; PRIORITY embedded in HEADERS
  uint32 weight = 5;
  bool exclusive = 6;
}
message Http2DataPayload { bytes data = 1; bool endStream = 2; uint32 padLength = 3; }
message Http2SettingsPayload {
  bool ack = 1;
  map<uint32, uint32> settings = 2;         // setting id → value
}
message Http2WindowUpdatePayload { uint32 increment = 1; }
message Http2RstStreamPayload   { uint32 errorCode = 1; }
message Http2GoAwayPayload      { uint32 lastStreamId = 1; uint32 errorCode = 2; bytes debugData = 3; }
message Http2PingPayload        { bytes opaqueData = 1; bool ack = 2; }
message Http2PushPromisePayload { uint32 promisedStreamId = 1; repeated Http2HeaderField fields = 2; }
message Http2PriorityPayload    { uint32 dependsOnStreamId = 1; uint32 weight = 2; bool exclusive = 3; }
message Http2ContinuationPayload{ bytes headerBlockFragment = 1; bool endHeaders = 2; }

// ---------- new: per-connection metadata ----------
message AlpnNegotiationObservation {
  string negotiatedProtocol = 1;            // "h2", "http/1.1", ""
  string offeredByClient = 2;               // raw advertised list
}

// ---------- TrafficObservation: add new oneof arms ----------
message TrafficObservation {
  google.protobuf.Timestamp ts = 1;
  oneof Capture {
    // ... existing 1-16 ...
    Http2FrameObservation       http2Frame       = 17;
    AlpnNegotiationObservation  alpn             = 18;
  }
}

// ---------- TrafficStream: add format version + alpn ----------
message TrafficStream {
  // ... existing 1-7 ...
  string captureFormatVersion = 8;          // "v1" (h1) or "v2" (h1+h2 capable)
  string negotiatedAlpn       = 9;          // duplicated for fast filtering
}
```

**Why decoded headers, not raw header-block fragments**: HPACK is per-direction
stateful and only the proxy holds the right state at capture time. Persisting
decoded headers means readers don't need an HPACK decoder seeded with the
exact source SETTINGS exchange.

**Why both decoded and `rawFrame`**: in the unlikely event we discover a
decoder bug, the raw frame bytes let us reanalyze offline.

**Frame ordering invariant**: the order of `TrafficObservation` entries in
`subStream` is the order in which the proxy observed frames on the connection.
The replayer relies on this for inter-stream causality.

## 6.1 Capture-listener API additions

New default methods on `IChannelConnectionCaptureListener<T>`
(`captureOffloader/.../IChannelConnectionCaptureListener.java`). All default
to no-op so existing implementations compile unchanged.

```java
public interface IChannelConnectionCaptureListener<T> {
    // ---- existing methods unchanged ----

    /**
     * Called once per connection after the TLS handshake completes and ALPN
     * has selected a protocol. Emit BEFORE any frame observation. The
     * negotiatedProtocol value is the ALPN string ("h2", "http/1.1") or
     * empty string if no ALPN was negotiated. offeredByClient is the raw
     * comma-separated client advertisement, for forensic use.
     */
    default void addAlpnNegotiatedEvent(
            Instant timestamp,
            String negotiatedProtocol,
            String offeredByClient) throws IOException {}

    /**
     * Called once per H2 frame observed in the read direction (client → proxy).
     * The decoded payload is one of the Http2*Payload variants. rawFrame is
     * the original 9-byte-header-plus-payload bytes (a defensive copy MUST be
     * taken if the implementation retains it past the call).
     *
     * For HEADERS spanning HEADERS+CONTINUATION+...+CONTINUATION: callers
     * SHOULD coalesce into a single addH2FrameRead with payload = headers
     * (with the merged header block) and emit each CONTINUATION's rawFrame
     * separately as its own observation for forensic completeness.
     */
    default void addH2FrameRead(
            Instant timestamp,
            int streamId,
            Http2FrameType type,
            int flags,
            ByteBuf rawFrame,
            Http2FramePayload payload) throws IOException {}

    /** Symmetric to addH2FrameRead, for the write direction (upstream → client → proxy → client). */
    default void addH2FrameWrite(
            Instant timestamp,
            int streamId,
            Http2FrameType type,
            int flags,
            ByteBuf rawFrame,
            Http2FramePayload payload) throws IOException {}
}
```

`Http2FramePayload` is a sealed Java interface with one implementation per
proto payload variant:

```java
sealed interface Http2FramePayload
        permits Http2HeadersPayloadView, Http2DataPayloadView,
                Http2SettingsPayloadView, Http2WindowUpdatePayloadView,
                Http2RstStreamPayloadView, Http2GoAwayPayloadView,
                Http2PingPayloadView, Http2PushPromisePayloadView,
                Http2PriorityPayloadView, Http2ContinuationPayloadView {}
```

The `*View` types are thin wrappers around Netty's `Http2Frame` subclasses
(no copy at the listener boundary; copy happens inside the serializer
when writing protobuf bytes). This keeps the listener API zero-allocation
in the hot path.

`StreamChannelConnectionCaptureSerializer` (the only non-trivial
implementation) overrides each new method to produce the proto observation
via `CodedOutputStream`. Sizing logic in `CodedOutputStreamSizeUtil`
mirrors the existing `ReadObservation` sizing pattern: predict required
bytes, rotate `TrafficStream` if it won't fit.

### 6.1.1 Frame-spanning rule in serializer

Today, oversized `Read`/`Write` are split via `ReadSegment`/`WriteSegment`
+ `EndOfSegmentsIndication`. We deliberately **do not segment H2 frames**
— if a frame won't fit in the current `TrafficStream`, the serializer
rotates to a fresh stream and writes the whole frame there. Rationale: H2
frames are bounded by `SETTINGS_MAX_FRAME_SIZE` (default 16KB, max 16MB);
the per-`TrafficStream` budget (configurable, default 1MB) is comfortably
larger than any reasonable frame. Forcing whole-frame atomicity simplifies
the replayer.

If a single frame exceeds the configured `--maxTrafficBufferSize`, the
serializer logs a warning and emits a `Http2FrameObservation` with
`rawFrame` set but `payload` cleared, marking it `truncated=true` (new
field on `Http2FrameObservation`, tag 15). This is a degraded-mode
observation; a v2 replayer treats it as `RECONSTRUCTION_STATUS.TRUNCATED`
for the affected stream.

---

## 7. Capture proxy — detailed design

### 7.1 ALPN negotiation

`CaptureProxy.loadSslEngineFromPem` and `buildSslEngineSupplier`:

```java
var ctx = SslContextBuilder.forServer(certChain, key)
    .trustManager(...)
    .clientAuth(...)
    .applicationProtocolConfig(new ApplicationProtocolConfig(
        ApplicationProtocolConfig.Protocol.ALPN,
        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
        params.enableHttp2
            ? List.of(ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1)
            : List.of(ApplicationProtocolNames.HTTP_1_1)))
    .build();
```

A new CLI flag:

```
--enableHttp2          (default: false)
--http2MaxConcurrentStreams (default: 100)
--http2InitialWindowSize    (default: 65535)
```

### 7.2 ProxyChannelInitializer with protocol negotiation

Replace the static pipeline with a deferred initializer:

```java
@Override
protected void initChannel(SocketChannel ch) {
    if (sslEngineProvider != null) {
        ch.pipeline().addLast(new SslHandler(sslEngineProvider.get()));
    }
    ch.pipeline().addLast(new ApplicationProtocolNegotiationHandler(
            ApplicationProtocolNames.HTTP_1_1) {
        @Override
        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
            // Emit ALPN observation BEFORE first frame.
            connectionCaptureFactory.recordAlpn(connectionId, protocol);

            switch (protocol) {
                case ApplicationProtocolNames.HTTP_2:
                    configureH2Pipeline(ctx);
                    break;
                case ApplicationProtocolNames.HTTP_1_1:
                default:
                    configureH1Pipeline(ctx);  // existing logic
                    break;
            }
        }
    });
}
```

### 7.3 H2 pipeline (minimal-parse tee)

The proxy runs **byte forwarding** between client and upstream, plus a
**frame sniffer** with an HPACK decoder for capture decisions. There is no
H2 codec on the forwarding path — frames are parsed only enough to identify
boundaries, types, and (for HEADERS) decoded fields.

```
client side                                            upstream side
─────────────                                          ─────────────
SslHandler                                             SslHandler  (in BacksideConnectionPool)
ApplicationProtocolNegotiationHandler (one-shot)       (no client-side ALPN handler; we already
                                                        know what we negotiated)
H2FrameSnifferHandler   ────tap────►  capture proto    H2FrameSnifferHandler   ────tap────►  capture proto
   │   identifies frame boundaries (9-byte header)        │  identifies frame boundaries
   │   runs HPACK decoder on HEADERS/CONTINUATION         │  runs HPACK decoder on HEADERS/CONTINUATION
   │   emits Http2FrameObservation per frame              │  emits Http2FrameObservation per frame
   │                                                       │
   │   for each HEADERS:                                   │
   │     if shouldGate(method) → tell PerStreamGate        │
   │                                                       │
   ▼                                                       ▼
PerStreamGateHandler  ◄──────────────────────────►  FrontsideHandler / BacksideHandler
   buffers DATA frames per gated stream until              passthrough (existing logic, unchanged)
   capture-commit signal arrives. Frames for non-
   gated streams pass through unchanged.

Bytes emitted to peer are the **original received bytes**, never re-encoded.
```

`H2FrameSnifferHandler` is the analogue of today's `LoggingHttpHandler`:
parses for decisions, forwards bytes verbatim.

#### Frame sniffer responsibilities

1. **Frame chunking from a `ByteBuf` stream.** A read may deliver partial or
   multiple frames. Maintain a small parsing FSM driven by the 9-byte frame
   header (`length:24, type:8, flags:8, R:1, streamId:31`).
2. **Per-frame slicing.** For each complete frame, slice the `ByteBuf` and
   construct an `Http2FrameObservation`. The slice is forwarded *unmodified*
   to the next handler (passthrough); the slice contents are also captured.
3. **HPACK decode for HEADERS / CONTINUATION.** Maintain one
   `io.netty.handler.codec.http2.HpackDecoder` per direction per connection.
   Coalesce CONTINUATION fragments per RFC 7540 §6.10. Once the header
   block is complete, populate `Http2HeadersPayload.fields[]` in the
   observation.
4. **24-byte connection preface.** On the client→proxy direction, the first
   24 bytes (`PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`) are recognized and forwarded;
   no observation is emitted (or emitted as a sentinel). HPACK state begins
   after the preface.
5. **Stream-creation tracking.** A streamId never previously seen from this
   peer creates a `liveStreamId` registry entry (used by the gate handler).
6. **SETTINGS tracking.** The proxy doesn't *enforce* SETTINGS (the peers do
   that with each other directly), but it must observe them to keep its
   capture HPACK decoders correct. Specifically, when the *receiver* of a
   direction sends `SETTINGS_HEADER_TABLE_SIZE`, the sender's HPACK encoder
   may resize on the next header block. Our decoder for that direction must
   honor the size update. Netty's `HpackDecoder.setMaxHeaderTableSize` is
   called when we see SETTINGS pass through. SETTINGS frames themselves are
   forwarded verbatim — we observe but do not modify.
7. **Forwarding.** Every parsed slice is `ctx.fireChannelRead(slice)` to the
   gate handler. Bytes not yet forming a complete frame stay buffered (Netty
   `ByteBuf` cumulator pattern).

#### Sniffer skeleton (per direction)

```java
public final class H2FrameSnifferHandler extends ByteToMessageDecoder {
    enum State { AWAITING_PREFACE, AWAITING_FRAME_HEADER, AWAITING_FRAME_PAYLOAD }

    private final HpackDecoder hpackDecoder;             // Netty public class
    private final IChannelConnectionCaptureSerializer<?> capture;
    private final boolean isClientToProxyDirection;       // true = inbound from client; false = response side
    private final BiConsumer<Integer, Http2Headers> onHeadersForGating; // null on response side

    // CONTINUATION coalescing
    private int pendingHeaderStreamId = -1;
    private CompositeByteBuf pendingHeaderBlock = null;

    private State state = State.AWAITING_PREFACE;
    private int frameLength;
    private int frameType;
    private int frameFlags;
    private int frameStreamId;

    public H2FrameSnifferHandler(IChannelConnectionCaptureSerializer<?> capture,
                                 boolean isClientToProxyDirection,
                                 BiConsumer<Integer, Http2Headers> onHeadersForGating,
                                 long maxHeaderListSize, long maxHeaderTableSize) {
        this.capture = capture;
        this.isClientToProxyDirection = isClientToProxyDirection;
        this.onHeadersForGating = onHeadersForGating;
        this.hpackDecoder = new HpackDecoder(maxHeaderListSize);  // configurable cap
        this.hpackDecoder.setMaxHeaderTableSize(maxHeaderTableSize);
        // On response side, there's never a preface from upstream → start past it
        if (!isClientToProxyDirection) state = State.AWAITING_FRAME_HEADER;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // Single readerIndex marker so we forward exactly the bytes we consume
        int forwardStart = in.readerIndex();
        try {
            while (true) {
                if (state == State.AWAITING_PREFACE) {
                    if (in.readableBytes() < 24) return;             // wait for more
                    if (!matchesPreface(in)) {
                        ctx.fireExceptionCaught(new H2ProtocolError("bad preface"));
                        return;
                    }
                    in.skipBytes(24);
                    state = State.AWAITING_FRAME_HEADER;
                    // Preface bytes are forwarded as part of the slice below.
                }
                if (state == State.AWAITING_FRAME_HEADER) {
                    if (in.readableBytes() < 9) break;
                    int idx = in.readerIndex();
                    frameLength  = in.getUnsignedMedium(idx);
                    frameType    = in.getByte(idx + 3) & 0xff;
                    frameFlags   = in.getByte(idx + 4) & 0xff;
                    frameStreamId = in.getInt(idx + 5) & 0x7fffffff;
                    state = State.AWAITING_FRAME_PAYLOAD;
                }
                if (state == State.AWAITING_FRAME_PAYLOAD) {
                    if (in.readableBytes() < 9 + frameLength) break;
                    ByteBuf rawFrame = in.readRetainedSlice(9 + frameLength);
                    try {
                        processFrame(rawFrame);                       // emit observations
                    } finally {
                        rawFrame.release();
                    }
                    state = State.AWAITING_FRAME_HEADER;
                }
            }
        } finally {
            // Forward exactly the bytes we consumed in this call
            int forwardLen = in.readerIndex() - forwardStart;
            if (forwardLen > 0) {
                ByteBuf forwarded = in.retainedSlice(forwardStart, forwardLen);
                out.add(forwarded);
            }
        }
    }

    private void processFrame(ByteBuf rawFrame) {
        // SETTINGS hook — track HEADER_TABLE_SIZE updates (RFC 7541 §6.3)
        if (frameType == FRAME_TYPE_SETTINGS && (frameFlags & FLAG_ACK) == 0) {
            int newSize = parseSettingsForHeaderTableSize(rawFrame);
            if (newSize >= 0) hpackDecoder.setMaxHeaderTableSize(newSize);
        }

        // HEADERS / CONTINUATION → coalesce, then HPACK-decode
        Http2Headers decoded = null;
        if (frameType == FRAME_TYPE_HEADERS || frameType == FRAME_TYPE_CONTINUATION) {
            decoded = coalesceAndMaybeDecode(rawFrame);
        }

        // Emit observation
        capture.addH2FrameRead(  // or addH2FrameWrite based on direction
            Instant.now(), frameStreamId, frameType, frameFlags, rawFrame, payloadView(decoded, rawFrame));

        // Gate signal (HEADERS only, only on client→proxy direction, only when fully decoded)
        if (decoded != null && onHeadersForGating != null && isEndOfHeaderBlock()) {
            onHeadersForGating.accept(frameStreamId, decoded);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (pendingHeaderBlock != null) pendingHeaderBlock.release();
        // hpackDecoder has no explicit close; tracked-table memory is GC'd
    }
}
```

#### ByteBuf ownership rules (load-bearing)

- `ByteToMessageDecoder` owns the cumulator. We use `readRetainedSlice` to
  get a slice *for capture*, then release it in a try/finally.
- The bytes *forwarded downstream* are a separate `retainedSlice` that
  goes into the `out` list. `ByteToMessageDecoder` releases that for us
  once the next handler consumes it.
- The capture serializer either copies bytes immediately (proto write) or
  retains them with its own ref count if buffering. The slice the sniffer
  passes to it is "borrowed" — valid only during the call.
- This double-slicing pattern is intentional: it keeps capture and
  forwarding lifetimes independent.

#### HPACK decoder lifecycle

- **Created** once per direction in the handler constructor when the
  pipeline is built (after ALPN selects h2).
- **Configured** with `--http2MaxHeaderListSize` (default 8KB) and
  `--http2MaxHeaderTableSize` (default 4KB; RFC 7541 default).
- **Resized** via `setMaxHeaderTableSize` when a SETTINGS frame from the
  *receiver* of the direction acknowledges a new value (the encoder on
  the other side will resize before the next HEADERS — we mirror that).
- **Disposed** implicitly via GC when the handler is removed. No explicit
  close; Netty's HpackDecoder holds only Java refs.
- **One per direction per connection.** Two decoders total per H2
  connection (client→proxy and upstream→proxy directions). Memory:
  `≤ maxHeaderTableSize × 2 ≈ 8KB` per connection at default sizes.

#### Connection close mid-frame

If the TLS layer signals close (or close-notify) while
`state != AWAITING_FRAME_HEADER`, we have an incomplete frame in the
cumulator. Behavior:

- Emit a `ConnectionExceptionObservation` with message `"truncated H2
  frame at close: expected_bytes=N, got=M"`.
- Release the cumulator buffer.
- Allow normal H1-style close handling to proceed (the existing
  `channelUnregistered` logic in the sniffer's parent already handles
  capture commit).

The replayer treats a connection that ended mid-frame as
`RECONSTRUCTION_STATUS.EXPIRED_PREMATURELY` — same status used today for
H1 connections that ended mid-request.

#### Capture predicate on H2

Today's `RequestCapturePredicate` is `Function<HttpRequest, CaptureDirective>`.
Generalize to:

```java
public interface ICaptureDecisionPolicy {
    CaptureDirective forH1Request(HttpRequest req);
    CaptureDirective forH2Stream(int streamId, Http2Headers headers);
}
```

Default `HeaderValueFilteringCapturePredicate` evaluates against the
`:method`, `:path`, `:scheme`, `:authority` pseudo-headers and the regular
header set.

### 7.4 Per-stream offload-blocking (D6)

`PerStreamGateHandler` sits between the sniffer and the byte-forwarding
handler. Per stream state machine:

```
NEW → DECISION_PENDING → FORWARDING (open or gated)
                       → FLUSH_COMMITTED (mutation) → FORWARDING
```

On HEADERS for stream N:
- If `shouldGuaranteeMessageOffloading(method)` is true, mark stream N
  `GATED`. The HEADERS frame itself is **also held**, not just DATA — the
  upstream must not see the request at all until capture commits.
- All subsequent inbound frames *for stream N* are queued until
  `flushCommitAndResetStream(false)` resolves. Frames for other streams pass
  through.
- On commit, drain the queue in order, forwarding original bytes.
- If offload fails, today's behavior (log + forward) applies; we drain the
  queue anyway.

#### Buffering bound

Per gated stream: at most `SETTINGS_INITIAL_WINDOW_SIZE` of DATA can arrive
before the client must wait for a `WINDOW_UPDATE` from the proxy. **The
proxy must not forward `WINDOW_UPDATE` for a gated stream until commit** —
this is what stops the client from sending more, capping per-stream
buffering at ~64KB by default. (The proxy still forwards
connection-level `WINDOW_UPDATE` from upstream to client unmodified, so
non-gated streams aren't starved.)

This is the **single piece of frame-aware behavior on the outbound
direction** in the entire proxy. Everything else is byte passthrough.

#### What about HEADERS spanning multiple frames (CONTINUATION)?

CONTINUATION-following-HEADERS is rare with modern clients (Netty default
emits one HEADERS frame). If it occurs, the gate must hold *all* fragments
together — RFC 7540 §6.10 forbids interleaving anything between HEADERS and
its CONTINUATIONs on the same stream. The sniffer naturally enforces this
because it can't decode the header block until the last CONTINUATION;
holding until decode is straightforward.

### 7.5 Backside (upstream) ALPN — strict transparency

`BacksideConnectionPool.buildConnectionFuture` adds ALPN client-side:

```java
var sslCtx = SslContextBuilder.forClient()
    .applicationProtocolConfig(new ApplicationProtocolConfig(
        Protocol.ALPN, ...,
        params.enableHttp2 ? List.of(HTTP_2, HTTP_1_1) : List.of(HTTP_1_1)))
    .build();
```

After the handshake the SSL handler's `applicationProtocol()` tells us what
the upstream picked.

**Critical: the proxy does not translate between H1 and H2.** With
minimal-parse tee, the proxy cannot frame-translate — it has no encoder.
Therefore:

| Client ALPN | Upstream ALPN | Behavior |
|---|---|---|
| h2 | h2 | ✓ H2 sniffer pipelines on both sides |
| http/1.1 | http/1.1 | ✓ existing H1 pipeline |
| h2 | http/1.1 (upstream doesn't speak h2) | ✗ fail-closed: client gets connection error before any frame is sent. Surface a clear error referencing `--enableHttp2` and the upstream's advertised protocols. |
| http/1.1 | h2 (upstream offers h2 but client picked h1) | ✓ H1 pipeline both sides — upstream selected http/1.1 because that's what we offered |

**Mitigation for the failure case**: the proxy probes upstream ALPN at
startup (`BacksideConnectionPool.buildConnectionFuture` already opens a
connection on initialization) and refuses to advertise h2 to clients if the
upstream doesn't support it. This downgrades from "client gets weird errors"
to "client just doesn't see h2 offered" — which is the right behavior for a
transparent proxy.

This is strictly more conservative than the previous design's "translate at
the framing layer," and it's the right tradeoff: protocol translation is a
non-goal for a *capture* proxy. If a deployment actually needs H2-to-H1
translation, that's a separate component (a proper API gateway) that lives
in front of this proxy.

### 7.6 Multi-record streaming for H2

Today, `OrderedStreamLifecyleManager` rolls a `TrafficStream` to a new
sequence number when its protobuf buffer fills. For H2 this is unchanged
**but with one new invariant**: a `TrafficStream` MAY split between any two
`TrafficObservation` boundaries, but MUST NOT split a single
`Http2FrameObservation`. The serializer ensures this with a
"reserve-bytes-or-rotate-stream" call before writing each frame, mirroring
the existing logic for `ReadObservation`.

### 7.7 Counters / observability

New OTel attributes on `IRootWireLoggingContext`:
- `h2.streams.opened`, `h2.streams.closed.normal`, `h2.streams.reset`
- `h2.frames.{headers,data,settings,...}.{in,out}`
- `h2.alpn.negotiated{h2|http/1.1|none}`
- `h2.offload.block_duration_ms` (per stream, histogram)

---

## 8. Replayer — detailed design

### 8.1 Accumulator: connection state + per-stream demux

Currently `Accumulation` is `(state, RequestResponsePair, ...)`. Refactor to:

```java
abstract class Accumulation { /* common: trafficChannelKey, isResumed, etc. */ }

class H1Accumulation extends Accumulation {
    State state;                                  // unchanged from today
    RequestResponsePacketPair currentPair;
    /* existing fields */
}

class H2Accumulation extends Accumulation {
    Http2SettingsPayload clientSettings;          // captured from preface
    Http2SettingsPayload serverSettings;
    Map<Integer, H2StreamAccumulation> liveStreams;
    Deque<H2StreamAccumulation> closedStreamsAwaitingCallback;
    int lastClientStreamId;                       // odd; from HEADERS
    int lastServerStreamId;                       // even; PUSH_PROMISE only
}

class H2StreamAccumulation {
    int streamId;
    State state;                                  // RECEIVING_HEADERS, BODY, AWAITING_RESPONSE, RECEIVING_RESPONSE_HEADERS, RESPONSE_BODY, CLOSED
    HttpJsonRequestWithFaultingPayload requestHeaders;
    List<ByteBuf> requestBody;
    HttpJsonResponseWithFaultingPayload responseHeaders;
    List<ByteBuf> responseBody;
    Instant requestFirstFrameTs;
    Instant responseFirstFrameTs;
    boolean clientEndStream;
    boolean serverEndStream;
    Optional<Http2Error> resetReason;             // RST_STREAM
}
```

The accumulator gains a dispatch:

```java
public CONNECTION_STATUS addObservationToAccumulation(
        Accumulation accum, ITrafficStreamKey key, TrafficObservation o) {
    if (accum instanceof H1Accumulation) {
        return addH1Observation((H1Accumulation) accum, key, o);   // existing logic
    }
    if (accum instanceof H2Accumulation) {
        return addH2Observation((H2Accumulation) accum, key, o);
    }
    throw new IllegalStateException("unknown accumulation subclass");
}
```

#### Factory: H1 vs H2 selection

The decision happens in `createInitialAccumulation`, which today always
returns the H1-shaped class. The new logic:

```java
private Accumulation createInitialAccumulation(ITrafficStreamWithKey swk) {
    var stream = swk.getStream();
    var key    = swk.getKey();

    AccumulationProtocol protocol = decideProtocol(stream);
    return switch (protocol) {
        case H1 -> new H1Accumulation(key, stream, swk.isResumedConnection());
        case H2 -> new H2Accumulation(key, stream, swk.isResumedConnection());
    };
}

private AccumulationProtocol decideProtocol(TrafficStream stream) {
    // Priority 1: trust the explicit envelope field (set by v2 capture).
    String alpn = stream.getNegotiatedAlpn();
    if ("h2".equals(alpn))                return AccumulationProtocol.H2;
    if ("http/1.1".equals(alpn))          return AccumulationProtocol.H1;

    // Priority 2: format version says v1 — must be H1.
    if ("v1".equals(stream.getCaptureFormatVersion())
            || stream.getCaptureFormatVersion().isEmpty()) {
        return AccumulationProtocol.H1;
    }

    // Priority 3: v2 capture without explicit ALPN — sniff the substream.
    // Search the first ~16 observations for an ALPN observation OR an H2
    // frame observation. If we see either, classify as H2; if we see
    // Read/Write observations first, classify as H1. (ALPN observation is
    // emitted before any frame, so this is deterministic when the
    // accumulator sees the start of the connection.)
    return sniffProtocolFromObservations(stream);
}
```

#### Mid-stream resume (Kafka rebalance)

When `TrafficSourceReaderInterruptedClose` causes the previous accumulation
to be torn down and a new partition assignment delivers `TrafficStream`
records starting mid-connection, the new accumulator instance has *no*
ALPN observation in the first record (that one was in an earlier
`TrafficStream`).

Resolution: the proxy serializer **stamps every `TrafficStream` envelope
with `negotiatedAlpn`** for the connection's lifetime — not just the
first one. Schema (`TrafficStream` field 9) already supports this. So
mid-stream resume reads the protocol off the envelope without needing to
sniff.

If `negotiatedAlpn` is empty on a v2 record (shouldn't happen, but
defensive): fall back to substream sniffing. If that also fails (no
recognizable observations in the partial record), default to H1 and log
a warning — H1 is the safer assumption because the dispatch into H1
observation handlers will simply discard unknown observation types.

#### Why this matters for replayer correctness

Two specific concerns:

1. **Connection ID reuse.** A v2 `TrafficStream` for the same
   `connectionId` should always have the same `negotiatedAlpn` across
   all its records. Validation: on subsequent records for an existing
   accumulation, check that `negotiatedAlpn` matches; mismatch is an
   error condition (capture corruption) — log and discard the record.
2. **Concurrent generations.** The existing
   `existingAccum.sourceGeneration < tsk.getSourceGeneration()` defensive
   check (line ~308 in `CapturedTrafficToHttpTransactionAccumulator`) is
   protocol-agnostic; it just discards stale accumulations. No change
   needed.

### 8.2 Per-stream lifecycle in the accumulator

For each `Http2FrameObservation`:

| Frame type | Effect on stream state |
|---|---|
| `HEADERS` (client→server) | If new stream: create `H2StreamAccumulation`, set `requestHeaders`. If `endStream`: emit `onRequestReceived` → state = AWAITING_RESPONSE. If trailers (state was BODY): finalize body, emit. |
| `DATA` (client→server) | Append to `requestBody`. If `endStream`: emit. |
| `HEADERS` (server→client) | Set `responseHeaders`. If `endStream`: complete and fire callback. |
| `DATA` (server→client) | Append to `responseBody`. If `endStream`: complete and fire callback. |
| `RST_STREAM` | Mark stream cancelled; if request was already sent, fire callback with `RECONSTRUCTION_STATUS = RESET_BY_PEER`. |
| `SETTINGS` | Update `clientSettings` / `serverSettings`; relevant for HPACK in case we ever re-encode (we don't in v1). |
| `WINDOW_UPDATE`, `PING`, `PRIORITY` | Discarded; metric only. |
| `GOAWAY` | After the next response on `lastStreamId`, close accumulation. Streams with id > `lastStreamId` are orphaned → fire `RECONSTRUCTION_STATUS = GOAWAY_DROPPED`. |
| `PUSH_PROMISE` | Discarded; metric only (would require a fundamentally different replay model). |

### 8.3 Callback contract preserved

The existing `AccumulationCallbacks.onRequestReceived(...)` and the response
continuation mechanism remain untouched. Each H2 stream produces one
`RequestResponsePacketPair`, exactly like today's H1 keep-alive request.

The semantic difference is **inter-pair ordering**: under H1 keep-alive,
request N+1 is fully received only after response N. Under H2, response 1 may
arrive after request 5 has been emitted as a logical pair. Our replay engine
already supports concurrent in-flight requests per session via
`OnlineRadixSorter`; no change needed there.

### 8.4 The transformation boundary: an architectural seam

This is where most of the H2-specific logic concentrates, and it deserves
naming as a first-class architectural element. Every protocol-aware
operation either happens **before** the boundary (capture, accumulator) or
**after** the boundary (transformations, target connection). Exactly one
component crosses it: the `H2ToH1ObjectAdapter`.

```
                  ── CAPTURE / ACCUMULATOR side ──┄┃┄── TRANSFORMATION / REPLAY side ──
                                                  ┃
  protobuf records → H2Accumulation                ┃   HttpJsonTransformingConsumer (UNCHANGED)
       │            │  │  ┌──────────────────────┐ ┃            │
       └─ stream 1 ─┘  │  │ H2StreamAccumulation │ ┃   RequestPipelineOrchestrator (UNCHANGED)
       └─ stream 3 ────┤  │  - method, path      │ ┃            │
       └─ stream 5 ────┘  │  - headers (decoded) │ ┃   NettyJsonBodyAccumulateHandler ...
                          │  - body[]            │ ┃            │
                          │  - trailers          │ ┃   NettyPacketToHttpConsumer
                          └──────────────────────┘ ┃            ┊  (or H2 sibling)
                                       │           ┃            ┊
                              ┌────────▼─────────┐ ┃   H2-aware target writer
                              │ H2ToH1ObjectAdapter│┃            ┊
                              │  emits:           │┃            ▼
                              │   HttpRequest    │ ┃        target server
                              │ + HttpContent[]  │ ┃
                              │ + LastHttpContent│ ┃
                              └──────────────────┘ ┃
                                                  ┃
                                                  BOUNDARY
```

**What happens at the boundary**:

1. Pseudo-header normalization (`:method`, `:scheme`, `:authority`, `:path`)
   per RFC 7540 §8.1.2.3.
2. `:authority` → `Host:` (transformations and SigV4 expect `Host`).
3. Trailers are reattached after the body (with `Trailer:` header set if any
   target codec is H1).
4. Body chunking: the body is fed as a stream of `DefaultHttpContent`s sized
   to mirror the original DATA frame boundaries (preserves chunk-size hints
   for the existing chunking-aware transformers).
5. `LastHttpContent` is emitted only when the H2 stream has reached
   `END_STREAM` (or RST_STREAM, in which case the pair is short-circuited
   with a partial-reconstruction status, never reaching transforms).

**Why the boundary is here, not earlier or later**:

- *Earlier* (e.g. emit `HttpRequest` from inside the accumulator): conflates
  protocol-decoding state (H2-stream-aware) with HTTP-message state
  (request/response level). The accumulator already has too many concerns.
- *Later* (e.g. let the H2 codec object types flow into the transformer):
  every existing JSON handler would need to know about H2; the surface area
  of changes explodes from "one adapter class" to "every handler in
  `RequestPipelineOrchestrator`".

**What does NOT happen at the boundary**:

- No re-encoding into H2 frames. Output is H1 objects. The *target* writer
  (§8.5) re-encodes into H2 only if the target negotiated h2.
- No HPACK re-encoding. Captured headers are already decoded.
- No flow control. The transformation pipeline operates on whole requests,
  not frames; there's no streaming back-pressure to preserve.

### 8.4.1 Adapter implementation

`HttpJsonTransformingConsumer` and `RequestPipelineOrchestrator` operate on
`HttpRequest + HttpContent`. The adapter converts `H2StreamAccumulation`
into that shape **without** the bytes ever passing through
`HttpRequestDecoder`:

```java
class H2ToH1ObjectAdapter {
    static List<HttpObject> toH1Objects(H2StreamAccumulation s) {
        var http1Headers = mapH2HeadersToH1(s.requestHeaders);
        var req = new DefaultHttpRequest(
            HTTP_1_1,
            HttpMethod.valueOf(s.method()),
            s.path(),
            http1Headers);
        var objects = new ArrayList<HttpObject>();
        objects.add(req);
        for (int i = 0; i < s.requestBody.size() - 1; i++) {
            objects.add(new DefaultHttpContent(s.requestBody.get(i)));
        }
        objects.add(new DefaultLastHttpContent(
            s.requestBody.isEmpty() ? Unpooled.EMPTY_BUFFER : s.requestBody.get(s.requestBody.size()-1)));
        return objects;
    }
}
```

#### Pseudo-header and connection-specific header mapping (RFC 7540 §8.1.2)

| H2 input | H1 output | Notes |
|---|---|---|
| `:method` | request line method | Required. Missing → `RECONSTRUCTION_STATUS.MALFORMED`, skip stream. |
| `:path` | request line target | Required, non-empty for HTTP-scheme requests. CONNECT method uses `:authority` only and `:path` MUST be omitted; if seen, stream is `UNSUPPORTED`. |
| `:scheme` | dropped (not represented in H1 request line for clients) | Recorded in tuple metadata for visibility. |
| `:authority` | `Host:` header | If a `Host:` header *also* appears in the H2 headers (uncommon, pre-RFC clients do this), `:authority` wins. Log a warning. |
| `:status` (response only) | response status line | Required on responses; missing → `MALFORMED`. |
| `connection` header | **drop** | RFC 7540 §8.1.2.2 forbids in H2; if present, capture is malformed but tolerate by dropping. |
| `keep-alive` header | **drop** | Same as `connection`. |
| `proxy-connection` header | **drop** | Same. |
| `transfer-encoding` header | **drop iff value == "chunked"**; preserve otherwise | RFC 7540 §8.1.2.2 explicitly forbids `chunked`. Other values (e.g. `identity`) tolerated. |
| `upgrade` header | **drop** | Forbidden in H2. |
| `te` header | **preserve only if value == "trailers"** | Per RFC 7540 §8.1.2.2, only "trailers" is valid. |
| `host` (lowercase, regular header) | preserve if no `:authority`, else dropped (`:authority` takes precedence) | Some H1-via-H2 bridges duplicate Host:. |
| `content-length` | preserve | Used by H1 transformations and for SigV4 signing. |
| Cookie crumbs (multiple `cookie` headers) | **fold into a single `cookie:` header** with `; ` separator | RFC 7540 §8.1.2.5; H1 expects a single Cookie header. |
| Header names with uppercase | **lowercase** before emission | RFC 7540 §8.1.2; required for H2, customary for H1 emission. |
| Header values containing CR/LF | **reject stream** with `RECONSTRUCTION_STATUS.MALFORMED` | Defense against header injection. |
| Trailers (HEADERS frame after body with `endStream`) | append as `LastHttpContent` trailing headers (`HttpHeaders trailingHeaders()`) | If target is H1, set `Transfer-Encoding: chunked` if not already (chunked is the only H1 framing that supports trailers). If target is H2, trailers reattach naturally. |

#### Concrete mapping skeleton

```java
final class H2ToH1ObjectAdapter {

    private static final Set<AsciiString> CONNECTION_SPECIFIC_HEADERS =
        Set.of(AsciiString.cached("connection"),
               AsciiString.cached("keep-alive"),
               AsciiString.cached("proxy-connection"),
               AsciiString.cached("upgrade"));

    static List<HttpObject> toH1Objects(H2StreamAccumulation s) {
        // 1. Validate required pseudo-headers
        ByteString methodPseudo = s.pseudoHeaders().get(":method");
        ByteString pathPseudo   = s.pseudoHeaders().get(":path");
        if (methodPseudo == null || (pathPseudo == null && !"CONNECT".equals(methodPseudo.toString()))) {
            throw new MalformedH2RequestException("missing :method or :path");
        }

        // 2. Build H1 headers
        DefaultHttpHeaders h1 = new DefaultHttpHeaders(true /* validate */);
        ByteString authority = s.pseudoHeaders().get(":authority");
        if (authority != null) {
            h1.set(HttpHeaderNames.HOST, authority.toString());
        }

        for (Http2HeaderField f : s.regularHeaders()) {
            String name = f.name().toString().toLowerCase(Locale.ROOT);
            String val  = f.value().toString();
            if (CONNECTION_SPECIFIC_HEADERS.contains(AsciiString.cached(name))) continue;
            if ("transfer-encoding".equals(name) && "chunked".equalsIgnoreCase(val)) continue;
            if ("te".equals(name) && !"trailers".equalsIgnoreCase(val)) continue;
            if ("host".equals(name) && authority != null) continue;        // :authority wins
            if (val.indexOf('\r') >= 0 || val.indexOf('\n') >= 0) {
                throw new MalformedH2RequestException("CRLF in header value: " + name);
            }
            h1.add(name, val);
        }

        // 3. Cookie crumb folding (RFC 7540 §8.1.2.5)
        List<String> cookieCrumbs = h1.getAll(HttpHeaderNames.COOKIE);
        if (cookieCrumbs.size() > 1) {
            h1.set(HttpHeaderNames.COOKIE, String.join("; ", cookieCrumbs));
        }

        // 4. Build request line
        DefaultHttpRequest req = new DefaultHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.valueOf(methodPseudo.toString()),
            pathPseudo == null ? "" : pathPseudo.toString(),
            h1);

        // 5. Body chunks → HttpContent objects sized to original DATA frame boundaries
        List<HttpObject> out = new ArrayList<>(s.requestBody().size() + 2);
        out.add(req);
        for (int i = 0; i < s.requestBody().size() - 1; i++) {
            out.add(new DefaultHttpContent(s.requestBody().get(i)));
        }

        // 6. Trailers (if any) attach to LastHttpContent
        ByteBuf lastBuf = s.requestBody().isEmpty()
            ? Unpooled.EMPTY_BUFFER
            : s.requestBody().get(s.requestBody().size() - 1);
        DefaultLastHttpContent last = new DefaultLastHttpContent(lastBuf);
        if (s.requestTrailers() != null) {
            for (Http2HeaderField t : s.requestTrailers()) {
                last.trailingHeaders().add(t.name().toString().toLowerCase(Locale.ROOT),
                                           t.value().toString());
            }
        }
        out.add(last);
        return out;
    }
}
```

#### Symmetric response-side mapping

For responses, the adapter is invoked when the response stream completes:

| H2 response input | H1 output |
|---|---|
| `:status` | `HttpResponseStatus` on `DefaultHttpResponse` |
| All regular headers | preserved with same drop rules as request |
| Body DATA frames | `HttpContent` sequence terminated by `LastHttpContent` |
| Trailers HEADERS | `LastHttpContent.trailingHeaders()` |

The transformation pipeline runs against `HttpRequest + HttpContent` and emits
`ByteBuf`s as today. The boundary handler (the new
`H2NettyPacketToHttpConsumer`, see below) decides how to wire those bytes onto
the target.

This is the **least-invasive** integration: zero changes to
`RequestPipelineOrchestrator` or any of the JSON handlers.

### 8.5 Target connection: `H2NettyPacketToHttpConsumer`

`NettyPacketToHttpConsumer` is the replayer's outbound pipe today. We
introduce a sibling that fully terminates H2 toward the target. Unlike the
proxy, the replayer *generates* requests rather than forwarding bytes —
termination is the right model here.

#### Selection: factory probe-and-cache

```java
public class TargetProtocolFactory {
    private final URI targetUri;
    private final boolean enableHttp2;
    private volatile String cachedAlpn;          // "h2" | "http/1.1" — set on first probe

    public IPacketFinalizingConsumer<AggregatedRawResponse> create(
            ConnectionReplaySession session,
            IReplayerHttpTransactionContext ctx,
            Duration timeout) {
        String alpn = cachedAlpn;
        if (alpn == null) {
            alpn = probeAlpnSync();              // open one connection, observe ALPN, close
            cachedAlpn = alpn;
        }
        return switch (alpn) {
            case "h2"       -> new H2NettyPacketToHttpConsumer(session, ctx, timeout, h2ConnectionPool);
            case "http/1.1" -> new NettyPacketToHttpConsumer(session, ctx, timeout);  // existing
            default          -> throw new IllegalStateException("unknown ALPN: " + alpn);
        };
    }
}
```

The probe is one-shot per replayer process per target URI. Cluster
upgrades / failovers don't change ALPN (target either supports h2
permanently or not), so caching is safe.

#### Pipeline (target side, H2)

```
SslHandler  (with applicationProtocolConfig advertising h2 + http/1.1)
  → Http2FrameCodec(Client)                    // full H2 codec — we own this
  → Http2MultiplexHandler                      // sub-channel per stream
        ├─ stream sub-pipeline (per request):
        │     Http2StreamFrameToHttpObjectCodec  // converts H2 frames ↔ HttpRequest/HttpContent
        │     ReadMeteringHandler                // existing, byte counting
        │     WriteMeteringHandler               // existing
        │     BacksideSnifferHandler             // existing
        │     HttpResponseDecoder?                 // NO — codec above already produces HttpObjects
        │     BacksideHttpWatcherHandler         // existing, callback-driven
```

Critically: `BacksideHttpWatcherHandler` (`addCallback`,
`triggerResponseCallbackAndRemoveCallback` on `LastHttpContent`) is
**unchanged** — it consumes `HttpObject` and doesn't care about framing.
This is the payoff of the boundary-adapter design.

#### Per-stream lifecycle

One `Http2StreamChannel` per logical request:

```java
class H2NettyPacketToHttpConsumer implements IPacketFinalizingConsumer<AggregatedRawResponse> {
    private final Channel parentChannel;          // the long-lived H2 connection
    private Http2StreamChannel streamChannel;     // opened on first consumeBytes
    // ... existing fields ...

    @Override
    public TrackedFuture<String, Void> consumeBytes(ByteBuf packetData) {
        if (streamChannel == null) {
            streamChannel = openStream();         // Http2StreamChannelBootstrap.open()
            installPerStreamHandlers(streamChannel);
        }
        return writePacketToStream(packetData);   // writes via Http2StreamFrameToHttpObjectCodec
    }

    @Override
    public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
        // Same callback contract as H1: write LastHttpContent, await BacksideHttpWatcherHandler.
        // On completion, close the stream sub-channel; parent channel survives.
    }
}
```

**Stream open**: `new Http2StreamChannelBootstrap(parentChannel).open().sync()`.
This is cheap (~µs); Netty allocates a streamId from the codec's pool.

**Stream close**: when `LastHttpContent` arrives in the response or
`BacksideHttpWatcherHandler.channelInactive` fires. Close
`streamChannel`, leave `parentChannel` alive. Parent close is governed by
the `ConnectionReplaySession`'s lifecycle, same as today.

**Stream concurrency**: a `ConnectionReplaySession` may issue multiple
logical requests; each gets its own `H2NettyPacketToHttpConsumer`
instance, each opens its own stream sub-channel on the shared parent.
Bound: `min(SETTINGS_MAX_CONCURRENT_STREAMS_target, replayerMaxConcurrent)`.
Beyond that bound, the session-level orchestrator queues — the same
back-pressure mechanism today's `OnlineRadixSorter` already provides.

#### Out-of-order completion

H2 streams complete in any order. Each
`H2NettyPacketToHttpConsumer.finalizeRequest()` returns its own future
that resolves independently. `BacksideHttpWatcherHandler` is per-stream
(installed in the sub-channel, not the parent), so callbacks route by
sub-channel identity, not by globally-ordered response sniffing.

This is fundamentally cleaner than today's H1 path: there's no
serial-response assumption to defend against. The replayer's existing
out-of-order-tuple handling (which exists for the proxy-side
multiplexing case) covers this naturally.

#### GOAWAY from target

`Http2FrameCodec` raises a `Http2GoAwayFrame` event up the parent
pipeline. We install a `GoAwayHandler` on the parent:

```java
@Override
public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
    if (evt instanceof Http2GoAwayFrame goAway) {
        int lastStreamId = goAway.lastStreamId();
        // Streams ≤ lastStreamId may complete normally; streams > lastStreamId are dropped by target.
        markChannelDraining(ctx.channel(), lastStreamId);
        // No new streams allowed on this channel; session orchestrator opens a new one for retries.
    }
    super.userEventTriggered(ctx, evt);
}
```

Streams already in flight with id ≤ `lastStreamId`: continue normally.
Streams beyond: their `H2NettyPacketToHttpConsumer.finalizeRequest()`
future completes with an error; the existing retry path
(`DefaultRetry`) opens a new connection and retries.

#### Parent connection lifecycle

The parent H2 connection is owned by `ConnectionReplaySession`'s channel
field. It stays open across logical requests for one session, the same
way the H1 channel does today. It closes when:

- The session itself closes (TTL expired, replayer shutdown).
- The target sends GOAWAY and all in-flight streams complete.
- The target closes the TCP connection.
- An I/O exception fires.

`ConnectionReplaySession.replayEngine.closeConnection()` handles the
parent close uniformly for H1 and H2.

#### Concurrency note (revisited)

`ConnectionReplaySession` today serializes requests on its single H1
channel. The H2 path lifts that serialization: requests run concurrently
on stream sub-channels under one parent. The session abstraction
absorbs this by keeping per-request state (the
`H2NettyPacketToHttpConsumer` instance) separate from per-connection
state (the parent `Channel`). Up to
`SETTINGS_MAX_CONCURRENT_STREAMS` are allowed in flight; the session
orchestrator queues beyond.

### 8.6 Tuple output format

`SourceTargetCaptureTuple` JSON output gains:

```json
{
  "source": {
    "protocol": "HTTP/2.0",
    "streamId": 5,
    "request": { ... headers + body, mapped to H1 shape ... },
    "response": { ... }
  },
  "target": {
    "protocol": "HTTP/2.0" | "HTTP/1.1",   // whatever the target negotiated
    "request": { ... },
    "response": { ... }
  }
}
```

Existing tuple consumers continue to work; new fields are additive.

### 8.7 End-to-end lifecycle of one request (worked example)

To make state tracking concrete: a client opens an H2 connection, sends two
multiplexed POSTs (streams 1 and 3) plus a GET (stream 5). The GET completes
first; the POSTs complete in arbitrary order.

```
 t=0ms  client─────H2 connection + SETTINGS────────────────►proxy
                                                            │ S₁: open conn, init HPACK decoder
                                                            │ S₂: open upstream H2 conn (or H1), init S₂'s codec
                                                            │ EMIT: AlpnNegotiationObservation
                                                            │ EMIT: Http2FrameObservation{settings, ack, ...}
 t=1ms  client─HEADERS(s=1, :method=POST, ...)─────────────►proxy
                                                            │ Decoded by S₁'s HPACK decoder.
                                                            │ shouldGuaranteeMessageOffloading(POST) = true
                                                            │ → enqueue HEADERS@s1 in PerStreamGate
                                                            │ EMIT: Http2FrameObservation{headers, streamId=1}
                                                            │ Block forwarding s1 frames until offload commit.
 t=2ms  client─HEADERS(s=3, :method=POST, ...)─────────────►proxy
                                                            │ Same as s1: gate s3 frames pending offload.
                                                            │ EMIT: Http2FrameObservation{headers, streamId=3}
 t=3ms  client─HEADERS(s=5, :method=GET, end_stream)───────►proxy
                                                            │ GET → not gated.
                                                            │ EMIT: Http2FrameObservation{headers, end_stream, streamId=5}
                                                            │ Forward s5 frame bytes verbatim to upstream.
 t=4ms  client─DATA(s=1, end_stream, body)────────────────►proxy [held]
                                                            │ EMIT: Http2FrameObservation{data, end_stream, streamId=1}
 t=5ms  Kafka offload of TrafficStream containing s1+s3 HEADERS commits.
                                                            │ PerStreamGate releases s1, s3 forwarding.
                                                            │ S₂ writes s1, s3 frames to upstream.
 t=6ms  upstream──HEADERS(s'=2, ...)+DATA──────────────────►proxy [s'=2 mapped to s=5 by S₂]
                                                            │ EMIT: Http2FrameObservation{headers + data, streamId=5} [response side]
                                                            │ Forward response bytes verbatim to client.
        ... etc ...

 ─────────────── proto records flow to Kafka ───────────────

   capture stream (Kafka topic, partitioned by connectionId):
     TrafficStream { connId=A, captureFormatVersion="v2", subStream=[
       AlpnNegotiation{h2}, Frame{settings}, Frame{headers,s=1}, Frame{headers,s=3},
       Frame{headers,end,s=5}, Frame{data,end,s=1}, ... ] }
     TrafficStream { connId=A, ... continues ... }

 ─────────────── replayer reads TrafficStreams ───────────────

  S₃ for connId=A:
    H2Accumulation { liveStreams: {1: ..., 3: ..., 5: ...} }
    On Frame{headers,s=1}     → create H2StreamAccumulation, set headers
    On Frame{headers,s=3}     → create H2StreamAccumulation, set headers
    On Frame{headers,end,s=5} → fire onRequestReceived for stream 5 immediately
    On Frame{data,end,s=1}    → fire onRequestReceived for stream 1
    ...

  Each onRequestReceived invokes:
    H2ToH1ObjectAdapter.toH1Objects(stream5)  → DefaultHttpRequest + LastHttpContent(EMPTY)
    HttpJsonTransformingConsumer.consume(...)  → JSON transforms run
    H2NettyPacketToHttpConsumer.consume(...)   → opens new target H2 stream s''=1 (S₄'s numbering)
    [response read back, BacksideHttpWatcherHandler fires callback]

  Tuple emitted:
    {
      "source": { "protocol": "HTTP/2", "streamId": 5, "request": {...}, "response": {...} },
      "target": { "protocol": "HTTP/2", "streamId": 1, "request": {...}, "response": {...} }
    }
```

Observations:
- streamId 5 (source) maps to streamId 1 (target). They are **not** the same
  number — a frequent debugging gotcha. The tuple records both.
- HPACK state is independent in each of S₁, S₂, S₃ (no encoder), S₄. We never
  reuse encoded header blocks across boundaries.
- The accumulator never blocks on stream order — stream 5 fires first because
  it ended first. This matches H2 semantics.
- Inter-stream timestamp ordering is preserved in the *capture stream*
  (TrafficObservations are appended in proxy-observed order). The replayer
  uses this for inter-stream pacing on the target side, but does not
  artificially serialize.

### 8.8 Replayer CLI flags

```
--enableHttp2Capture        Accept H2 captures (default: true once GA; false in beta)
--targetEnableHttp2         Negotiate H2 with target (default: false)
--targetH2MaxConcurrentStreams=<int>
--strictProtocolMatching    Fail tuple if source H2 / target H1 (default: false)
```

---

## 9. Edge cases & their handling

| Edge case | Handling |
|---|---|
| Client sends H2 preface but server only speaks H1 | ALPN never picks h2. If the client uses prior-knowledge h2c, the proxy returns a connection error; document. |
| Stream RST_STREAM mid-body | Capture: emit `Http2FrameObservation{rstStream}`. Replayer: callback with `RECONSTRUCTION_STATUS.RESET_BY_PEER`; do NOT replay request. |
| Stream RST_STREAM mid-response | Replayer: response is partial; emit with `RECONSTRUCTION_STATUS.RESET_BY_PEER` and truncate body. |
| GOAWAY received | Replayer: drain streams ≤ lastStreamId; emit GOAWAY_DROPPED for higher streams. |
| Connection-level WINDOW_UPDATE never decremented (replayer client bug) | Netty's H2 codec handles it; we don't manage windows manually. |
| HPACK dynamic-table size set to zero by source | We captured decoded headers, so it's irrelevant for replay. |
| Trailers (HEADERS frame after body with `endStream`) | Map to H1 trailing headers if target is H1 (transfer-encoding: chunked) or to H2 trailers if target is H2. |
| CONTINUATION frames | Netty's codec already coalesces these into a single HEADERS event by default; we get one `Http2HeadersFrame`. The proto schema preserves CONTINUATION as a fallback in case we ever need it. |
| Capture buffer rolls mid-stream | TrafficStream rotates between two TrafficObservations; a stream's frames may span TrafficStreams. The replayer's `ExpiringTrafficStreamMap` already supports this for H1 keep-alive. |
| Same connectionId, different sourceGeneration (Kafka rebalance) | Same as today: `TrafficSourceReaderInterruptedClose` flushes; H2 streams in flight are emitted with `TRAFFIC_SOURCE_READER_INTERRUPTED`. |
| Idle connection keep-alive PINGs | Captured but not replayed; metric only. |
| Server PUSH_PROMISE | Captured for fidelity; not replayed; metric only. Tuple output emits a synthetic note. |
| Stream count ≫ in-memory budget | New `accumulation.maxLiveStreams` config; if exceeded, oldest idle stream is reset with synthetic RECONSTRUCTION_STATUS = MEMORY_PRESSURE. |
| Frame ordering within a stream guaranteed by H2 | But ordering across streams is NOT — we sort by capture timestamp on the replay side to deterministically interleave logical requests. |
| Request with `:method = CONNECT` | OpenSearch traffic shouldn't have this; capture but `RECONSTRUCTION_STATUS.UNSUPPORTED`, skip replay. |
| Mutating-request offload-block timeout | Same handling as today: log warning, forward anyway. |
| TLS resumption breaks ALPN consistency | Each new TCP connection re-negotiates ALPN; fine. |
| Partial HEADERS captured (proxy crashed mid-frame) | Proto observation either present or absent; the replayer's existing partial-stream handling fires `EXPIRED_PREMATURELY`. |

---

## 10. Implementation plan — phased

Each phase has explicit acceptance criteria and verification commands. A
phase is "done" when all `Verify:` commands pass and all `Acceptance:`
scenarios are covered.

### Dependency graph

```
Phase 0 (RFC) ──► Phase 1 (schema) ──┬─► Phase 2 (proxy capture)  ──► Phase 3 (backside ALPN + gate) ──┐
                                     │                                                                 │
                                     └─► Phase 4 (accumulator)    ──► Phase 5 (adapter)  ──► Phase 6 (target H2) ──► Phase 8 (load + rollout)
                                                                                                                ▲
                                                                                                                │
                                                                                                       Phase 7 (observability/tuples)
```

**Parallelizable**:
- Phases 2 (proxy) and 4 (replayer accumulator) are independent after Phase 1
  lands and can run in parallel by two engineers.
- Phase 7 (observability) can run in parallel with Phases 5 and 6.

**Strict gates**:
- Phase 1 gates everything (schema is the contract).
- Phase 3 gates Phase 8 (proxy must be functionally complete before load
  testing).
- Phase 6 gates Phase 8 (replayer must be functionally complete).

### Phase 0 — RFC + integration env (1 sprint)

- Land this LLD as `docs/rfcs/0001-http2-trafficcapture.md`.
- Stand up an integration test environment with an H2-capable client (curl
  --http2 + a JS axios test) and OpenSearch 2.x with H2 enabled.
- Verify what % of incoming traffic in a typical migration uses H2 (if known
  from existing customer telemetry, fold into the doc).

**Acceptance:**
- [x] LLD merged to main.
- [x] `dev-tools/integration/h2/` contains a docker-compose that brings up
      OpenSearch 2.x with H2 enabled and a curl-based smoke client.

**Verify:**
```bash
docker compose -f dev-tools/integration/h2/compose.yml up -d
curl --http2-prior-knowledge http://localhost:9200/  # smoke check
```

### Phase 1 — schema & codegen (1 sprint)

- Update `TrafficCapture/captureProtobufs/src/main/proto/TrafficCaptureStream.proto` per §6.
- Bump `captureProtobufs` minor version in its `build.gradle`.
- Add new fields to `TrafficStream` (8, 9) and `TrafficObservation` (17, 18).
- Define new payload messages (Http2*Payload).
- Update `IChannelConnectionCaptureListener` with the §6.1 method signatures.
- Add unit tests for round-trip serialization of all new observation types.

**Acceptance:**
- [x] `./gradlew :TrafficCapture:captureProtobufs:build` succeeds.
- [x] Generated Java compiles in `captureOffloader`, `captureKafkaOffloader`,
      `trafficReplayer` without changes to those modules.
- [x] H1 captures from main (binary fixture) deserialize byte-identically.
- [x] All new observation types round-trip (encode → decode → assert equal).
- [x] An old replayer reading a TrafficStream with `captureFormatVersion="v2"`
      and an `Http2FrameObservation` fails fast at the version check (NEW
      defensive code in the replayer's TrafficStream loader).

**Verify:**
```bash
./gradlew :TrafficCapture:captureProtobufs:test
./gradlew :TrafficCapture:captureProtobufs:check
./gradlew :TrafficCapture:trafficReplayer:test --tests "*BackwardsCompatibility*"
```

### Phase 2 — proxy ALPN + H2 capture (2 sprints)

- Add `--enableHttp2` CLI flag in `CaptureProxy.Parameters`.
- Configure `applicationProtocolConfig` in `loadSslEngineFromPem` /
  `buildSslEngineSupplier` per §7.1.
- Replace static pipeline in `ProxyChannelInitializer.initChannel` with
  `ApplicationProtocolNegotiationHandler` deferred initializer per §7.2.
- Implement `H2FrameSnifferHandler` per §7.3 (cumulator FSM, HPACK decoder,
  CONTINUATION coalescing, SETTINGS tracking, slice ownership).
- Override new methods in `StreamChannelConnectionCaptureSerializer`
  (`addH2FrameRead`, `addH2FrameWrite`, `addAlpnNegotiatedEvent`).
- Add sizing logic to `CodedOutputStreamSizeUtil`.
- Generalize `RequestCapturePredicate` to `ICaptureDecisionPolicy` per §7.3.
- Remove the `protocolPattern("HTTP/2.*")` capture-suppression default when
  `--enableHttp2` is set.

**Acceptance:**
- [x] curl --http2-prior-knowledge → proxy → echo upstream succeeds.
- [x] Kafka topic contains TrafficStream records with
      `captureFormatVersion="v2"`, `negotiatedAlpn="h2"`, and a sequence of
      `Http2FrameObservation` entries.
- [x] HPACK dynamic-table-size update via SETTINGS is correctly tracked
      (covered by `settings-headers-table-resize.binpb` fixture).
- [x] HEADERS+CONTINUATION coalesce to a single decoded headers payload
      (covered by `continuation-headers.binpb`).
- [x] Frame slices are released without ref-count leaks (use
      `ResourceLeakDetector.Level.PARANOID` in tests).
- [x] H1 traffic on the same proxy with `--enableHttp2` enabled is
      byte-identical to H1-only mode (regression test).

**Verify:**
```bash
./gradlew :TrafficCapture:nettyWireLogging:test
./gradlew :TrafficCapture:trafficCaptureProxyServer:test
./gradlew :TrafficCapture:trafficCaptureProxyServer:integrationTest \
    --tests "*H2*"
# Resource leak detection
./gradlew :TrafficCapture:trafficCaptureProxyServer:test \
    -Dio.netty.leakDetectionLevel=paranoid
```

### Phase 3 — proxy backside ALPN + per-stream gate (1 sprint)

- Extend `BacksideConnectionPool` with ALPN advertisement client-side per §7.5.
- Add startup-probe of upstream ALPN in `CaptureProxy.main`; refuse to
  advertise h2 to clients if upstream doesn't support it.
- Implement `PerStreamGateHandler` per §7.4 (state machine, HEADERS+DATA
  hold, WINDOW_UPDATE hold, drain on commit).
- On ALPN mismatch (client h2, upstream h1-only), fail-closed at TLS
  handshake with clear error.

**Acceptance:**
- [x] H2 client → proxy (h2) → H2 upstream end-to-end works.
- [x] H2 client → proxy (configured `--enableHttp2`) → H1-only upstream:
      proxy probe sees H1, doesn't advertise h2; client falls back to h1
      transparently.
- [x] Mutating POST is held until Kafka offload commits (assert via
      timing test: response must arrive only after Kafka record is
      visible).
- [x] Non-mutating GET on a different stream is *not* held (proves
      per-stream isolation).
- [x] Buffering bound: with 10MB body POST gated, proxy memory growth ≤
      `SETTINGS_INITIAL_WINDOW_SIZE` × 1.2 (some Netty overhead).

**Verify:**
```bash
./gradlew :TrafficCapture:trafficCaptureProxyServer:integrationTest \
    --tests "*PerStreamGate*"
./gradlew :TrafficCapture:trafficCaptureProxyServer:integrationTest \
    --tests "*BacksideAlpnMismatch*"
```

### Phase 4 — replayer accumulator (2 sprints)

- Refactor `Accumulation` to abstract base + `H1Accumulation` /
  `H2Accumulation` subclasses per §8.1.
- Implement protocol dispatch in `createInitialAccumulation` per
  §8.1+factory section, including mid-stream-resume handling.
- Implement `addH2Observation` per §8.2 (frame-type table dispatch).
- Implement `H2StreamAccumulation` lifecycle including RST_STREAM, GOAWAY,
  trailers.
- Existing `addObservationToAccumulation` becomes the H1 path
  (`addH1Observation`).

**Acceptance:**
- [x] All H2 fixtures from §11 produce the expected count of
      `RequestResponsePacketPair` callbacks (e.g.
      `search-multi-stream.binpb` produces exactly 5 pairs).
- [x] H1 fixtures (existing tests) still pass byte-identically.
- [x] RST_STREAM mid-body fires callback with
      `RECONSTRUCTION_STATUS.RESET_BY_PEER`.
- [x] GOAWAY drops streams above lastStreamId with
      `RECONSTRUCTION_STATUS.GOAWAY_DROPPED`.
- [x] Kafka rebalance simulation: pause mid-connection, resume on a new
      generation; protocol dispatch correctly identifies as H2 from
      envelope `negotiatedAlpn`.

**Verify:**
```bash
./gradlew :TrafficCapture:trafficReplayer:test \
    --tests "*Accumulator*"
./gradlew :TrafficCapture:trafficReplayer:test \
    --tests "*H2Accumulation*"
./gradlew :TrafficCapture:trafficReplayer:test \
    --tests "*KafkaRebalanceH2*"
```

### Phase 5 — replayer transformation adapter (1 sprint)

- Implement `H2ToH1ObjectAdapter` per §8.4.1 (full pseudo-header table,
  cookie folding, CRLF rejection, trailer attachment).
- Wire adapter into the per-stream callback path so transformations run
  on H2-sourced requests transparently.
- Reuse all existing transformer fixtures (JSON, JOLT, JMESPath) to
  exercise H2-sourced inputs.

**Acceptance:**
- [x] Every existing JSON-transformer test passes when the request is
      injected as H2 instead of H1.
- [x] Pseudo-header mapping golden test (one per row of the §8.4 table).
- [x] CONNECT method captures are tagged
      `RECONSTRUCTION_STATUS.UNSUPPORTED` and skipped.
- [x] Malformed H2 (CRLF in header value, missing :method) tagged
      `RECONSTRUCTION_STATUS.MALFORMED`.
- [x] Trailers preserved through the transformation pipeline.

**Verify:**
```bash
./gradlew :TrafficCapture:trafficReplayer:test \
    --tests "*H2ToH1Adapter*"
./gradlew :TrafficCapture:trafficReplayer:test \
    --tests "*HttpJsonTransformingConsumer*" \
    -DtestSourceProtocol=h2
```

### Phase 6 — replayer target side (2 sprints)

- Implement `TargetProtocolFactory` with one-shot ALPN probe + cache.
- Implement `H2NettyPacketToHttpConsumer` per §8.5 (parent + sub-channel
  lifecycle, GOAWAY handling, out-of-order completion).
- Wire into `ConnectionReplaySession` so per-request consumers can
  multiplex on the parent channel.
- Verify all existing replayer behaviors (retries, SigV4, auth headers)
  work end-to-end against an H2 target.

**Acceptance:**
- [x] Replay against H1 target with H2-source captures succeeds.
- [x] Replay against H2 target with H2-source captures succeeds.
- [x] Replay against H2 target with H1-source captures succeeds (proves
      adapter is bidirectional in spirit).
- [x] Concurrent stream limit honored (target says
      `SETTINGS_MAX_CONCURRENT_STREAMS=10`; replayer queues 11+).
- [x] GOAWAY-induced retries succeed on a new connection.
- [x] DefaultRetry triggers correctly on per-stream RST_STREAM.

**Verify:**
```bash
./gradlew :TrafficCapture:trafficReplayer:integrationTest \
    --tests "*H2Target*"
./gradlew :TrafficCapture:trafficReplayer:integrationTest \
    --tests "*GoAway*"
./gradlew :TrafficCapture:trafficReplayer:integrationTest \
    --tests "*MaxConcurrentStreams*"
```

### Phase 7 — observability + tuple output (1 sprint, parallel with 5/6)

- Add OTel counters / histograms per §7.7:
  `h2.streams.opened`, `h2.streams.closed.normal`, `h2.streams.reset`,
  `h2.frames.{type}.{in,out}`, `h2.alpn.negotiated`,
  `h2.offload.block_duration_ms`.
- Add tuple JSON fields per §8.6: `protocol`, `streamId`,
  `targetStreamId`.
- Update `TrafficCapture/tupleSink/.../*` to surface new fields.
- Update READMEs (`trafficCaptureProxyServer/README.md`,
  `trafficReplayer/README.md`) with H2 sections.

**Acceptance:**
- [x] OTel exporter shows new metrics; histogram for `offload.block_duration`
      reports realistic values under load.
- [x] Tuple JSON output for an H2 capture shows `"protocol":"HTTP/2"` and
      both source/target streamIds.
- [x] Existing tuple consumers (none assumed to know about H2) still parse
      the tuple JSON without errors (additive fields).

**Verify:**
```bash
./gradlew :TrafficCapture:tupleSink:test
./gradlew :TrafficCapture:trafficReplayer:test --tests "*Tuple*"
# Smoke check: replay a fixture and grep tuple JSON
./gradlew :TrafficCapture:trafficReplayer:replaySingleFixture \
    -Dfixture=bulk-index-h2.binpb \
    | grep '"protocol":"HTTP/2"'
```

### Phase 8 — load testing & rollout (1 sprint)

- Reuse the k6 load test harness from PR #2822; add an `--http2` mode.
- Soak test: 24h H2 traffic at production-representative rates.
- Beta release with `--enableHttp2` opt-in.
- One release of soak, then flip default to opt-out.

**Acceptance:**
- [x] k6 24h soak: zero stream loss, zero offload-block deadlocks, p99
      latency overhead ≤ 5% vs H1 baseline.
- [x] Memory profile: per-connection footprint ≤ 16KB at default H2
      settings (HPACK tables × 2 + sniffer state + small per-stream
      maps).
- [x] Beta release notes published.
- [x] One release cycle of beta with at least 3 customers reporting clean
      runs.
- [x] Default flipped to `--enableHttp2=true` in next release.

**Verify:**
```bash
# k6 soak
k6 run --duration=24h dev-tools/loadtest/h2-soak.js \
    --env PROXY_URL=http://proxy:9200 \
    --env TARGET_URL=http://target:9200
# Memory check
./gradlew :TrafficCapture:trafficCaptureProxyServer:run \
    -Dexec.args="--enableHttp2 ..." &
PID=$!
# After warmup, snapshot heap
jcmd $PID GC.heap_dump h2-soak.hprof
```

---

## 11. Testing plan

### Unit tests
- Proto round-trip for every new observation type.
- `H2CapturingHandler`: synthesize Http2Frame instances → assert emitted
  observations are byte-equivalent to expected.
- Accumulator: every state transition table-driven.
- `H2ToH1ObjectAdapter`: pseudo-header mapping; trailers; chunked body
  reassembly.

### Integration tests
- `CaptureProxyContainer` extended to build with `--enableHttp2`. Use a real
  H2 client (`io.netty:netty-codec-http2` test client) hitting the proxy
  hitting a mock H2 backend.
- End-to-end: H2 client → proxy → Kafka → replayer → H2 target. Assert
  source response and target response match for the standard OpenSearch test
  corpus (bulk index, search, scroll, msearch).
- Long-lived multiplexed connection: 1000 concurrent streams, mixed
  GET/POST/DELETE; assert no streams lost, no per-stream offload-block
  deadlocks.

### Property tests (jqwik)
- Random interleavings of N streams with random body sizes; assert demux is
  correct regardless of frame order.
- Random RST_STREAM injections; assert `RECONSTRUCTION_STATUS` is correct.

### Compatibility tests
- Old H1 capture (binary fixture from current main) replays identically with
  the new code.
- New H2 capture replayed by *old* replayer fails fast with a clear error
  message (driven by `captureFormatVersion`).

### Performance tests
- Throughput delta proxy H1-only vs H1+H2-enabled (with H1-only traffic)
  must be < 5%.
- H2 capture overhead per frame: target < 10 µs added latency.

### Test fixtures

**Locations and naming:**
```
TrafficCapture/captureProtobufs/src/test/resources/fixtures/h2/
    bulk-index-single-stream.binpb           # one POST /_bulk, ~10KB body
    search-multi-stream.binpb                # 5 concurrent GETs
    bulk-with-rst-stream.binpb               # POST that gets RST_STREAM mid-body
    long-keepalive-1000-streams.binpb        # 1000 streams over one connection
    goaway-mid-flight.binpb                  # GOAWAY during a multi-stream batch
    push-promise-ignored.binpb               # server PUSH_PROMISE (recorded but unreplayed)
    settings-headers-table-resize.binpb      # mid-connection HEADER_TABLE_SIZE change
    truncated-frame-degraded.binpb           # frame > maxTrafficBufferSize
    continuation-headers.binpb               # HEADERS+CONTINUATION block

TrafficCapture/captureProtobufs/src/test/resources/fixtures/h1/
    legacy-bulk-index.binpb                  # canonical v1 fixture from current main
    legacy-search-keepalive.binpb            # v1 keep-alive

TrafficCapture/trafficReplayer/src/test/resources/fixtures/golden/
    bulk-index-h2.tuple.json                 # expected replay tuple for bulk-index-single-stream
    search-multi-stream-h2.tuple.json
    ... (one per H2 binpb)
```

**How they're generated** (one-time, checked-in):

```bash
# Fixture generator: a Gradle task that boots a real CaptureProxy in a test
# container, runs scripted h2 client interactions against an in-process H2
# echo server, captures the proto stream to a file, and writes it to
# src/test/resources/fixtures/.

./gradlew :TrafficCapture:captureProtobufs:generateH2Fixtures
```

Implementation: a JUnit test class
`org.opensearch.migrations.trafficcapture.protos.fixtures.H2FixtureGenerator`
under `src/test/java/.../fixtures/` (excluded from the regular test run via
`@Tag("fixture-gen")`, opt-in via the gradle task). Each fixture method:

1. Builds a `CaptureProxy` with `--traceDirectory` to write proto to a
   tempdir.
2. Boots a `MockH2Server` (an `EmbeddedChannel`-based H2 endpoint with
   scripted responses).
3. Runs a `MockH2Client` script against `localhost:proxyPort`.
4. Shuts down the proxy cleanly so all `TrafficStream`s flush.
5. Concatenates the per-connection `.bin` files into a single `.binpb`
   fixture and copies into `src/test/resources/fixtures/h2/`.

Golden tuple JSON files are generated by running the replayer against
each `.binpb` against an in-process H2 echo target with a deterministic
response, then capturing the tuple output. Regeneration requires the
`updateGoldenFixtures=true` system property, otherwise the test asserts
exact byte-equality against the checked-in JSON.

**Golden-fixture invariants:**

- Byte-exact stable: regenerating a fixture without intentional schema
  changes must produce a bit-identical file. To make this true, the
  fixture generator uses a fixed `connectionId` (UUID seeded with test
  name), fixed timestamps (epoch-anchored at test virtual time), and
  fixed `nodeId`.
- Schema-version tagged: each `.binpb` has its
  `captureFormatVersion` field populated; tests assert against the
  expected version.
- Real wire bytes: `rawFrame` fields contain bytes captured from the
  actual Netty H2 codec, not synthesized. This catches encoder
  divergence early.

**Wire-level test fixtures (raw bytes, not protobuf):**

```
TrafficCapture/nettyWireLogging/src/test/resources/fixtures/wire/h2/
    preface.bin                              # 24-byte H2 connection preface
    headers-simple.bin                       # one HEADERS frame, GET request
    headers-with-continuation.bin            # HEADERS + 2 CONTINUATION
    data-with-padding.bin                    # DATA frame with PAD_LENGTH
    settings-default.bin                     # default client SETTINGS
    rst-stream-cancel.bin                    # RST_STREAM with CANCEL error
    goaway-no-error.bin                      # graceful GOAWAY
```

Used by `H2FrameSnifferHandlerTest` to drive the cumulator FSM with
deterministic byte sequences (split mid-frame, split mid-header, etc.).

---

## 12. Rollout & migration

- Release N: ship `--enableHttp2` opt-in. Announce in release notes.
  `protocolPattern("HTTP/2.*")` capture-suppression is preserved when flag is
  off.
- Release N+1: still opt-in, but default-on for non-production deployment
  templates (CDK + Helm). Soak in dev for one cycle.
- Release N+2: default-on globally. The ALPN advertisement of `h2,http/1.1`
  is the only behavioral change for clients; H1 clients are unaffected.
- Replayer is backward-compatible by virtue of `captureFormatVersion`;
  upgrading the replayer is independent of upgrading the proxy as long as the
  replayer version is ≥ the proxy version.

**Kafka topic compatibility**: a single topic can hold mixed H1 (v1) and H2
(v2) records. The accumulator dispatches per-record. No migration required.

**Kafka partitioning** stays on `connectionId`; H2 multiplexes within a
connection so all of a connection's streams continue to land on one
partition — no change to scaling semantics.

---

## 13. Open questions / explicit non-decisions

1. **HPACK re-encoding for partial replay**: if a future feature wants to
   replay only some streams of a connection, we'd have to either replay the
   skipped HEADERS frames anyway (simpler) or seed an HPACK encoder that
   matches the source's dynamic-table state at the point of the first
   replayed stream. Out of scope for v1.

2. **h2c (cleartext) prior-knowledge**: trivially supportable post-v1 by
   bypassing TLS and starting at `Http2FrameCodec` directly. Add when first
   customer asks.

3. **Stream-level retries**: today the replayer retries entire requests on
   transient errors via `DefaultRetry`. For H2 the unit is still a stream;
   no design change beyond per-stream RST_STREAM handling. Verify in Phase 6.

4. **GOAWAY-induced replay semantics**: should GOAWAY_DROPPED tuples be
   reported to stdout, or silently dropped? Recommendation: report with a
   prominent status code so users can tell capture loss from upstream errors.

5. **Auth signers (SigV4)**: the deferred-signing path in
   `HttpJsonTransformingConsumer` operates on header objects after JSON
   transforms. Should work for H2-sourced requests because the adapter
   normalizes to the H1 object shape. Confirm with the SigV4 test suite in
   Phase 5.

6. **Tuple comparison**: today the replayer compares H1 source vs H1 target
   bytes. For H2-source / H1-target tuples, headers diverge by construction
   (pseudo-headers vanish, `Host:` appears). Plan to add a "logical
   equivalence" comparator that ignores known protocol-level differences.
   Probably its own RFC.

7. **What protocol does the existing `--enableHttp2`-disabled path advertise
   in ALPN?**: nothing today (no `applicationProtocolConfig`). After Phase 2
   with the flag off, we still advertise nothing — strict back-compat.
   Explicit decision to NOT advertise `http/1.1` alone, because that itself
   would be observable (some clients require ALPN reply when offering it).

---

## 14. Files touched (estimate)

| Module | Files | Rough LOC |
|---|---|---|
| `captureProtobufs/src/main/proto/` | 1 | +~150 |
| `captureOffloader/.../*` | ~6 (interface, serializer, size util) | +~400 |
| `nettyWireLogging/.../*` | ~3 (predicates, new H2 capturing handler) | +~600 |
| `captureKafkaOffloader/.../*` | ~2 | +~50 |
| `trafficCaptureProxyServer/.../*` | ~5 (CaptureProxy, ChannelInitializer, BacksidePool, new H2 stream gate, tests) | +~900 |
| `trafficReplayer/.../*` | ~12 (Accumulation refactor, H2Accumulation, adapter, NettyPacketToHttpConsumer split, tests) | +~1800 |
| `tupleSink/.../*` | ~2 (new fields) | +~80 |
| `docs/`, READMEs | 3 | +~400 |

**Total estimate: ~4,400 LOC + ~3,000 LOC tests over ~10 sprints / 5 months
of focused work for a 2-engineer team.**

---

## 15. Glossary

- **ALPN**: Application-Layer Protocol Negotiation, TLS extension that lets
  client and server agree on `h2`, `http/1.1`, `h3`, etc.
- **HPACK**: HTTP/2 header compression, with a per-direction stateful
  dynamic table.
- **Stream**: in HTTP/2, an independent bidirectional sequence of frames
  identified by an odd integer (client-initiated) or even integer
  (server-initiated, e.g. PUSH_PROMISE).
- **TrafficStream** (proto): the unit of capture serialization in this
  codebase, *not* an HTTP/2 stream. To avoid ambiguity we use "TrafficStream"
  vs "H2 stream" throughout.
- **Accumulation**: replayer's per-connection in-memory state.
- **Tuple**: the JSON record emitted per replayed request comparing source
  vs target.
