import json
import os
import pytest
import py

import cluster_migration_core.core.test_config_wrangling as tcw

TEST_CONFIG = {
    "clusters_def": {
        "source": {
            "engine_version": "v1",
            "image": "image-1",
            "node_count": 1,
            "additional_node_config": {
                "config1": "val1"
            }
        },
        "target": {
            "engine_version": "v2",
            "image": "image-2",
            "node_count": 2,
            "additional_node_config": {
                "config2": "val2",
                "config3": "val3"
            }
        }
    },
    "upgrade_def": {
        "style": "noldorin"
    }
}

TEST_CONFIG_MISSING_SOURCE_DEF = {
    "clusters_def": {
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
    "clusters_def": {
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

TEST_CONFIG_MISSING_UPGRADE = {
    "clusters_def": {
        "source": {
            "engine_version": "v1",
            "image": "image-1",
            "node_count": 1,
            "additional_node_config": {
                "config1": "val1"
            }
        },
        "target": {
            "engine_version": "v2",
            "image": "image-2",
            "node_count": 2,
            "additional_node_config": {
                "config2": "val2",
                "config3": "val3"
            }
        }
    }
}

TEST_CONFIG_MISSING_UPGRADE_STYLE = {
    "clusters_def": {
        "source": {
            "engine_version": "v1",
            "image": "image-1",
            "node_count": 1,
            "additional_node_config": {
                "config1": "val1"
            }
        },
        "target": {
            "engine_version": "v2",
            "image": "image-2",
            "node_count": 2,
            "additional_node_config": {
                "config2": "val2",
                "config3": "val3"
            }
        }
    },
    "upgrade_def": {}
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


@pytest.fixture
def invalid_test_config_file_missing_upgrade(tmpdir):
    test_config_file = py.path.local(os.path.join(tmpdir.strpath, "test_config.json"))
    test_config_file.write(json.dumps(TEST_CONFIG_MISSING_UPGRADE, sort_keys=True, indent=4))
    return test_config_file


@pytest.fixture
def invalid_test_config_file_missing_style(tmpdir):
    test_config_file = py.path.local(os.path.join(tmpdir.strpath, "test_config.json"))
    test_config_file.write(json.dumps(TEST_CONFIG_MISSING_UPGRADE_STYLE, sort_keys=True, indent=4))
    return test_config_file


def test_WHEN_load_test_config_called_AND_valid_THEN_returns_it(valid_test_config_file):
    # Run our test
    actual_value = tcw.load_test_config(valid_test_config_file.strpath)

    # Check the results
    expected_value = tcw.TestConfig(TEST_CONFIG)
    assert expected_value == actual_value


def test_WHEN_load_test_config_called_AND_doesnt_exist_THEN_raises(tmpdir):
    # Set up our test
    non_existent_file = os.path.join(tmpdir.strpath, "test_config.json")

    # Run our test
    with pytest.raises(tcw.TestConfigFileDoesntExistException):
        tcw.load_test_config(non_existent_file)


def test_WHEN_load_test_config_called_AND_not_readable_THEN_raises(valid_test_config_file):
    # Set up our test
    valid_test_config_file.chmod(0o200)  # Make the file unreadable

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


def test_WHEN_load_test_config_called_AND_missing_target_field_THEN_raises(
        invalid_test_config_file_missing_target_field):
    # Run our test
    with pytest.raises(tcw.TestConfigFileMissingFieldException):
        tcw.load_test_config(invalid_test_config_file_missing_target_field.strpath)


def test_WHEN_load_test_config_called_AND_missing_upgrade_THEN_raises(invalid_test_config_file_missing_upgrade):
    # Run our test
    with pytest.raises(tcw.TestConfigFileMissingFieldException):
        tcw.load_test_config(invalid_test_config_file_missing_upgrade.strpath)


def test_WHEN_load_test_config_called_AND_missing_style_THEN_raises(invalid_test_config_file_missing_style):
    # Run our test
    with pytest.raises(tcw.TestConfigFileMissingFieldException):
        tcw.load_test_config(invalid_test_config_file_missing_style.strpath)
