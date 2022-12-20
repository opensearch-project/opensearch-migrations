import json
import os
from typing import Dict, List

import upgrade_testing_framework.core.versions_engine as ev

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

"""
These configuration classes can probably be auto-generated from a template using some combination of Python's
meta-class, decorator, and/or introspection features.  If the config gets much more complicated, we should explore
doing that.
"""
class ClustersDef:
    def __init__(self, raw_config: dict):
        raw_source_config = raw_config.get("source", None)
        if raw_source_config is None:
            raise TestConfigFileMissingFieldException("source")
        self.source = ClusterConfig(raw_source_config)

        raw_target_config = raw_config.get("target", None)
        if raw_target_config is None:
            raise TestConfigFileMissingFieldException("target")
        self.target = ClusterConfig(raw_target_config)

    def to_dict(self) -> dict:
        return {
            "source": self.source.to_dict(),
            "target": self.target.to_dict()
        }    

    def __eq__(self, other):
        return self.to_dict() == other.to_dict()

class ClusterConfig:
    def __init__(self, raw_config: dict):
        # Manually declare the internal members so IDEs know they exist
        self.engine_version: str = None
        self.image: str = None
        self.node_count: int = None
        self.additional_node_config: Dict[str, str] = None

        # Load the real values from the config
        self._load_attrs_by_list(raw_config, [
            "engine_version",
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

class UpgradeDef:
    def __init__(self, raw_config: dict):
        style = raw_config.get("style", None)
        if style is None:
            raise TestConfigFileMissingFieldException("style")
        self.style = style

    def to_dict(self) -> dict:
        return {
            "style": self.style
        }    

    def __eq__(self, other):
        return self.to_dict() == other.to_dict()

class TestConfig:
    def __init__(self, raw_config: dict):
        raw_clusters_def = raw_config.get("clusters_def", None)
        if raw_clusters_def is None:
            raise TestConfigFileMissingFieldException("clusters_def")
        self.clusters_def = ClustersDef(raw_clusters_def)
        
        raw_upgrade_def = raw_config.get("upgrade_def", None)
        if raw_upgrade_def is None:
            raise TestConfigFileMissingFieldException("upgrade_def")
        self.upgrade_def = UpgradeDef(raw_upgrade_def)

    def to_dict(self) -> dict:
        return {
            "clusters_def": self.clusters_def.to_dict(),
            "upgrade_def": self.upgrade_def.to_dict()
        }    

    def __eq__(self, other):
        return self.to_dict() == other.to_dict()

def load_test_config(test_config_path: str) -> TestConfig:
    # Confirm the file exists
    test_config_path_full = os.path.abspath(test_config_path)
    if not os.path.exists(test_config_path):
        raise TestConfigFileDoesntExistException(test_config_path_full)

    # Pull the contents and convert to JSON
    try:
        with open(test_config_path_full, "r") as file_handle:
            raw_test_config = json.load(file_handle)
    except json.JSONDecodeError as exception:
        raise TestConfigFileNotJSONException(test_config_path_full, exception)
    except IOError as exception:
        raise TestConfigCantReadFileException(test_config_path_full, exception)

    return TestConfig(raw_test_config)

