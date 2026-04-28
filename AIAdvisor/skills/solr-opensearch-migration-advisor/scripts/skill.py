"""
Main agent skill class for migrating from Apache Solr to OpenSearch.

The :class:`SolrToOpenSearchMigrationSkill` class acts as a high-level facade
that can be used as an agent tool in the OpenSearch ML agent framework.  Each
public method corresponds to a discrete migration capability that an agent can
invoke.

Session state is fully resumable: every call to :meth:`handle_message` loads
the existing :class:`~storage.SessionState` for the given *session_id*,
updates it, and persists it back through the configured
:class:`~storage.StorageBackend`.  Swapping the backend (e.g. from
:class:`~storage.FileStorage` to a database-backed implementation) requires
only passing a different backend to the constructor.
"""

from __future__ import annotations

import json
import os
import urllib.request
import urllib.error
from typing import Dict, Optional

from schema_converter import SchemaConverter
from query_converter import QueryConverter
from storage import StorageBackend, FileStorage, SessionState
from report import MigrationReport
from pricing_calculator import PricingCalculatorClient, PricingCalculatorError


class SolrToOpenSearchMigrationSkill:
    """Agent skill for migrating from Apache Solr to OpenSearch.

    Capabilities
    ------------
    * :meth:`convert_schema_xml`         — Translate ``schema.xml`` → OpenSearch mapping.
    * :meth:`convert_schema_json`        — Translate Solr Schema API JSON → OpenSearch mapping.
    * :meth:`convert_query`              — Translate Solr query string → Query DSL.
    * :meth:`get_migration_checklist`    — Return a human-readable migration checklist.
    * :meth:`get_field_type_mapping_reference` — Return a Solr→OpenSearch type table.
    * :meth:`generate_report`            — Generate a comprehensive migration report.
    * :meth:`handle_message`             — Transport-agnostic conversational interface.

    Session resumability
    --------------------
    All state (conversation history, discovered facts, migration progress, and
    incompatibilities) is stored via the injected :class:`~storage.StorageBackend`.
    Pass ``storage=InMemoryStorage()`` for ephemeral use or tests, or
    ``storage=FileStorage(path)`` (the default) for persistent sessions.

    Usage::

        skill = SolrToOpenSearchMigrationSkill()
        response = skill.handle_message("Convert this schema...", session_id="user-123")
        # Resume the same session later — state is automatically reloaded.
        response = skill.handle_message("Now generate the report.", session_id="user-123")
    """

    def __init__(
        self,
        storage: Optional[StorageBackend] = None,
        pricing_calculator_url: str = "http://opensearch-pricing-calculator:5050",
    ) -> None:
        self._schema_converter = SchemaConverter()
        self._query_converter = QueryConverter()
        self._storage = storage or FileStorage()
        self._steering_docs = self._load_steering_docs()
        self._aws_knowledge_url = "https://knowledge-mcp.global.api.aws"
        self._pricing_client = PricingCalculatorClient(base_url=pricing_calculator_url)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _load_steering_docs(self) -> Dict[str, str]:
        """Load steering documents from the data directory."""
        docs: Dict[str, str] = {}
        data_dir = os.path.join(
            os.path.dirname(os.path.dirname(__file__)), "data", "steering"
        )
        if os.path.exists(data_dir):
            for filename in os.listdir(data_dir):
                if filename.endswith(".md"):
                    with open(os.path.join(data_dir, filename), "r") as fh:
                        docs[filename[:-3]] = fh.read()
        return docs

    def _load_session(self, session_id: str) -> SessionState:
        """Load or create a session."""
        return self._storage.load_or_new(session_id)

    def _save_session(self, state: SessionState) -> None:
        self._storage.save(state)

    def _query_aws_knowledge(self, query: str, topic: str = "general") -> str:
        """Query the AWS Knowledge MCP Server for accurate AWS information.

        Falls back gracefully if the server is unreachable.

        Args:
            query: Natural-language search phrase.
            topic: Documentation topic (e.g. "general", "reference_documentation",
                   "troubleshooting", "current_awareness").

        Returns:
            Relevant documentation excerpts, or an empty string on failure.
        """
        payload = json.dumps({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {
                "name": "search_documentation",
                "arguments": {"search_phrase": query, "topics": [topic]},
            },
        }).encode("utf-8")
        req = urllib.request.Request(
            self._aws_knowledge_url,
            data=payload,
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                result = json.loads(resp.read().decode("utf-8"))
                content = result.get("result", {}).get("content", [])
                return "\n".join(
                    item.get("text", "")
                    for item in content
                    if item.get("type") == "text"
                )
        except Exception:  # noqa: BLE001 — degrade gracefully
            return ""

    # ------------------------------------------------------------------
    # Conversational interface
    # ------------------------------------------------------------------

    def handle_message(self, message: str, session_id: str) -> str:
        """Transport-agnostic core interface.

        Loads the session for *session_id*, processes *message*, updates all
        relevant state fields (history, facts, progress, incompatibilities),
        and persists the session before returning.

        Args:
            message:    The user's message.
            session_id: Unique identifier for the migration session.

        Returns:
            A string response from the advisor.
        """
        state = self._load_session(session_id)
        response = self._dispatch(message, state, session_id)
        state.append_turn(message, response)
        self._save_session(state)
        return response

    def _dispatch(self, message: str, state: SessionState, session_id: str) -> str:
        """Route *message* to the appropriate handler."""
        message_lc = message.lower()

        if "report" in message_lc:
            return self.generate_report(session_id)

        if self._looks_like_schema(message, message_lc):
            return self._handle_schema(message, state)

        if "query" in message_lc or "translate" in message_lc:
            return self._handle_query(message, message_lc, state)

        if "checklist" in message_lc:
            return self.get_migration_checklist()

        if "field type" in message_lc or "type mapping" in message_lc:
            return self.get_field_type_mapping_reference()

        if any(kw in message_lc for kw in ("pricing", "cost estimate", "price estimate", "how much")):
            return self._handle_pricing(message, message_lc, state)

        return self._handle_general(message, message_lc)

    # ------------------------------------------------------------------
    # Message handlers (one per intent)
    # ------------------------------------------------------------------

    _SCHEMA_START_TAG = "<schema"
    _SCHEMA_END_TAG = "</schema>"

    @staticmethod
    def _looks_like_schema(message: str, message_lc: str) -> bool:
        """Return True if *message* appears to contain or request a schema conversion."""
        if (
            SolrToOpenSearchMigrationSkill._SCHEMA_START_TAG in message and
                SolrToOpenSearchMigrationSkill._SCHEMA_END_TAG in message
        ):
            return True
        schema_keywords = ("schema" in message_lc or "migrate" in message_lc or "convert" in message_lc)
        return schema_keywords and SolrToOpenSearchMigrationSkill._SCHEMA_START_TAG in message

    def _handle_schema(self, message: str, state: SessionState) -> str:
        """Handle a schema conversion request."""
        schema_start = message.find(self._SCHEMA_START_TAG)
        schema_end = message.find(self._SCHEMA_END_TAG)
        if schema_start == -1 or schema_end == -1:
            return (
                "I detected you want to convert a schema, but I couldn't find "
                "the XML content. Please paste your full `schema.xml` content."
            )
        schema_xml = message[schema_start: schema_end + len(self._SCHEMA_END_TAG)]
        mapping = self.convert_schema_xml(schema_xml)
        state.set_fact("schema_migrated", True)
        state.advance_progress(1)
        return (
            f"I've converted your Solr schema to an OpenSearch mapping:"
            f"\n\n```json\n{mapping}\n```"
        )

    def _handle_query(self, message: str, message_lc: str, state: SessionState) -> str:
        """Handle a query translation request."""
        q = self._extract_query_text(message, message_lc)
        if not q:
            return "What query would you like me to translate?"
        try:
            dsl = self.convert_query(q)
        except ValueError:
            return (
                "I couldn't parse that query. Please provide a valid Solr "
                "query string (e.g. `title:opensearch AND year:[2020 TO *]`)."
            )
        state.advance_progress(3)
        return (
            f"The OpenSearch equivalent of your query is:"
            f"\n\n```json\n{dsl}\n```"
        )

    @staticmethod
    def _extract_query_text(message: str, message_lc: str) -> str:
        """Pull the raw Solr query string out of the user's message."""
        for keyword in ("query:", "query", "translate:"):
            idx = message_lc.find(keyword)
            if idx != -1:
                return message[idx + len(keyword):].strip().lstrip(": ").strip()
        return ""

    _OPENSEARCH_KEYWORDS = (
        "opensearch", "index", "shard", "replica", "mapping",
        "cluster", "node", "query dsl", "aggregation", "analyzer",
        "aws", "service", "region", "pricing", "instance",
    )

    def _handle_general(self, message: str, message_lc: str) -> str:
        """Fallback handler: try AWS Knowledge enrichment, else greet."""
        if any(kw in message_lc for kw in self._OPENSEARCH_KEYWORDS):
            aws_context = self._query_aws_knowledge(message, topic="general")
            if aws_context:
                return "Here is accurate information from AWS documentation:\n\n" + aws_context
        return (
            "I'm your Solr to OpenSearch migration advisor. How can I help you "
            "today? I can convert schemas, translate queries, or generate a "
            "migration report."
        )

    def _handle_pricing(self, message: str, message_lc: str, state: SessionState) -> str:
        """Handle a pricing estimate request via the opensearch-pricing-calculator.

        Checks whether the calculator is reachable. If not, instructs the user
        to start it. Otherwise, asks for the workload parameters needed to call
        the appropriate estimate endpoint.
        """
        if not self._pricing_client.health_check():
            return (
                "To calculate pricing estimates I need the **opensearch-pricing-calculator** "
                "to be running locally.\n\n"
                "**Start it with:**\n"
                "```bash\n"
                "# Clone the repo\n"
                "git clone https://github.com/opensearch-project/opensearch-migrations.git\n"
                "cd opensearch-migrations/AIAdvisor/opensearch-pricing-calculator\n\n"
                "# Build and run (requires Go 1.24+)\n"
                "go mod download\n"
                "go build -o opensearch-pricing-calculator .\n"
                "./opensearch-pricing-calculator\n"
                "```\n\n"
                "Or with Docker:\n"
                "```bash\n"
                "docker build -t opensearch-pricing-calculator .\n"
                "docker run -p 5050:5050 -p 8081:8081 opensearch-pricing-calculator\n"
                "```\n\n"
                "Once it is running on port 5050, ask me again and I'll calculate "
                "your pricing estimate."
            )

        # Determine workload type from context
        if "serverless" in message_lc:
            return self._pricing_prompt_serverless(state)
        if "vector" in message_lc:
            return self._pricing_prompt_vector(state)
        if "time" in message_lc and "series" in message_lc:
            return self._pricing_prompt_time_series(state)

        # Default: ask which workload type
        return (
            "I can estimate pricing for three OpenSearch workload types:\n\n"
            "1. **Search** — general-purpose search workload\n"
            "2. **Time-series** — log/metrics workload with hot/warm tiers\n"
            "3. **Vector** — k-NN / semantic search workload\n"
            "4. **Serverless** — OpenSearch Serverless collection\n\n"
            "Which workload type best describes your use case? "
            "Also let me know your approximate data size in GB and your AWS region."
        )

    def _pricing_prompt_search(self, state: SessionState) -> str:
        return (
            "For a **search workload** estimate, please provide:\n\n"
            "- Total data size (GB)\n"
            "- Number of Availability Zones (default: 3)\n"
            "- Number of replicas (default: 1)\n"
            "- Target shard size in GB (default: 25)\n"
            "- CPUs per shard (default: 1.5)\n"
            "- Pricing type: `OnDemand` or `Reserved` (default: OnDemand)\n"
            "- AWS region (e.g. `US East (N. Virginia)`)\n\n"
            "You can provide just the data size and region if you'd like defaults for the rest."
        )

    def _pricing_prompt_time_series(self, state: SessionState) -> str:
        return (
            "For a **time-series workload** estimate, please provide:\n\n"
            "- Total data size (GB)\n"
            "- Hot retention period in days (default: 14)\n"
            "- Warm retention period in days (default: 76)\n"
            "- Number of Availability Zones (default: 3)\n"
            "- Number of replicas (default: 1)\n"
            "- Target shard size in GB (default: 45)\n"
            "- CPUs per shard (default: 1.25)\n"
            "- Pricing type: `OnDemand` or `Reserved` (default: OnDemand)\n"
            "- AWS region (e.g. `US East (N. Virginia)`)\n\n"
            "You can provide just the data size and region if you'd like defaults for the rest."
        )

    def _pricing_prompt_vector(self, state: SessionState) -> str:
        return (
            "For a **vector search workload** estimate, please provide:\n\n"
            "- Number of vectors\n"
            "- Vector dimensions (e.g. 768 for BERT, 1536 for OpenAI ada-002)\n"
            "- Engine type: `hnswfp32`, `hnswfp16`, `hnswbq`, `ivffp32`, `ivffp16`, or `ivfbq` (default: `hnswfp16`)\n"
            "- Max edges / HNSW `m` parameter (default: 16)\n"
            "- Number of Availability Zones (default: 3)\n"
            "- Number of replicas (default: 1)\n"
            "- Pricing type: `OnDemand` or `Reserved` (default: OnDemand)\n"
            "- AWS region (e.g. `US East (N. Virginia)`)\n\n"
            "You can provide just the vector count, dimensions, and region if you'd like defaults for the rest."
        )

    def _pricing_prompt_serverless(self, state: SessionState) -> str:
        return (
            "For a **serverless collection** estimate, please provide:\n\n"
            "- Collection type: `timeSeries`, `search`, or `vector`\n"
            "- Daily index size (GB)\n"
            "- Days in hot storage (default: 1)\n"
            "- Days in warm storage (default: 6)\n"
            "- Minimum query rate (QPS, default: 1)\n"
            "- Maximum query rate (QPS, default: 1)\n"
            "- Hours per day at max query rate (default: 0)\n"
            "- AWS region code (e.g. `us-east-1`)\n"
            "- Multi-AZ redundancy: yes/no (default: yes)\n\n"
            "You can provide just the daily index size and region if you'd like defaults for the rest."
        )

    def estimate_pricing(
        self,
        workload_type: str,
        session_id: str,
        **kwargs,
    ) -> str:
        """Call the pricing calculator and return a formatted estimate.

        This is the primary public method for pricing. The skill's conversational
        interface collects parameters from the user; this method executes the
        actual HTTP call and formats the result.

        Args:
            workload_type: One of ``"search"``, ``"timeSeries"``, ``"vector"``,
                           or ``"serverless"``.
            session_id:    Session to store the estimate result in.
            **kwargs:      Workload-specific parameters forwarded to the
                           appropriate :class:`PricingCalculatorClient` method.

        Returns:
            A Markdown-formatted pricing estimate, or an error message.
        """
        state = self._load_session(session_id)
        try:
            if workload_type == "search":
                result = self._pricing_client.estimate_provisioned_search(**kwargs)
            elif workload_type == "timeSeries":
                result = self._pricing_client.estimate_provisioned_time_series(**kwargs)
            elif workload_type == "vector":
                result = self._pricing_client.estimate_provisioned_vector(**kwargs)
            elif workload_type == "serverless":
                result = self._pricing_client.estimate_serverless(**kwargs)
            else:
                return f"Unknown workload type '{workload_type}'. Use: search, timeSeries, vector, serverless."
        except PricingCalculatorError as exc:
            return f"Pricing calculator error: {exc}"

        summary = PricingCalculatorClient.format_estimate(result)
        state.set_fact("pricing_estimate", {"workload_type": workload_type, "result": result})
        self._save_session(state)
        return f"**OpenSearch pricing estimate ({workload_type}):**\n\n{summary}"

    # ------------------------------------------------------------------
    # Report generation
    # ------------------------------------------------------------------

    def generate_report(self, session_id: str) -> str:
        """Generate a comprehensive migration report for the session.

        The report prominently surfaces all incompatibilities collected during
        the migration workflow, grouped by severity (Breaking → Unsupported →
        Behavioral), followed by milestones, blockers, implementation points,
        and cost estimates.

        Args:
            session_id: The session identifier.

        Returns:
            A Markdown-formatted migration report.
        """
        state = self._storage.load_or_new(session_id)
        facts = state.facts

        milestones = [
            "Infrastructure setup and sizing",
            "Schema and analysis chain migration",
            "Data re-indexing and validation",
            "Application query and client migration",
            "Parallel testing and cutover",
        ]

        blockers: list[str] = []
        if not facts.get("schema_migrated"):
            blockers.append("Schema not yet analyzed for incompatibilities.")

        # Surface Breaking/Unsupported incompatibilities as explicit blockers.
        for inc in state.incompatibilities:
            if inc.severity in ("Breaking", "Unsupported"):
                blockers.append(f"[{inc.severity}] {inc.description}")

        ip = [
            "Map Solr field types to OpenSearch equivalents (see steering documents).",
            "Replace Solr copyField with OpenSearch copy_to.",
            "Update client libraries from SolrJ/SolrPy to OpenSearch clients.",
        ]

        # Append customization migration items collected in Step 4.
        for solr_item, os_solution in facts.get("customizations", {}).items():
            ip.append(f"Customization — {solr_item}: {os_solution}")

        costs = {
            "Infrastructure": (
                "Estimated 10% increase over Solr due to shard management overhead."
            ),
            "Effort": "Moderate (2-4 weeks for typical mid-sized workload).",
        }

        # Include pricing calculator estimate if one was collected this session.
        pricing = facts.get("pricing_estimate")
        if pricing:
            workload = pricing.get("workload_type", "unknown")
            result = pricing.get("result", {})
            summary = PricingCalculatorClient.format_estimate(result)
            costs[f"OpenSearch ({workload} workload)"] = summary

        report = MigrationReport(
            milestones=milestones,
            blockers=blockers,
            implementation_points=ip,
            cost_estimates=costs,
            incompatibilities=state.incompatibilities,
            client_integrations=state.client_integrations,
        )
        return report.generate()

    # ------------------------------------------------------------------
    # Schema conversion
    # ------------------------------------------------------------------

    def convert_schema_xml(self, schema_xml: str, *, indent: int = 2) -> str:
        """Convert a Solr ``schema.xml`` to an OpenSearch index mapping.

        Args:
            schema_xml: Full text content of a Solr ``schema.xml`` file.
            indent:     JSON indentation level for the returned string.

        Returns:
            A JSON string representing the OpenSearch index mapping.

        Raises:
            ValueError: If the XML cannot be parsed or is not a valid Solr schema.
        """
        mapping = self._schema_converter.convert_xml(schema_xml)
        return json.dumps(mapping, indent=indent)

    def convert_schema_json(self, schema_api_json: str, *, indent: int = 2) -> str:
        """Convert a Solr Schema API JSON document to an OpenSearch mapping.

        Args:
            schema_api_json: JSON string returned by the Solr Schema API.
            indent:          JSON indentation level for the returned string.

        Returns:
            A JSON string representing the OpenSearch index mapping.

        Raises:
            ValueError: If the JSON cannot be parsed or is missing required keys.
        """
        mapping = self._schema_converter.convert_json(schema_api_json)
        return json.dumps(mapping, indent=indent)

    # ------------------------------------------------------------------
    # Query conversion
    # ------------------------------------------------------------------

    def convert_query(self, solr_query: str, *, indent: int = 2) -> str:
        """Convert a Solr query string to an OpenSearch Query DSL JSON string.

        Args:
            solr_query: A Solr query string (the ``q`` parameter value).
            indent:     JSON indentation level for the returned string.

        Returns:
            A JSON string representing the OpenSearch Query DSL.

        Raises:
            ValueError: If ``solr_query`` is empty.
        """
        dsl = self._query_converter.convert(solr_query)
        return json.dumps(dsl, indent=indent)

    # ------------------------------------------------------------------
    # Migration guidance
    # ------------------------------------------------------------------

    def get_migration_checklist(self) -> str:
        """Return a human-readable checklist of migration steps."""
        return _MIGRATION_CHECKLIST

    def get_field_type_mapping_reference(self) -> str:
        """Return a Markdown reference table of Solr → OpenSearch field type mappings."""
        from schema_converter import SOLR_TYPE_TO_OPENSEARCH

        lines: list[str] = [
            "| Solr Field Type | OpenSearch Type |",
            "|---|---|",
        ]
        seen: dict[str, str] = {}
        for solr_type, os_type in SOLR_TYPE_TO_OPENSEARCH.items():
            short = solr_type.replace("solr.", "")
            if short not in seen:
                seen[short] = os_type
                lines.append(f"| {short} | {os_type} |")
        return "\n".join(lines)


# ---------------------------------------------------------------------------
# Static content
# ---------------------------------------------------------------------------

_MIGRATION_CHECKLIST: str = """\
Apache Solr → OpenSearch Migration Checklist
=============================================

1. PREPARATION
   [ ] Back up all Solr collections and configuration files.
   [ ] Document all Solr field types, fields, and dynamic fields.
   [ ] Record all custom tokenizers, filters, and analyzers.
   [ ] List all Solr request handlers, search components, and plugins.
   [ ] Identify any SolrCloud-specific configuration (ZooKeeper, shards,
       replicas).

2. SCHEMA / MAPPING MIGRATION
   [ ] Convert Solr schema.xml to an OpenSearch index mapping using the
       convert_schema_xml() skill method.
   [ ] Review the generated mapping for accuracy and completeness.
   [ ] Map custom Solr field types to appropriate OpenSearch types.
   [ ] Translate custom analyzers (char filters, tokenizers, token filters)
       to OpenSearch analysis settings.
   [ ] Handle multi-valued fields (OpenSearch arrays are native).
   [ ] Replace Solr copyField directives with OpenSearch copy_to.

3. INDEX SETTINGS
   [ ] Define OpenSearch index settings (number_of_shards,
       number_of_replicas, refresh_interval, etc.).
   [ ] Translate Solr synonyms.txt and stopwords.txt to OpenSearch analysis
       synonym / stop token filter configuration.
   [ ] Configure dynamic mappings or disable them to match Solr's
       schemaFactory setting.

4. QUERY MIGRATION
   [ ] Identify all query types in use (standard, edismax, dismax, spatial,
       facet, etc.).
   [ ] Convert standard Solr queries to OpenSearch Query DSL using the
       convert_query() skill method.
   [ ] Translate eDismax parameters (qf, pf, mm, boost, etc.) to
       OpenSearch multi_match / function_score queries.
   [ ] Migrate facet queries to OpenSearch aggregations.
   [ ] Replace Solr highlighting parameters with OpenSearch highlight API.
   [ ] Convert Solr spatial queries to OpenSearch geo_distance / geo_shape
       queries.
   [ ] Migrate Solr MoreLikeThis queries to OpenSearch more_like_this.

5. DATA MIGRATION
   [ ] Choose a migration strategy:
       a) Re-index from source data (recommended for clean migration).
       b) Export from Solr via Data Import Handler or curl and import into
          OpenSearch using the Bulk API.
   [ ] Validate document counts and spot-check field values after migration.

6. APPLICATION / CLIENT MIGRATION
   [ ] Replace the Solr Java/Python/Ruby client with the appropriate
       OpenSearch client.
   [ ] Update HTTP endpoints (Solr uses /solr/<collection>/select;
       OpenSearch uses /<index>/_search).
   [ ] Migrate Solr Admin UI usage to OpenSearch Dashboards.
   [ ] Replace SolrCloud management API calls with OpenSearch Cluster API
       calls.

7. TESTING
   [ ] Run query equivalence tests: same inputs should return equivalent
       results from both Solr and OpenSearch.
   [ ] Validate relevance scores and ranking.
   [ ] Load-test the OpenSearch cluster under production-level traffic.
   [ ] Test failover and replica behaviour.

8. CUTOVER
   [ ] Run both systems in parallel and compare results.
   [ ] Switch application traffic to OpenSearch.
   [ ] Monitor OpenSearch cluster health and query latency.
   [ ] Decommission Solr after a suitable stabilization period.

USEFUL OPENSEARCH DOCUMENTATION
--------------------------------
* Index API:        https://opensearch.org/docs/latest/api-reference/index-apis/
* Mapping:          https://opensearch.org/docs/latest/field-types/
* Query DSL:        https://opensearch.org/docs/latest/query-dsl/
* Aggregations:     https://opensearch.org/docs/latest/aggregations/
* Analysis:         https://opensearch.org/docs/latest/analyzers/
* Migration guide:  https://opensearch.org/docs/latest/migration-guide/
"""
