import json
import os

class TestConfigFileDoesntExistException(Exception):
    def __init__(self, test_config_path_full):
        super().__init__(f"There is no file at the path you specified for your test config: {test_config_path_full}")

class TestConfigCantReadFileException(Exception):
    def __init__(self, test_config_path_full, original_exception):
        super().__init__(f"Unable to read test config file at path {test_config_path_full}.  Details: {str(original_exception)}")

class TestConfigFileNotJSONException(Exception):
    def __init__(self, test_config_path_full, original_exception):
        super().__init__(f"The test config at path {test_config_path_full} is not parsible as JSON.  Details: {str(original_exception)}")

class TestConfigFileMissingFieldException(Exception):
    def __init__(self, missing_field):
        super().__init__(f"The test config is missing a required field: {missing_field}")

def load_test_config(test_config_path: str) -> dict:
    # Confirm the file exists
    test_config_path_full = os.path.abspath(test_config_path)
    if not os.path.exists(test_config_path):
        raise TestConfigFileDoesntExistException(test_config_path_full)

    # Pull the contents and convert to JSON
    try:
        with open(test_config_path_full, "r") as file_handle:
            test_config = json.load(file_handle)
    except json.JSONDecodeError as exception:
        raise TestConfigFileNotJSONException(test_config_path_full, exception)
    except IOError as exception:
        raise TestConfigCantReadFileException(test_config_path_full, exception)

    # Confirm expected fields present
    if not test_config.get('source_docker_image', None):
        raise TestConfigFileMissingFieldException('source_docker_image')

    return test_config

