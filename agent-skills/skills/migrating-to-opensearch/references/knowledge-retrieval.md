# Retrieval recipes — topic → tool → URL

You MUST draft from the skill's embedded reference tables first and verify the **version-volatile** claims in the single batched pass (SKILL.md Step 8) before delivery — NOT one retrieval per claim. Stable-core facts (fork points, type-system removals, schema field-type mappings, transformation rules, severity rubric, sizing constants, gotcha-detection rules) are embedded in the references and need no retrieval. Version-volatile facts (current feature-parity rows, supported-plugin list, current instance families, regional availability, NextGen / Migration Assistant capability matrices, per-version k-NN default engine, exact per-version limits) MUST be confirmed against the live docs in the batch — do not ship one unverified. This file is the recipe for that batch.

## Three retrieval primitives

- **`aws___search_documentation`** / **`aws___read_documentation`** (AWS MCP server) — for AWS-domain URLs (`docs.aws.amazon.com`, `aws.amazon.com`, `repost.aws/knowledge-center`, `docs.amplify.aws`, `strandsagents.com`).
- **`WebFetch <url>`** — for non-AWS public web (OpenSearch Project, Elastic).
- **`gh api <path>`** — for raw GitHub content (release notes, raw markdown).

> **Fallback when the AWS MCP server is not available** (e.g., running in Claude Code, Cursor, Copilot, or any host without the AWS MCP server bound):
> - For any `aws___read_documentation <url>`: visit the URL directly in a browser, or `curl -sSL <url>`.
> - For any `aws___search_documentation` query: search at <https://docs.aws.amazon.com/> or use a general web search.
> - For `aws___get_regional_availability`: use `aws ec2 describe-regions --query 'Regions[].RegionName'` and `aws opensearch list-versions --region <region>`.
> - For `aws___list_regions`: use `aws ec2 describe-regions` or visit <https://docs.aws.amazon.com/general/latest/gr/rande.html>.
>
> The skill produces the same assessment regardless of host. The MCP-tool form is the recommended call when the server is bound; the URL/CLI form is the fallback when it is not.

## Amazon OpenSearch Service (managed) — `aws___read_documentation <url>`

Sizing & operational best practice — `bp.html`, `bp-storage.html`, `bp-sharding.html`, `bp-instances.html`, `sizing-domains.html`, `petabyte-scale.html`, `handling-errors.html`.

Topology / HA — `managedomains-multiaz.html`, `managedomains-dedicatedmasternodes.html`, `auto-tune.html`.

Tiering — `ultrawarm.html`, `cold-storage.html`.

Cross-cluster & data movement — `replication.html`, `cross-cluster-search.html`, `remote-reindex.html`, `managedomains-snapshots.html`, `snapshot-based-migration.html`, `migration.html` (snapshot tutorial), `version-migration.html`. (Note: `standard-and-extended-support.html` returned a generic-shell page on last verification — search the developer-guide table of contents for the current "extended support" page rather than embedding the URL here.)

Service features — `ism.html`, `supported-plugins.html`, `supported-instance-types.html`, `rename.html` (alarm metric renames), `managedomains-signing-service-requests.html` (SigV4 client examples — the older `request-signing.html` slug is a generic-shell redirect).

Security — `security.html` (security best practices — Security Considerations checklist verification).

Observability — `managedomains-cloudwatchmetrics.html`, `cloudwatch-alarms.html`.

Sharding blog (third-party but AWS-hosted) — <https://aws.amazon.com/blogs/big-data/amazon-opensearch-service-101-how-many-shards-do-i-need/>.

All under `https://docs.aws.amazon.com/opensearch-service/latest/developerguide/<page>` unless noted.

## Amazon OpenSearch Serverless NextGen

NextGen capability + sizing + supported-source facts drift across NextGen iterations. **Tag any specific NextGen feature, limit, supported collection type, or supported migration source row `[verify]` and confirm it in the Step 8 batched pass** — do NOT recite NextGen specifics from training memory unverified. The companion `aoss-nextgen` skill packs the operator-side gotchas (NextGen vs Classic signals, the SDK/CLI version pre-flight, the 401-during-warmup window, cleanup ordering); load it whenever target shape lands on Serverless NextGen.

Canonical pages (same base URL): `serverless-overview.html`, `serverless-comparison.html`, `serverless-scaling.html`, `serverless-vector-search.html`, `serverless-genref.html`.

## OpenSearch Ingestion (OSI)

AWS doc: `ingestion.html`, `osis-features-overview.html` (OCU scaling and `max_units`). OpenSearch source plugin (engine doc): <https://docs.opensearch.org/latest/data-prepper/pipelines/configuration/sources/opensearch/>.

## Migration Assistant

- AWS Solutions doc (solution overview): <https://docs.aws.amazon.com/solutions/latest/migration-assistant-for-amazon-opensearch-service/solution-overview.html>
- AWS Solutions cost guide: <https://docs.aws.amazon.com/solutions/latest/migration-assistant-for-amazon-opensearch-service/cost.html>
- Project doc (decision criteria, architecture): <https://docs.opensearch.org/latest/migration-assistant/>
- Repo readme + latest release: `gh api repos/opensearch-project/opensearch-migrations/readme` and `.../releases/latest`

## OpenSearch Project (engine docs — `WebFetch` or `gh api`)

