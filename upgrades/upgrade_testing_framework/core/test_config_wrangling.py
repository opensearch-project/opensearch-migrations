import json
import os
from typing import Dict, List

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

class TestClustersDef:
    def __init__(self, raw_config: dict):
        raw_source_config = raw_config.get("source", None)
        if raw_source_config is None:
            raise TestConfigFileMissingFieldException("source")
        self.source_cluster = TestClusterConfig(raw_source_config)

        raw_target_config = raw_config.get("target", None)
        if raw_target_config is None:
            raise TestConfigFileMissingFieldException("target")
        self.target_cluster = TestClusterConfig(raw_target_config)

    def to_dict(self) -> dict:
        return {
            "source": self.source_cluster.to_dict(),
            "target": self.target_cluster.to_dict()
        }    

    def __eq__(self, other):
        return self.to_dict() == other.to_dict()

class TestClusterConfig:
    def __init__(self, raw_config: dict):
        # Manually declare the internal members so IDEs know they exist
        self.image: str = None
        self.node_count: int = None
        self.additional_node_config: Dict[str, str] = None

        # Load the real values from the config
        self._load_attrs_by_list(raw_config, [
            "image",
            "node_count",
            "additional_node_config"
        ])        
    
    def _load_attrs_by_list(self, raw_config: dict, attr_names: List[str]):
        """
        Pulls values from raw_config using keys from attr_names and stores them in the object into members of the same
        name
        """
        for attr_name in attr_names:
            attr = raw_config.get(attr_name, None)
            if attr is None:
                raise TestConfigFileMissingFieldException(attr_name)
            setattr(self, attr_name, attr)

    def to_dict(self) -> dict:
        return {
            "image": self.image,
            "node_count": self.node_count,
            "additional_node_config": self.additional_node_config
        }    

    def __eq__(self, other):
        return self.to_dict() == other.to_dict()

def load_test_config(test_config_path: str) -> TestClustersDef:
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
    raw_clusters_def = test_config.get('cluster_def', None)
    if raw_clusters_def is None:
        raise TestConfigFileMissingFieldException('cluster_def')
    return TestClustersDef(raw_clusters_def)

