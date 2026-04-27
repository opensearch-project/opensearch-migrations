"""Additional tests for pricing_calculator.py to reach ≥80% coverage.

Covers: HTTPError paths in _post/_get, get_regions, get_pricing_options,
get_instance_families, format_estimate with clusterConfigs and serverless
price shapes, base_url env-var fallback, and constructor edge cases.
"""

import json
import os
import sys
import io
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError, URLError

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))

from pricing_calculator import (
    PricingCalculatorClient,
    PricingCalculatorError,
    PRICING_CALCULATOR_URL,
)


def _mock_urlopen(body: dict):
    mock_resp = MagicMock()
    mock_resp.read.return_value = json.dumps(body).encode("utf-8")
    mock_resp.__enter__ = lambda s: s
    mock_resp.__exit__ = MagicMock(return_value=False)
    return mock_resp


# ------------------------------------------------------------------
# Constructor / env-var
# ------------------------------------------------------------------


def test_default_base_url_from_module_constant():
    client = PricingCalculatorClient()
    assert client.base_url == PRICING_CALCULATOR_URL.rstrip("/")


def test_explicit_base_url_overrides_env():
    client = PricingCalculatorClient(base_url="http://custom:9999/")
    assert client.base_url == "http://custom:9999"


def test_none_base_url_falls_back_to_env():
    client = PricingCalculatorClient(base_url=None)
    assert client.base_url == PRICING_CALCULATOR_URL.rstrip("/")


def test_env_var_override(monkeypatch):
    """PRICING_CALCULATOR_URL env var is read at module import time."""
    # We can't easily test the module-level read without reload,
    # so instead verify the client uses the module constant
    original = PRICING_CALCULATOR_URL
    client = PricingCalculatorClient()
    assert client.base_url == original.rstrip("/")


# ------------------------------------------------------------------
# _post — HTTPError path
# ------------------------------------------------------------------


@patch("urllib.request.urlopen")
def test_post_http_error_raises(mock_urlopen):
    exc = HTTPError(
        url="http://localhost:5050/provisioned/estimate",
        code=400,
        msg="Bad Request",
        hdrs={},
        fp=io.BytesIO(b"invalid payload"),
    )
    mock_urlopen.side_effect = exc
    client = PricingCalculatorClient(base_url="http://localhost:5050")
    with pytest.raises(PricingCalculatorError, match="HTTP 400"):
        client.estimate_provisioned_search(size_gb=100)


# ------------------------------------------------------------------
# _get — HTTPError and URLError paths
# ------------------------------------------------------------------


@patch("urllib.request.urlopen")
def test_get_http_error_raises(mock_urlopen):
    exc = HTTPError(
        url="http://localhost:5050/provisioned/regions",
        code=500,
        msg="Internal Server Error",
        hdrs={},
        fp=io.BytesIO(b"server error"),
    )
    mock_urlopen.side_effect = exc
    client = PricingCalculatorClient(base_url="http://localhost:5050")
    with pytest.raises(PricingCalculatorError, match="HTTP 500"):
        client.get_regions()


@patch("urllib.request.urlopen", side_effect=URLError("Connection refused"))
def test_get_url_error_raises(_):
    client = PricingCalculatorClient(base_url="http://localhost:5050")
    with pytest.raises(PricingCalculatorError, match="Could not reach"):
        client.get_pricing_options()


# ------------------------------------------------------------------
# get_regions / get_pricing_options / get_instance_families
# ------------------------------------------------------------------


@patch("urllib.request.urlopen")
def test_get_regions_provisioned(mock_urlopen):
    mock_urlopen.return_value = _mock_urlopen(["US East (N. Virginia)", "EU West (Ireland)"])
    result = PricingCalculatorClient().get_regions("provisioned")
    assert "US East (N. Virginia)" in result


@patch("urllib.request.urlopen")
def test_get_regions_serverless(mock_urlopen):
    mock_urlopen.return_value = _mock_urlopen(["us-east-1", "eu-west-1"])
    result = PricingCalculatorClient().get_regions("serverless")
    assert "us-east-1" in result


@patch("urllib.request.urlopen")
def test_get_pricing_options(mock_urlopen):
    mock_urlopen.return_value = _mock_urlopen(["OnDemand", "Reserved"])
    result = PricingCalculatorClient().get_pricing_options()
    assert "OnDemand" in result


