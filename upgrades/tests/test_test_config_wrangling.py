import json
import os
import pytest
import py

import upgrade_testing_framework.core.test_config_wrangling as tcw

TEST_CONFIG = {
    "cluster_def": {
        "source": {
            "image": "image-1",
            "node_count": 1,
            "additional_node_config": {
                "config1": "val1"
            }
        },
        "target": {
            "image": "image-2",
            "node_count": 2,
            "additional_node_config": {
                "config2": "val2",
                "config3": "val3"
            }
        }
    }
}

TEST_CONFIG_MISSING_SOURCE_DEF = {
    "cluster_def": {
        "target": {
            "image": "image-2",
            "node_count": 2,
            "additional_node_config": {
                "config2": "val2",
                "config3": "val3"
            }
        }
    }
}

TEST_CONFIG_MISSING_TARGET_FIELD = {
    "cluster_def": {
        "source": {
            "image": "image-1",
            "node_count": 1,
            "additional_node_config": {}
        },
        "target": {
            "image": "image-2",
            "node_count": 2
        }
    }
}

@pytest.fixture
def valid_test_config_file(tmpdir):
    test_config_file = py.path.local(os.path.join(tmpdir.strpath, "test_config.json"))
    test_config_file.write(json.dumps(TEST_CONFIG, sort_keys=True, indent=4))
    return test_config_file

@pytest.fixture
def not_parsable_file(tmpdir):
    test_config_file = py.path.local(os.path.join(tmpdir.strpath, "test_config.json"))
    test_config_file.write("]")
    return test_config_file

@pytest.fixture
def empty_dict_file(tmpdir):
    test_config_file = py.path.local(os.path.join(tmpdir.strpath, "test_config.json"))
    test_config_file.write(json.dumps({}, sort_keys=True, indent=4))
    return test_config_file

@pytest.fixture
def invalid_test_config_file_missing_source(tmpdir):
    test_config_file = py.path.local(os.path.join(tmpdir.strpath, "test_config.json"))
    test_config_file.write(json.dumps(TEST_CONFIG_MISSING_SOURCE_DEF, sort_keys=True, indent=4))
    return test_config_file

@pytest.fixture
def invalid_test_config_file_missing_target_field(tmpdir):
    test_config_file = py.path.local(os.path.join(tmpdir.strpath, "test_config.json"))
    test_config_file.write(json.dumps(TEST_CONFIG_MISSING_TARGET_FIELD, sort_keys=True, indent=4))
    return test_config_file

def test_WHEN_load_test_config_called_AND_valid_THEN_returns_it(valid_test_config_file):
    # Run our test
    actual_value = tcw.load_test_config(valid_test_config_file.strpath)

    # Check the results
    expected_value = tcw.TestClustersDef(TEST_CONFIG["cluster_def"])
    assert expected_value == actual_value

def test_WHEN_load_test_config_called_AND_doesnt_exist_THEN_raises(tmpdir):
    # Set up our test
    non_existent_file = os.path.join(tmpdir.strpath, "test_config.json")

    # Run our test
    with pytest.raises(tcw.TestConfigFileDoesntExistException):
        tcw.load_test_config(non_existent_file)

def test_WHEN_load_test_config_called_AND_not_readable_THEN_raises(valid_test_config_file):
    # Set up our test
    valid_test_config_file.chmod(0o200) # Make the file unreadable

    # Run our test
    with pytest.raises(tcw.TestConfigCantReadFileException):
        tcw.load_test_config(valid_test_config_file.strpath)

def test_WHEN_load_test_config_called_AND_not_valid_json_THEN_raises(not_parsable_file):
    # Run our test
    with pytest.raises(tcw.TestConfigFileNotJSONException):
        tcw.load_test_config(not_parsable_file.strpath)

def test_WHEN_load_test_config_called_AND_empty_THEN_raises(empty_dict_file):
    # Run our test
    with pytest.raises(tcw.TestConfigFileMissingFieldException):
        tcw.load_test_config(empty_dict_file.strpath)

def test_WHEN_load_test_config_called_AND_missing_source_def_THEN_raises(invalid_test_config_file_missing_source):
    # Run our test
    with pytest.raises(tcw.TestConfigFileMissingFieldException):
        tcw.load_test_config(invalid_test_config_file_missing_source.strpath)

def test_WHEN_load_test_config_called_AND_missing_target_field_THEN_raises(invalid_test_config_file_missing_target_field):
    # Run our test
    with pytest.raises(tcw.TestConfigFileMissingFieldException):
        tcw.load_test_config(invalid_test_config_file_missing_target_field.strpath)
