import pytest

import upgrade_testing_framework.core.versions_engine as versions

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