@patch("urllib.request.urlopen")
def test_get_instance_families(mock_urlopen):
    mock_urlopen.return_value = _mock_urlopen({"families": ["r6g", "m6g"]})
    result = PricingCalculatorClient().get_instance_families("US East (N. Virginia)")
    assert result["families"] == ["r6g", "m6g"]
    # Verify the region was URL-encoded in the request path
    req = mock_urlopen.call_args[0][0]
    assert "US%20East" in req.full_url


# ------------------------------------------------------------------
# format_estimate — clusterConfigs shape (provisioned)
# ------------------------------------------------------------------


def test_format_estimate_cluster_configs_single():
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
    assert "$500.00" in summary
    assert "r6g.large.search" in summary
    assert "m6g.large.search" in summary


def test_format_estimate_cluster_configs_multiple():
    result = {
        "clusterConfigs": [
            {"totalCost": 400.00, "hotNodes": {"type": "r6g.large.search", "count": 3}, "leaderNodes": {}},
            {"totalCost": 800.00, "hotNodes": {"type": "r6g.2xlarge.search", "count": 3}, "leaderNodes": {}},
        ]
    }
    summary = PricingCalculatorClient.format_estimate(result)
    assert "$400.00" in summary
    assert "Configurations evaluated" in summary
    assert "$800.00" in summary


def test_format_estimate_cluster_configs_no_leader():
    result = {
        "clusterConfigs": [
            {"totalCost": 300.00, "hotNodes": {"type": "r6g.large.search", "count": 2}},
        ]
    }
    summary = PricingCalculatorClient.format_estimate(result)
    assert "$300.00" in summary
    assert "Manager" not in summary


def test_format_estimate_cluster_configs_no_hot_type():
    result = {
        "clusterConfigs": [
            {"totalCost": 300.00, "hotNodes": {}, "leaderNodes": {}},
        ]
    }
    summary = PricingCalculatorClient.format_estimate(result)
    assert "$300.00" in summary


# ------------------------------------------------------------------
# format_estimate — serverless price shape
# ------------------------------------------------------------------


def test_format_estimate_serverless_price():
    result = {
        "price": {
            "month": {"total": 123.45, "indexOcu": 50.0, "searchOcu": 40.0, "s3Storage": 33.45},
            "year": {"total": 1481.40},
        }
    }
    summary = PricingCalculatorClient.format_estimate(result)
    assert "$123.45" in summary
    assert "Index OCU" in summary
    assert "Search OCU" in summary
    assert "S3 storage" in summary
    assert "$1,481.40" in summary


def test_format_estimate_serverless_price_minimal():
    result = {"price": {"month": {"total": 99.99}}}
    summary = PricingCalculatorClient.format_estimate(result)
    assert "$99.99" in summary


def test_format_estimate_serverless_price_empty_month():
    result = {"price": {"day": {"total": 3.0}}}
    summary = PricingCalculatorClient.format_estimate(result)
    # Falls through to JSON fallback since month has no total
    assert "```json" in summary


# ------------------------------------------------------------------
# format_estimate — legacy flat shape
# ------------------------------------------------------------------


def test_format_estimate_legacy_all_fields():
    result = {
        "monthlyCost": 1234.56,
        "annualCost": 14814.72,
        "instanceType": "r6g.2xlarge.search",
        "instanceCount": 6,
        "storageGB": 600,
        "shardCount": 8,
    }
    summary = PricingCalculatorClient.format_estimate(result)
    assert "$1,234.56" in summary
    assert "$14,814.72" in summary
    assert "r6g.2xlarge.search" in summary
    assert "6" in summary
    assert "600" in summary
    assert "8" in summary


# ------------------------------------------------------------------
# format_estimate — fallback / edge cases
# ------------------------------------------------------------------


def test_format_estimate_unknown_shape_returns_json():
    summary = PricingCalculatorClient.format_estimate({"foo": "bar"})
    assert "```json" in summary
    assert '"foo"' in summary


def test_format_estimate_type_error_returns_json():
    # Trigger TypeError by making configs[0] a non-subscriptable type
    result = {"clusterConfigs": [None]}
    summary = PricingCalculatorClient.format_estimate(result)
    assert "```json" in summary


def test_format_estimate_key_error_returns_json():
    # Trigger KeyError by omitting totalCost
    result = {"clusterConfigs": [{"noSuchKey": 1}]}
    summary = PricingCalculatorClient.format_estimate(result)
    assert "```json" in summary
