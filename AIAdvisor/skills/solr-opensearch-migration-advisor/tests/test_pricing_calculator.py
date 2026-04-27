"""Tests for PricingCalculatorClient."""

import json
import unittest
import sys
import os
import io
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError, URLError

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))

from pricing_calculator import (
    PricingCalculatorClient,
    PricingCalculatorError,
    PRICING_CALCULATOR_URL,
)


MOCK_PROVISIONED = {
    "monthlyCost": 1234.56,
    "annualCost": 14814.72,
    "instanceType": "r6g.2xlarge.search",
    "instanceCount": 6,
    "storageGB": 600,
    "shardCount": 8,
}

MOCK_SERVERLESS = {
    "monthlyCost": 450.00,
    "annualCost": 5400.00,
}


def _mock_urlopen(response_body: dict):
    """Return a context-manager mock that yields a fake HTTP response."""
    mock_resp = MagicMock()
    mock_resp.read.return_value = json.dumps(response_body).encode("utf-8")
    mock_resp.__enter__ = lambda s: s
    mock_resp.__exit__ = MagicMock(return_value=False)
    return mock_resp


class TestPricingCalculatorClient(unittest.TestCase):

    def setUp(self):
        self.client = PricingCalculatorClient()

    # ------------------------------------------------------------------
    # Constructor / env-var
    # ------------------------------------------------------------------

    def test_default_base_url_from_module_constant(self):
        client = PricingCalculatorClient()
        self.assertEqual(client.base_url, PRICING_CALCULATOR_URL.rstrip("/"))

    def test_explicit_base_url_overrides_env(self):
        client = PricingCalculatorClient(base_url="http://custom:9999/")
        self.assertEqual(client.base_url, "http://custom:9999")

    def test_none_base_url_falls_back_to_env(self):
        client = PricingCalculatorClient(base_url=None)
        self.assertEqual(client.base_url, PRICING_CALCULATOR_URL.rstrip("/"))

    def test_env_var_override(self):
        """PRICING_CALCULATOR_URL env var is read at module import time."""
        original = PRICING_CALCULATOR_URL
        client = PricingCalculatorClient()
        self.assertEqual(client.base_url, original.rstrip("/"))

    # ------------------------------------------------------------------
    # Provisioned estimates
    # ------------------------------------------------------------------

    @patch("urllib.request.urlopen")
    def test_estimate_provisioned_search(self, mock_urlopen):
        mock_urlopen.return_value = _mock_urlopen(MOCK_PROVISIONED)
        result = self.client.estimate_provisioned_search(
            size_gb=200, azs=3, replicas=1,
            target_shard_size_gb=25, cpus_per_shard=1.5,
            region="US East (N. Virginia)",
        )
        self.assertEqual(result["monthlyCost"], 1234.56)
        # Verify the request body contained the "search" key
        call_args = mock_urlopen.call_args[0][0]
        body = json.loads(call_args.data.decode())
        self.assertIn("search", body)
        self.assertEqual(body["search"]["size"], 200)

    @patch("urllib.request.urlopen")
    def test_estimate_provisioned_time_series(self, mock_urlopen):
        mock_urlopen.return_value = _mock_urlopen(MOCK_PROVISIONED)
        result = self.client.estimate_provisioned_time_series(
            size_gb=500, hot_retention_days=14, warm_retention_days=76,
            region="US East (N. Virginia)",
        )
        self.assertEqual(result["instanceCount"], 6)
        body = json.loads(mock_urlopen.call_args[0][0].data.decode())
        self.assertIn("timeSeries", body)
        self.assertEqual(body["timeSeries"]["hotRetentionPeriod"], 14)

    @patch("urllib.request.urlopen")
    def test_estimate_provisioned_vector(self, mock_urlopen):
        mock_urlopen.return_value = _mock_urlopen(MOCK_PROVISIONED)
        result = self.client.estimate_provisioned_vector(
            vector_count=10_000_000, dimensions=768,
            engine_type="hnswfp16", max_edges=16,
            region="US East (N. Virginia)",
        )
        self.assertIn("monthlyCost", result)
        body = json.loads(mock_urlopen.call_args[0][0].data.decode())
        self.assertIn("vector", body)
        self.assertEqual(body["vector"]["vectorCount"], 10_000_000)
        self.assertEqual(body["vector"]["vectorEngineType"], "hnswfp16")

    # ------------------------------------------------------------------
    # Serverless estimate
    # ------------------------------------------------------------------

    @patch("urllib.request.urlopen")
    def test_estimate_serverless(self, mock_urlopen):
        mock_urlopen.return_value = _mock_urlopen(MOCK_SERVERLESS)
        result = self.client.estimate_serverless(
            collection_type="timeSeries",
            daily_index_size_gb=10,
            days_in_hot=1, days_in_warm=6,
            region="us-east-1", redundancy=True,
        )
        self.assertEqual(result["monthlyCost"], 450.00)
        body = json.loads(mock_urlopen.call_args[0][0].data.decode())
        self.assertIn("timeSeries", body)
        self.assertEqual(body["region"], "us-east-1")
        self.assertTrue(body["redundancy"])

    # ------------------------------------------------------------------
    # Reference data
    # ------------------------------------------------------------------

    @patch("urllib.request.urlopen")
    def test_get_regions_provisioned(self, mock_urlopen):
        mock_urlopen.return_value = _mock_urlopen(["US East (N. Virginia)", "EU West (Ireland)"])
        result = self.client.get_regions("provisioned")
        self.assertIn("US East (N. Virginia)", result)

    @patch("urllib.request.urlopen")
    def test_get_regions_serverless(self, mock_urlopen):
        mock_urlopen.return_value = _mock_urlopen(["us-east-1", "eu-west-1"])
        result = self.client.get_regions("serverless")
        self.assertIn("us-east-1", result)

    @patch("urllib.request.urlopen")
    def test_get_pricing_options(self, mock_urlopen):
        mock_urlopen.return_value = _mock_urlopen(["OnDemand", "Reserved"])
        result = self.client.get_pricing_options()
        self.assertIn("OnDemand", result)

    @patch("urllib.request.urlopen")
    def test_get_instance_families(self, mock_urlopen):
        mock_urlopen.return_value = _mock_urlopen({"families": ["r6g", "m6g"]})
        result = self.client.get_instance_families("US East (N. Virginia)")
        self.assertEqual(result["families"], ["r6g", "m6g"])
        # Verify the region was URL-encoded in the request path
        req = mock_urlopen.call_args[0][0]
        self.assertIn("US%20East", req.full_url)

    # ------------------------------------------------------------------
    # Error handling
    # ------------------------------------------------------------------

    @patch("urllib.request.urlopen", side_effect=URLError("Connection refused"))
    def test_connection_error_raises(self, _):
        with self.assertRaises(PricingCalculatorError) as ctx:
            self.client.estimate_provisioned_search(size_gb=100)
        self.assertIn("port 5050", str(ctx.exception))

    @patch("urllib.request.urlopen")
    def test_post_http_error_raises(self, mock_urlopen):
        exc = HTTPError(
            url="http://localhost:5050/provisioned/estimate",
            code=400,
            msg="Bad Request",
            hdrs={},
            fp=io.BytesIO(b"invalid payload"),
        )
        mock_urlopen.side_effect = exc
        client = PricingCalculatorClient(base_url="http://localhost:5050")
        with self.assertRaises(PricingCalculatorError) as ctx:
            client.estimate_provisioned_search(size_gb=100)
        self.assertIn("HTTP 400", str(ctx.exception))

    @patch("urllib.request.urlopen")
    def test_get_http_error_raises(self, mock_urlopen):
        exc = HTTPError(
            url="http://localhost:5050/provisioned/regions",
            code=500,
            msg="Internal Server Error",
            hdrs={},
            fp=io.BytesIO(b"server error"),
        )
        mock_urlopen.side_effect = exc
        client = PricingCalculatorClient(base_url="http://localhost:5050")
        with self.assertRaises(PricingCalculatorError) as ctx:
            client.get_regions()
        self.assertIn("HTTP 500", str(ctx.exception))

    @patch("urllib.request.urlopen", side_effect=URLError("Connection refused"))
    def test_get_url_error_raises(self, _):
        client = PricingCalculatorClient(base_url="http://localhost:5050")
        with self.assertRaises(PricingCalculatorError) as ctx:
            client.get_pricing_options()
        self.assertIn("Could not reach", str(ctx.exception))

    @patch("urllib.request.urlopen", side_effect=URLError("Connection refused"))
    def test_health_check_returns_false_when_unreachable(self, _):
        self.assertFalse(self.client.health_check())

    @patch("urllib.request.urlopen")
    def test_health_check_returns_true_when_reachable(self, mock_urlopen):
        mock_urlopen.return_value = _mock_urlopen({"status": "ok"})
        self.assertTrue(self.client.health_check())

    # ------------------------------------------------------------------
    # format_estimate
    # ------------------------------------------------------------------

    def test_format_estimate_known_fields(self):
        summary = PricingCalculatorClient.format_estimate(MOCK_PROVISIONED)
        self.assertIn("$1,234.56", summary)
        self.assertIn("r6g.2xlarge.search", summary)
        self.assertIn("600", summary)

    def test_format_estimate_cluster_configs_single(self):
        result = {
            "clusterConfigs": [
                {
                    "totalCost": 500.00,
                    "hotNodes": {"type": "r6g.large.search", "count": 3},
                    "leaderNodes": {"type": "m6g.large.search", "count": 3},
                }
            ]
        }
        summary = PricingCalculatorClient.format_estimate(result)
        self.assertIn("$500.00", summary)
        self.assertIn("r6g.large.search", summary)
        self.assertIn("m6g.large.search", summary)

    def test_format_estimate_cluster_configs_multiple(self):
        result = {
            "clusterConfigs": [
                {"totalCost": 400.00, "hotNodes": {"type": "r6g.large.search", "count": 3}, "leaderNodes": {}},
                {"totalCost": 800.00, "hotNodes": {"type": "r6g.2xlarge.search", "count": 3}, "leaderNodes": {}},
            ]
        }
        summary = PricingCalculatorClient.format_estimate(result)
        self.assertIn("$400.00", summary)
        self.assertIn("Configurations evaluated", summary)
        self.assertIn("$800.00", summary)

    def test_format_estimate_cluster_configs_no_leader(self):
        result = {
            "clusterConfigs": [
                {"totalCost": 300.00, "hotNodes": {"type": "r6g.large.search", "count": 2}},
            ]
        }
        summary = PricingCalculatorClient.format_estimate(result)
        self.assertIn("$300.00", summary)
        self.assertNotIn("Manager", summary)

    def test_format_estimate_cluster_configs_no_hot_type(self):
        result = {
            "clusterConfigs": [
                {"totalCost": 300.00, "hotNodes": {}, "leaderNodes": {}},
            ]
        }
        summary = PricingCalculatorClient.format_estimate(result)
        self.assertIn("$300.00", summary)

    def test_format_estimate_serverless_price(self):
        result = {
            "price": {
                "month": {"total": 123.45, "indexOcu": 50.0, "searchOcu": 40.0, "s3Storage": 33.45},
                "year": {"total": 1481.40},
            }
        }
        summary = PricingCalculatorClient.format_estimate(result)
        self.assertIn("$123.45", summary)
        self.assertIn("Index OCU", summary)
        self.assertIn("Search OCU", summary)
        self.assertIn("S3 storage", summary)
        self.assertIn("$1,481.40", summary)

    def test_format_estimate_serverless_price_minimal(self):
        result = {"price": {"month": {"total": 99.99}}}
        summary = PricingCalculatorClient.format_estimate(result)
        self.assertIn("$99.99", summary)

    def test_format_estimate_serverless_price_empty_month(self):
        result = {"price": {"day": {"total": 3.0}}}
        summary = PricingCalculatorClient.format_estimate(result)
        # Falls through to JSON fallback since month has no total
        self.assertIn("```json", summary)

    def test_format_estimate_legacy_all_fields(self):
        result = {
            "monthlyCost": 1234.56,
            "annualCost": 14814.72,
            "instanceType": "r6g.2xlarge.search",
            "instanceCount": 6,
            "storageGB": 600,
            "shardCount": 8,
        }
        summary = PricingCalculatorClient.format_estimate(result)
        self.assertIn("$1,234.56", summary)
        self.assertIn("$14,814.72", summary)
        self.assertIn("r6g.2xlarge.search", summary)
        self.assertIn("6", summary)
        self.assertIn("600", summary)
        self.assertIn("8", summary)

    def test_format_estimate_unknown_shape_returns_json(self):
        summary = PricingCalculatorClient.format_estimate({"foo": "bar"})
        self.assertIn("```json", summary)
        self.assertIn('"foo"', summary)

    def test_format_estimate_type_error_returns_json(self):
        # Trigger TypeError by making configs[0] a non-subscriptable type
        result = {"clusterConfigs": [None]}
        summary = PricingCalculatorClient.format_estimate(result)
        self.assertIn("```json", summary)

    def test_format_estimate_key_error_returns_json(self):
        # Trigger KeyError by omitting totalCost
        result = {"clusterConfigs": [{"noSuchKey": 1}]}
        summary = PricingCalculatorClient.format_estimate(result)
        self.assertIn("```json", summary)

    def test_format_estimate_fallback_to_json(self):
        summary = PricingCalculatorClient.format_estimate({"unexpected": "shape"})
        self.assertIn("```json", summary)


