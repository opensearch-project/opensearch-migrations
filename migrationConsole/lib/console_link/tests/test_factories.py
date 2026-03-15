"""
Tests for factory functions in console_link.models.factories.

These tests target edge cases in the factory dispatch functions,
specifically empty config handling that previously raised StopIteration
instead of the expected UnsupportedXxxError.
"""
import pytest
from unittest.mock import patch

from console_link.models.factories import (
    get_snapshot,
    get_replayer,
    get_kafka,
    get_backfill,
    get_metrics_source,
    UnsupportedSnapshotError,
    UnsupportedReplayerError,
    UnsupportedKafkaError,
    UnsupportedBackfillTypeError,
    UnsupportedMetricsSourceError,
)
from tests.utils import create_valid_cluster


class TestGetSnapshotFactory:
    def test_empty_config_raises_unsupported_error(self):
        """Empty config must raise UnsupportedSnapshotError, not StopIteration."""
        with pytest.raises(UnsupportedSnapshotError):
            get_snapshot({}, None)

    def test_single_unknown_key_raises_unsupported_error(self):
        with pytest.raises(UnsupportedSnapshotError):
            get_snapshot({"unknown": {}}, None)

    def test_multiple_unknown_keys_raises_unsupported_error(self):
        with pytest.raises(UnsupportedSnapshotError):
            get_snapshot({"a": {}, "b": {}}, None)


class TestGetReplayerFactory:
    def test_empty_config_raises_unsupported_error(self):
        """Empty config must raise UnsupportedReplayerError, not StopIteration."""
        with pytest.raises(UnsupportedReplayerError):
            get_replayer({})

    def test_unknown_key_raises_unsupported_error(self):
        with pytest.raises(UnsupportedReplayerError):
            get_replayer({"unknown": {}})


class TestGetKafkaFactory:
    def test_empty_config_raises_unsupported_error(self):
        with pytest.raises(UnsupportedKafkaError):
            get_kafka({})

    def test_unknown_key_raises_unsupported_error(self):
        with pytest.raises(UnsupportedKafkaError):
            get_kafka({"unknown": {}})


class TestGetBackfillFactory:
    def test_empty_config_raises_unsupported_error(self):
        with pytest.raises(UnsupportedBackfillTypeError):
            get_backfill({}, create_valid_cluster())

    def test_unknown_key_raises_unsupported_error(self):
        with pytest.raises(UnsupportedBackfillTypeError):
            get_backfill({"unknown": {}}, create_valid_cluster())


class TestGetMetricsSourceFactory:
    def test_empty_config_raises_unsupported_error(self):
        with pytest.raises(UnsupportedMetricsSourceError):
            get_metrics_source({})

    def test_unknown_key_raises_unsupported_error(self):
        with pytest.raises(UnsupportedMetricsSourceError):
            get_metrics_source({"unknown": {}})
