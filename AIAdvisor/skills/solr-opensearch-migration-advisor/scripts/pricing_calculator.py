"""
Client for the opensearch-pricing-calculator HTTP API (port 5050).

See: https://github.com/opensearch-project/opensearch-migrations/tree/main/AIAdvisor/opensearch-pricing-calculator

The calculator must be running locally before calling any estimate method.
Start it with:

    go build -o opensearch-pricing-calculator .
    ./opensearch-pricing-calculator

or via Docker:

    docker run -p 5050:5050 -p 8081:8081 opensearch-pricing-calculator

Usage::

    client = PricingCalculatorClient()

    # Managed cluster — search workload
    result = client.estimate_provisioned_search(
        size_gb=200, azs=3, replicas=1,
        target_shard_size_gb=25, cpus_per_shard=1.5,
        region="US East (N. Virginia)",
    )

    # Managed cluster — time-series workload
    result = client.estimate_provisioned_time_series(
        size_gb=500, azs=3, replicas=1,
        hot_retention_days=14, warm_retention_days=76,
        target_shard_size_gb=45, cpus_per_shard=1.25,
        region="US East (N. Virginia)",
    )

    # Managed cluster — vector workload
    result = client.estimate_provisioned_vector(
        vector_count=10_000_000, dimensions=768,
        engine_type="hnswfp16", max_edges=16,
        azs=3, replicas=1,
        region="US East (N. Virginia)",
    )

    # Serverless collection
    result = client.estimate_serverless(
        collection_type="timeSeries",
        daily_index_size_gb=10, days_in_hot=1, days_in_warm=6,
        min_query_rate=1, max_query_rate=1, hours_at_max_rate=0,
        region="us-east-1", redundancy=True,
    )
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Any, Dict, Literal, Optional


class PricingCalculatorError(Exception):
    """Raised when the pricing calculator returns an error or is unreachable."""


class PricingCalculatorClient:
    """HTTP client for the opensearch-pricing-calculator API.

    Args:
        base_url: Base URL of the running calculator service.
                  Defaults to ``http://localhost:5050``.
        timeout:  Request timeout in seconds. Defaults to 30.
    """

    def __init__(
        self,
        base_url: str = "http://localhost:5050",
        timeout: int = 30,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _post(self, path: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        """POST *payload* to *path* and return the parsed JSON response.

        Raises:
            PricingCalculatorError: On HTTP errors or connection failures.
        """
        url = f"{self.base_url}{path}"
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=data,
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise PricingCalculatorError(
                f"HTTP {exc.code} from pricing calculator at {url}: {body}"
            ) from exc
        except urllib.error.URLError as exc:
            raise PricingCalculatorError(
                f"Could not reach pricing calculator at {url}. "
                "Make sure it is running on port 5050. "
                f"Details: {exc.reason}"
            ) from exc

    def _get(self, path: str) -> Any:
        """GET *path* and return the parsed JSON response."""
        url = f"{self.base_url}{path}"
        req = urllib.request.Request(url, method="GET")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise PricingCalculatorError(
                f"HTTP {exc.code} from pricing calculator at {url}: {body}"
            ) from exc
        except urllib.error.URLError as exc:
            raise PricingCalculatorError(
                f"Could not reach pricing calculator at {url}. "
                "Make sure it is running on port 5050. "
                f"Details: {exc.reason}"
            ) from exc

    # ------------------------------------------------------------------
    # Managed (provisioned) cluster estimates
    # ------------------------------------------------------------------

    def estimate_provisioned_search(
        self,
        size_gb: float,
        azs: int = 3,
        replicas: int = 1,
        target_shard_size_gb: float = 25.0,
        cpus_per_shard: float = 1.5,
        pricing_type: Literal["OnDemand", "Reserved"] = "OnDemand",
        region: str = "US East (N. Virginia)",
    ) -> Dict[str, Any]:
        """Estimate costs for a managed OpenSearch search workload.

        Args:
            size_gb:               Total data size in GB.
            azs:                   Number of Availability Zones.
            replicas:              Number of replicas per primary shard.
            target_shard_size_gb:  Target size per shard in GB.
            cpus_per_shard:        CPU cores allocated per shard.
            pricing_type:          ``"OnDemand"`` or ``"Reserved"``.
            region:                AWS region display name
                                   (e.g. ``"US East (N. Virginia)"``).

        Returns:
            Parsed JSON response from the calculator.
        """
        return self._post("/provisioned/estimate", {
            "search": {
                "size": size_gb,
                "azs": azs,
                "replicas": replicas,
                "targetShardSize": target_shard_size_gb,
                "CPUsPerShard": cpus_per_shard,
                "pricingType": pricing_type,
                "region": region,
            }
        })

    def estimate_provisioned_time_series(
        self,
        size_gb: float,
        azs: int = 3,
        replicas: int = 1,
        hot_retention_days: int = 14,
        warm_retention_days: int = 76,
        target_shard_size_gb: float = 45.0,
        cpus_per_shard: float = 1.25,
        pricing_type: Literal["OnDemand", "Reserved"] = "OnDemand",
        region: str = "US East (N. Virginia)",
    ) -> Dict[str, Any]:
        """Estimate costs for a managed OpenSearch time-series workload.

        Args:
            size_gb:               Total data size in GB.
            azs:                   Number of Availability Zones.
            replicas:              Number of replicas per primary shard.
            hot_retention_days:    Days data is kept in hot storage.
            warm_retention_days:   Days data is kept in warm storage.
            target_shard_size_gb:  Target size per shard in GB.
            cpus_per_shard:        CPU cores allocated per shard.
            pricing_type:          ``"OnDemand"`` or ``"Reserved"``.
            region:                AWS region display name.

        Returns:
            Parsed JSON response from the calculator.
        """
        return self._post("/provisioned/estimate", {
            "timeSeries": {
                "size": size_gb,
                "azs": azs,
                "replicas": replicas,
                "hotRetentionPeriod": hot_retention_days,
                "warmRetentionPeriod": warm_retention_days,
                "targetShardSize": target_shard_size_gb,
                "CPUsPerShard": cpus_per_shard,
                "pricingType": pricing_type,
                "region": region,
            }
        })

    def estimate_provisioned_vector(
        self,
        vector_count: int,
        dimensions: int,
        engine_type: Literal[
            "hnswfp32", "hnswfp16", "hnswbq",
            "ivffp32", "ivffp16", "ivfbq"
        ] = "hnswfp16",
        max_edges: int = 16,
        azs: int = 3,
        replicas: int = 1,
        pricing_type: Literal["OnDemand", "Reserved"] = "OnDemand",
        region: str = "US East (N. Virginia)",
    ) -> Dict[str, Any]:
        """Estimate costs for a managed OpenSearch vector search workload.

        Args:
            vector_count:  Number of vectors to index.
            dimensions:    Vector dimensionality (e.g. 768 for BERT embeddings).
            engine_type:   HNSW or IVF variant with precision suffix
                           (``fp32``, ``fp16``, ``bq``).
            max_edges:     HNSW ``m`` parameter (max edges per node).
            azs:           Number of Availability Zones.
            replicas:      Number of replicas per primary shard.
            pricing_type:  ``"OnDemand"`` or ``"Reserved"``.
            region:        AWS region display name.

        Returns:
            Parsed JSON response from the calculator.
        """
        return self._post("/provisioned/estimate", {
            "vector": {
                "vectorCount": vector_count,
                "dimensionsCount": dimensions,
                "vectorEngineType": engine_type,
                "maxEdges": max_edges,
                "azs": azs,
                "replicas": replicas,
                "pricingType": pricing_type,
                "region": region,
            }
        })

    # ------------------------------------------------------------------
    # Serverless estimates
    # ------------------------------------------------------------------

    def estimate_serverless(
        self,
        collection_type: Literal["timeSeries", "search", "vector"],
        daily_index_size_gb: float,
        days_in_hot: int = 1,
        days_in_warm: int = 6,
        min_query_rate: float = 1.0,
        max_query_rate: float = 1.0,
        hours_at_max_rate: float = 0.0,
        region: str = "us-east-1",
        redundancy: bool = True,
    ) -> Dict[str, Any]:
        """Estimate costs for an OpenSearch Serverless collection.

        Args:
            collection_type:       ``"timeSeries"``, ``"search"``, or ``"vector"``.
            daily_index_size_gb:   GB of data indexed per day.
            days_in_hot:           Days data is retained in hot storage.
            days_in_warm:          Days data is retained in warm storage.
            min_query_rate:        Minimum queries per second.
            max_query_rate:        Peak queries per second.
            hours_at_max_rate:     Hours per day running at peak query rate.
            region:                AWS region code (e.g. ``"us-east-1"``).
            redundancy:            Whether to enable multi-AZ redundancy.

        Returns:
            Parsed JSON response from the calculator.
        """
        workload: Dict[str, Any] = {
            "dailyIndexSize": daily_index_size_gb,
            "daysInHot": days_in_hot,
            "daysInWarm": days_in_warm,
            "minQueryRate": min_query_rate,
            "maxQueryRate": max_query_rate,
            "hoursAtMaxRate": hours_at_max_rate,
        }
        return self._post("/serverless/v2/estimate", {
            collection_type: workload,
            "region": region,
            "redundancy": redundancy,
        })

    # ------------------------------------------------------------------
    # Reference data helpers
    # ------------------------------------------------------------------

    def get_regions(self, deployment: Literal["provisioned", "serverless"] = "provisioned") -> Any:
        """Return available AWS regions for the given deployment type."""
        return self._get(f"/{deployment}/regions")

    def get_pricing_options(self) -> Any:
        """Return available pricing tier options (OnDemand, Reserved, etc.)."""
        return self._get("/provisioned/pricingOptions")

    def get_instance_families(self, region: str) -> Any:
        """Return available instance families for *region*.

        Args:
            region: URL-encoded AWS region display name
                    (e.g. ``"US East (N. Virginia)"``).
        """
        import urllib.parse
        encoded = urllib.parse.quote(region, safe="")
        return self._get(f"/provisioned/instanceFamilyOptions/{encoded}")

    def health_check(self) -> bool:
        """Return True if the calculator service is reachable and healthy."""
        try:
            self._get("/health")
            return True
        except (PricingCalculatorError, OSError):
            return False

    # ------------------------------------------------------------------
    # Convenience: format estimate as a human-readable summary
    # ------------------------------------------------------------------

    @staticmethod
    def format_estimate(result: Dict[str, Any]) -> str:
        """Return a compact Markdown summary of an estimate response.

        Handles both provisioned (clusterConfigs) and serverless (price.month)
        response shapes. Falls back to pretty-printed JSON for unknown shapes.
        """
        try:
            lines: list[str] = []

            # Provisioned: response contains a ranked list of cluster configs.
            configs = result.get("clusterConfigs")
            if configs and isinstance(configs, list) and configs:
                best = configs[0]
                lines.append(f"- **Lowest monthly cost:** ${best['totalCost']:,.2f}")
                hot = best.get("hotNodes", {})
                if hot.get("type"):
                    lines.append(f"- **Hot node type:** {hot['type']} × {hot.get('count', '?')}")
                leader = best.get("leaderNodes", {})
                if leader.get("type"):
                    lines.append(f"- **Manager node type:** {leader['type']} × {leader.get('count', '?')}")
                if len(configs) > 1:
                    lines.append(
                        f"- **Configurations evaluated:** {len(configs)} "
                        f"(range ${configs[-1]['totalCost']:,.2f} – ${best['totalCost']:,.2f}/mo)"
                    )
                return "\n".join(lines)

            # Serverless: response contains a "price" dict with day/month/year breakdowns.
            price = result.get("price")
            if price and isinstance(price, dict):
                month = price.get("month", {})
                if "total" in month:
                    lines.append(f"- **Monthly cost:** ${month['total']:,.2f}")
                if "indexOcu" in month:
                    lines.append(f"  - Index OCU: ${month['indexOcu']:,.2f}")
                if "searchOcu" in month:
                    lines.append(f"  - Search OCU: ${month['searchOcu']:,.2f}")
                if "s3Storage" in month:
                    lines.append(f"  - S3 storage: ${month['s3Storage']:,.2f}")
                year = price.get("year", {})
                if "total" in year:
                    lines.append(f"- **Annual cost:** ${year['total']:,.2f}")
                if lines:
                    return "\n".join(lines)

            # Legacy flat shape (monthlyCost / annualCost).
            if "monthlyCost" in result:
                lines.append(f"- **Monthly cost:** ${result['monthlyCost']:,.2f}")
            if "annualCost" in result:
                lines.append(f"- **Annual cost:** ${result['annualCost']:,.2f}")
            if "instanceType" in result:
                lines.append(f"- **Instance type:** {result['instanceType']}")
            if "instanceCount" in result:
                lines.append(f"- **Instance count:** {result['instanceCount']}")
            if "storageGB" in result:
                lines.append(f"- **Storage:** {result['storageGB']:,} GB")
            if "shardCount" in result:
                lines.append(f"- **Shards:** {result['shardCount']}")
            if lines:
                return "\n".join(lines)
        except (TypeError, KeyError):
            pass
        return f"```json\n{json.dumps(result, indent=2)}\n```"