if __name__ == "__main__":
    unittest.main()


# ---------------------------------------------------------------------------
# Integration tests — skipped automatically when running under pytest
# ---------------------------------------------------------------------------
# To run: python tests/test_pricing_calculator.py TestPricingCalculatorIntegration -v

import sys as _sys


def _is_pytest() -> bool:
    """Return True when the current process was launched by pytest."""
    # pytest injects '_pytest' into sys.modules before collecting tests.
    if "_pytest" in _sys.modules or "pytest" in _sys.modules:
        return True
    # Fallback: inspect argv for common pytest entry-points.
    argv0 = os.path.basename(_sys.argv[0]) if _sys.argv else ""
    return argv0 in ("pytest", "py.test", "_jb_pytest_runner.py")


_integration_skip = unittest.skip(
    "Integration tests are skipped when running under pytest. "
    "Run directly with: python tests/test_pricing_calculator.py"
)


@(_integration_skip if _is_pytest() else lambda cls: cls)
class TestPricingCalculatorIntegration(unittest.TestCase):
    """Live integration tests against a running opensearch-pricing-calculator.

    Prerequisites
    -------------
    The calculator must be running on http://localhost:5050 before executing
    these tests. Start it with::

        cd opensearch-migrations/AIAdvisor/opensearch-pricing-calculator
        go build -o opensearch-pricing-calculator . && ./opensearch-pricing-calculator

    Or with Docker::

        docker run -p 5050:5050 -p 8081:8081 opensearch-pricing-calculator

    Run directly (bypasses pytest skip)::

        python tests/test_pricing_calculator.py TestPricingCalculatorIntegration -v
    """

    BASE_URL = "http://opensearch-pricing-calculator:5050"

    @classmethod
    def setUpClass(cls):
        # Use a short timeout so the reachability probe fails fast.
        probe = PricingCalculatorClient(base_url=cls.BASE_URL, timeout=2)
        if not probe.health_check():
            raise unittest.SkipTest(
                f"opensearch-pricing-calculator is not reachable at {cls.BASE_URL}. "
                "Start it before running integration tests."
            )
        cls.client = PricingCalculatorClient(base_url=cls.BASE_URL)

    # ------------------------------------------------------------------
    # Health / reference data
    # ------------------------------------------------------------------

    def test_health_check(self):
        self.assertTrue(self.client.health_check())

    def test_get_provisioned_regions(self):
        regions = self.client.get_regions("provisioned")
        self.assertIsInstance(regions, (list, dict))

    def test_get_serverless_regions(self):
        regions = self.client.get_regions("serverless")
        self.assertIsInstance(regions, (list, dict))

    def test_get_pricing_options(self):
        options = self.client.get_pricing_options()
        self.assertIsInstance(options, (list, dict))

    def test_get_instance_families(self):
        families = self.client.get_instance_families("US East (N. Virginia)")
        self.assertIsInstance(families, (list, dict))

    # ------------------------------------------------------------------
    # Provisioned estimates
    # ------------------------------------------------------------------

    def test_estimate_provisioned_search_returns_cost(self):
        result = self.client.estimate_provisioned_search(
            size_gb=200,
            azs=3,
            replicas=1,
            target_shard_size_gb=25,
            cpus_per_shard=1.5,
            region="US East (N. Virginia)",
        )
        self.assertIsInstance(result, dict)
        # Response contains ranked cluster configurations, not a single flat cost.
        self.assertIn("clusterConfigs", result)
        configs = result["clusterConfigs"]
        self.assertIsInstance(configs, list)
        self.assertGreater(len(configs), 0)
        first = configs[0]
        self.assertIn("totalCost", first)
        self.assertGreater(first["totalCost"], 0)

    def test_estimate_provisioned_time_series_returns_cost(self):
        result = self.client.estimate_provisioned_time_series(
            size_gb=500,
            azs=3,
            replicas=1,
            hot_retention_days=14,
            warm_retention_days=76,
            target_shard_size_gb=45,
            cpus_per_shard=1.25,
            region="US East (N. Virginia)",
        )
        self.assertIsInstance(result, dict)
        self.assertIn("clusterConfigs", result)
        configs = result["clusterConfigs"]
        self.assertGreater(len(configs), 0)
        self.assertIn("totalCost", configs[0])
        self.assertGreater(configs[0]["totalCost"], 0)

    def test_estimate_provisioned_vector_returns_cost(self):
        result = self.client.estimate_provisioned_vector(
            vector_count=1_000_000,
            dimensions=768,
            engine_type="hnswfp16",
            max_edges=16,
            azs=3,
            replicas=1,
            region="US East (N. Virginia)",
        )
        self.assertIsInstance(result, dict)
        self.assertIn("clusterConfigs", result)
        configs = result["clusterConfigs"]
        self.assertGreater(len(configs), 0)
        self.assertIn("totalCost", configs[0])
        self.assertGreater(configs[0]["totalCost"], 0)

    # ------------------------------------------------------------------
    # Serverless estimates
    # ------------------------------------------------------------------

    def test_estimate_serverless_time_series_returns_cost(self):
        result = self.client.estimate_serverless(
            collection_type="timeSeries",
            daily_index_size_gb=10,
            days_in_hot=1,
            days_in_warm=6,
            min_query_rate=1,
            max_query_rate=1,
            hours_at_max_rate=0,
            region="us-east-1",
            redundancy=True,
        )
        self.assertIsInstance(result, dict)
        # Serverless response nests costs under "price" → "month" → "total"
        self.assertIn("price", result)
        self.assertIn("month", result["price"])
        self.assertGreater(result["price"]["month"]["total"], 0)

    def test_estimate_serverless_search_returns_cost(self):
        result = self.client.estimate_serverless(
            collection_type="search",
            daily_index_size_gb=5,
            days_in_hot=7,
            days_in_warm=0,
            min_query_rate=1,
            max_query_rate=10,
            hours_at_max_rate=8,
            region="us-east-1",
            redundancy=False,
        )
        self.assertIsInstance(result, dict)
        self.assertIn("price", result)
        self.assertIn("month", result["price"])

    # ------------------------------------------------------------------
    # format_estimate round-trip
    # ------------------------------------------------------------------

    def test_format_estimate_round_trip(self):
        result = self.client.estimate_provisioned_search(
            size_gb=100,
            region="US East (N. Virginia)",
        )
        summary = PricingCalculatorClient.format_estimate(result)
        self.assertIsInstance(summary, str)
        self.assertGreater(len(summary), 0)


if __name__ == "__main__":
    unittest.main()
