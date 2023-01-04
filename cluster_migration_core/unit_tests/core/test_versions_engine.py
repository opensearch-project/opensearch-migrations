import pytest

import cluster_migration_core.core.versions_engine as versions


def test_WHEN_get_version_AND_valid_es_THEN_returns():
    # Run our test
    actual_value = versions.get_version("ES_7_10_2")

    # Check our results
    expected_value = versions.EngineVersion(versions.ENGINE_ELASTICSEARCH, 7, 10, 2)
    assert expected_value == actual_value


def test_WHEN_get_version_AND_valid_os_THEN_returns():
    # Run our test
    actual_value = versions.get_version("OS_1_3_6")

    # Check our results
    expected_value = versions.EngineVersion(versions.ENGINE_OPENSEARCH, 1, 3, 6)
    assert expected_value == actual_value


def test_WHEN_get_version_AND_engine_weird_THEN_raises():
    # Run our test
    with pytest.raises(versions.CouldNotParseEngineVersionException):
        versions.get_version("OSS_1_3_6")


def test_WHEN_get_version_AND_not_enough_fields_THEN_raises():
    # Run our test
    with pytest.raises(versions.CouldNotParseEngineVersionException):
        versions.get_version("OS_1_3")


def test_WHEN_get_version_AND_version_not_int_THEN_raises():
    # Run our test
    with pytest.raises(versions.CouldNotParseEngineVersionException):
        versions.get_version("OS_1_3b_6")


def test_WHEN_version_comparison_AND_same_engine_THEN_compares():
    # Major versions are different
    assert versions.get_version("ES_7_0_2") > versions.get_version("ES_3_5_0")
    # Major versions are same
    assert versions.get_version("ES_7_10_2") > versions.get_version("ES_7_0_0")
    assert versions.get_version("OS_2_0_0") < versions.get_version("OS_2_4_0")
    assert versions.get_version("OS_2_4_0") >= versions.get_version("OS_2_4_0")


def test_WHEN_version_comparison_AND_different_engine_THEN_compares():
    assert versions.get_version("ES_7_10_2") < versions.get_version("OS_1_0_0")
    assert versions.get_version("OS_2_4_0") >= versions.get_version("ES_3_5_0")
