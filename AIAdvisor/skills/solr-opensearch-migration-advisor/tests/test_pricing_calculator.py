"""Tests for PricingCalculatorClient."""

import json
import unittest
from unittest.mock import MagicMock, patch
from urllib.error import URLError

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))

from pricing_calculator import PricingCalculatorClient, PricingCalculatorError


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
    # Error handling
    # ------------------------------------------------------------------

    @patch("urllib.request.urlopen", side_effect=URLError("Connection refused"))
    def test_connection_error_raises(self, _):
        with self.assertRaises(PricingCalculatorError) as ctx:
            self.client.estimate_provisioned_search(size_gb=100)
        self.assertIn("port 5050", str(ctx.exception))

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

    BASE_URL = "http://localhost:5050"

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
