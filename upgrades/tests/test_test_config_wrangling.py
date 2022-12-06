import json
import os
import pytest
import py

import upgrade_testing_framework.core.test_config_wrangling as tcw

TEST_CONFIG = {"source_docker_image": "my_image"}

@pytest.fixture
def valid_test_config_file(tmpdir):
    test_config_file = py.path.local(os.path.join(tmpdir.strpath, "test_config.json"))
    test_config_file.write(json.dumps(TEST_CONFIG, sort_keys=True, indent=4))
    return test_config_file

@pytest.fixture
def not_parsible_file(tmpdir):
    test_config_file = py.path.local(os.path.join(tmpdir.strpath, "test_config.json"))
    test_config_file.write("]")
    return test_config_file

@pytest.fixture
def missing_field_file(tmpdir):
    test_config_file = py.path.local(os.path.join(tmpdir.strpath, "test_config.json"))
    test_config_file.write(json.dumps({}, sort_keys=True, indent=4))
    return test_config_file

def test_WHEN_load_test_config_called_AND_valid_THEN_returns_it(valid_test_config_file):
    # Run our test
    actual_value = tcw.load_test_config(valid_test_config_file.strpath)

    # Check the results
    expected_value = TEST_CONFIG
    assert expected_value == actual_value

def test_WHEN_load_test_config_called_AND_doesnt_exist_THEN_raises(tmpdir):
    # Set up our test
    non_existant_file = os.path.join(tmpdir.strpath, "test_config.json")

    # Run our test
    with pytest.raises(tcw.TestConfigFileDoesntExistException):
        tcw.load_test_config(non_existant_file)

def test_WHEN_load_test_config_called_AND_not_readable_THEN_raises(valid_test_config_file):
    # Set up our test
    valid_test_config_file.chmod(0o200) # Make the file unreadable

    # Run our test
    with pytest.raises(tcw.TestConfigCantReadFileException):
        tcw.load_test_config(valid_test_config_file.strpath)

def test_WHEN_load_test_config_called_AND_not_valid_json_THEN_raises(not_parsible_file):
    # Run our test
    with pytest.raises(tcw.TestConfigFileNotJSONException):
        tcw.load_test_config(not_parsible_file.strpath)

def test_WHEN_load_test_config_called_AND_missing_field_THEN_raises(missing_field_file):
    # Run our test
    with pytest.raises(tcw.TestConfigFileMissingFieldException):
        tcw.load_test_config(missing_field_file.strpath)