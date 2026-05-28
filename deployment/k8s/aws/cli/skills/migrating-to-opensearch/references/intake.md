# Stakeholder-aware intake

The first decision in every assessment is the user's role. Get it right and the rest follows; get it wrong and the report quality collapses. Once set, you MUST run the matching intake below. **You MUST NOT block on a missing artifact because intake stalls collapse the assessment** — capture what is available, mark the rest UNKNOWN, and surface assumptions.

## Personas

| Code | Role | Primary concerns | Default report |
|---|---|---|---|
| **SRE** | Search Relevance Engineer | Schema, queries, analyzers, relevance, feature parity (X-Pack, Solr eDisMax) | `TECHNICAL_DEEP_DIVE.md` |
| **DOP** | DevOps / Platform Engineer | Cluster topology, sizing, ops, IaC, networking, IAM | `TECHNICAL_DEEP_DIVE.md` |
| **BSH** | Business Stakeholder | Cost, timeline, risk, go/no-go | `EXECUTIVE_SUMMARY.md` |

You MUST treat the user as **BSH** if any apply: self-identifies as PM / executive / non-technical; leads with cost, timeline, milestones, "scope this for me"; cannot or will not provide an engine version, topology, or workload metrics.

You MUST treat as **SRE** when they lead with queries, schema, relevance, feature questions, dashboards, or vector engine choice. You MUST treat as **DOP** when they lead with topology, sizing, JVM, networking, IaC, or operations. If unclear, you MUST ASK once. You MUST NOT re-ask because repeated persona prompts erode user trust.

If a single user occupies multiple personas (a small team's lead is often SRE+DOP), you MUST default to the **most technical** voice and add a one-page executive header. You MUST NOT degrade depth because the technical user still needs the technical content — combine instead.

## SRE / DOP technical intake (one prompt, ALL of)

- **Source engine + version** (REQUIRED). For Elasticsearch you MUST capture distribution (Elastic / OSS) and license (`GET /` and `GET _xpack/license`). You MUST flag ES 7.11+ for Elastic License v2 / SSPL legal review (and that Snapshot/Restore from ES ≥ 7.11 is not a supported migration path on Amazon OpenSearch Service — see [`nuggets.md`](nuggets.md) #21). For legacy Solr sources (Trie field types are ≤ 6.x; ClassicSimilarity / TF-IDF default is ≤ 5.x), see [`solr-schema-migration.md`](solr-schema-migration.md).
- **Cluster topology**: nodes + roles, JVM heap, hardware (CPU, RAM, disk type/size). For SolrCloud you MUST also capture the ZooKeeper ensemble.
- **Index inventory** (paste-friendly, ES/OS source):
  ```
  GET _cat/indices?v&h=index,health,status,pri,rep,docs.count,store.size,creation.date.string&format=json
  GET _cat/plugins?v&format=json
  GET _cat/aliases?v&format=json
  GET _cluster/health?human
  GET _cluster/settings?include_defaults=true&human
  GET _nodes/stats/jvm,fs,os,thread_pool
  GET _index_template?human
  ```
- **`_source` enablement** (CRITICAL — drives migration-tool selection): you MUST run `GET <index>/_mapping` for every index in scope. If any has `_source.enabled: false`, see [`nuggets.md`](nuggets.md) #22 — Migration Assistant RFS is the only supported path.
  For Solr, you MUST capture: `schema.xml` (or Schema API JSON), `solrconfig.xml`, sample documents, and up to 5 representative queries (Standard, DisMax, eDisMax — lexical and faceted).
- **Workload signals**: top 5 query patterns, search QPS sustained + peak, indexing rate (docs/sec, MB/sec), aggregation patterns, vector / k-NN usage (dimensions, engine, distance).
- **Operations**: ILM (`_ilm/policy`) for ES, ISM (`_plugins/_ism/policies`) for OS, Watcher rules, CCR settings (`_remote/info`), SLM (`_slm/policy`), snapshot repos (`_snapshot/_all`).
- **Auth backends**: basic, SAML, OIDC, Kerberos, JWT, IAM/SigV4, mTLS. Role definitions and role mappings.
- **Kibana / Dashboards**: saved-objects export, custom plugins, Lens / Canvas / Maps, APM / Fleet / Endpoint Security (X-Pack-locked).
- **Migration constraints**: source location (on-prem / AWS / other cloud), downtime tolerance (`none` / `short` / `long`), continuous-replication need, target region, regulatory constraints (FedRAMP / HIPAA / GovCloud).
- **Current cost**: monthly infra spend on the source cluster (delta comparison only — see [`scope.md`](scope.md) for the no-dollar-cost rule).

The DOP variant additionally MUST ask for: VPC topology, Transit Gateway / PrivateLink, KMS key policy, security groups, current Auto-Tune state, custom plugins.

For target-side security recommendations (auth, transport, encryption, audit, throttling), see SKILL.md "Security Considerations" — that section is canonical. You MUST NOT duplicate it here because divergent copies will drift.

## BSH business intake — six items, IN ORDER (one prompt)

> **Skill IP** — operational framing for non-technical stakeholders. Not from any upstream doc.


You MUST ask FIRST — before any technical question — for ALL six:

1. **Use case of the search system.** E-commerce (B2C/B2B), internal docs, log/observability, support KB, security analytics. Drives target tier selection.
2. **Users.** Internal vs external (web-facing); approximate user count.
3. **Criticality and SLA.** High / medium / low; explicit SLA (e.g. 99.9%); RPO/RTO.
4. **Traffic estimates.** Peak QPS, sustained QPS. If unknown, you MUST derive from use case + user count and surface for confirmation.
5. **Index update needs.** Documents/day; bulk vs streaming; expected growth.
6. **Typical document size.** Or a representative sample to derive size.

Optional after the six: source engine + version. It is fine if the BSH user does not know — you MUST proceed and revisit when DOP/SRE work begins.

### Items you MUST NOT ask a BSH user before completing the six

You MUST NOT ask a BSH user any of the following before the six items above are complete because BSH users typically cannot answer them and the questions erode trust:

- `elasticsearch.yml` / `schema.xml` / `solrconfig.xml` content
- `_cat/indices` JSON or Schema API output
- eDisMax query strings or Query DSL details
- Specific instance types or JVM heap sizes
- ZooKeeper / cluster manager configuration
- Authentication-backend specifics
- k-NN engine choice

You MAY capture if volunteered. You MUST NOT block on these because BSH intake completion is the priority.

## Recap and confirm

After intake, you MUST produce a short recap of what was provided. You MUST surface plausible estimates for any unknowns (avg document size, daily growth, peak QPS). You MUST ask the user to validate or correct before proceeding.

## Where this routes next

| Role | Next step |
|------|-----------|
| SRE | Step 3 (Compatibility scan / schema + query translation) leading; Step 4 (target shape); Step 5 (path); Step 7 (readiness) in support |
| DOP | Step 3 (Compatibility scan); Step 4 (target shape) sizing-focused; Step 5 (path); Step 6 (sizing) leading |
| BSH | Steps 4–7 framed as scope items with effort tiers; Step 8 (report) emphasizing timeline, milestones, blockers; cost questions deflected to <https://calculator.aws> |