- Top-level: <https://docs.opensearch.org/latest/>
- Mappings, field types, analyzers: `<base>/mappings/`, `<base>/field-types/`, `<base>/field-types/nested/`, `<base>/analyzers/supported-analyzers/index/`, `<base>/analyzers/tokenizers/index/`, `<base>/analyzers/token-filters/index/`, `<base>/analyzers/character-filters/`
- Query DSL: `<base>/query-dsl/compound/function-score/`
- k-NN engines/methods: `<base>/search-plugins/knn/knn-index/` (or `gh api repos/opensearch-project/documentation-website/contents/_mappings/supported-field-types/knn-methods-engines.md`)
- ISM policies, breaking changes (raw md): `gh api repos/opensearch-project/documentation-website/contents/_im-plugin/ism/policies.md` and `.../_about/breaking-changes.md` (also browseable at <https://docs.opensearch.org/latest/about/breaking-changes/>)
- Observability / Alerting / Anomaly Detection / ISM landing pages: `<base>/observing-your-data/alerting/index/`, `<base>/observing-your-data/ad/index/`, `<base>/im-plugin/ism/index/`
- Upgrade / migrate: <https://docs.opensearch.org/latest/upgrade-or-migrate/>, <https://opensearch.org/docs/latest/install-and-configure/upgrade-opensearch/upgrade-paths/>
- 3.0 announcement: <https://opensearch.org/blog/unveiling-opensearch-3-0/>
- OSB benchmarking: <https://opensearch.org/docs/latest/benchmark/> (workload catalog: <https://github.com/opensearch-project/opensearch-benchmark-workloads>)
- Apache Solr docs (used for Solr-side mapping inputs): <https://solr.apache.org/guide/solr/latest/indexing-guide/tokenizers.html>, <https://solr.apache.org/guide/solr/latest/indexing-guide/filters.html>

## Elasticsearch (Elastic)

- Removal of types (definitive): <https://www.elastic.co/guide/en/elasticsearch/reference/7.17/removal-of-types.html>
- Logstash ES input plugin: <https://www.elastic.co/guide/en/logstash/current/plugins-inputs-elasticsearch.html>
- Reindex from remote (engine ref): <https://www.elastic.co/guide/en/elasticsearch/reference/7.10/docs-reindex.html>

## AWS Prescriptive Guidance

- Solr → OpenSearch migration (informs every Solr-source assessment): <https://docs.aws.amazon.com/prescriptive-guidance/latest/migration-solr-opensearch/welcome.html>

## Pricing

This skill does NOT estimate dollar costs (see [`scope.md`](scope.md)). Sizing recommendations (compute, storage, shards, JVM heap, OCUs) are produced by the agent; the customer plugs them into the AWS Pricing Calculator for an authoritative figure with RI / Savings Plan / EDP discounts applied.

- AWS Pricing Calculator: <https://calculator.aws>
- Pricing marketing page: `https://aws.amazon.com/opensearch-service/pricing/`
- RI doc: `ri.html` (developer guide)
- MA cost guide: see Migration Assistant section above

## Regional availability

- `aws___get_regional_availability resource_type=product resource_identifier=opensearch regions=<region>[,...]`
- `aws___list_regions`
- Fallback: `aws ec2 describe-regions` + `aws opensearch list-versions --region <region>`

## Sibling AWS MCP skills

If the user asks to provision the target after assessment, you MUST retrieve and delegate to the `aws-setup` skill. You MUST NOT re-implement provisioning logic here because duplicating the sibling skill leads to drift and inconsistent recommendations.

Skill discovery: `aws___retrieve_skill skill_name=aws_setup` and `aws___search_documentation "opensearch agent skills" --topic agent_skills --limit 10` (canonical fallback applies — see top of this file). When the AWS MCP server is not bound, browse <https://github.com/aws/agent-toolkit-for-aws> (path: `skills/aws-setup/`).

## Citation discipline

Every **version-volatile** claim in a Migration Assessment report MUST, after the Step 8 batched pass:

1. Be confirmed against the live source (stable-core facts drafted from the embedded references do not need a live fetch, but you SHOULD still name the reference file/section they came from).
2. Cite the live URL in the Citations section.
3. Include a retrieval timestamp.
4. Use the right tool for the domain (AWS Knowledge MCP refuses non-AWS URLs).

You MUST self-check before delivery: no `[verify]` markers remain; every version-volatile claim traces to a cited URL with a retrieval timestamp in the Citations section; cite the URLs you actually used (typically ≥ 3) rather than padding to a fixed floor. The Citations section — not inline-per-line citations — is the canonical provenance record, which keeps the report dense and under the `max_tokens` ceiling.

## Batching the verification pass

To keep the verification fast, gather all `[verify]` tags first, then retrieve by domain in as few calls as possible (and concurrently where the host allows):

- **One AWS-docs sweep** — managed-AOS plugin support, instance families, NextGen capability/sizing, Migration Assistant caps, ISM. (`aws___read_documentation` / `aws___search_documentation`.)
- **One OpenSearch-project sweep** — current k-NN default engine, breaking-changes for the version step, analyzer/tokenizer class names. (`WebFetch` / `gh api`.)
- **One regional-availability call** — `aws___get_regional_availability` (or the CLI fallback below).
- **One Elastic-docs fetch** if the report makes a type-removal or ES-feature claim.

A typical assessment resolves in 3–4 batched calls, not dozens of per-claim calls.
