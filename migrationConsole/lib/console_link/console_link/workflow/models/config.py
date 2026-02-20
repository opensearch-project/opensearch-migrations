"""Configuration models for the workflow library."""

from typing import Dict, Any
from io import StringIO
from ruamel.yaml import YAML


class WorkflowConfig:
    """Simple workflow configuration that stores raw YAML data with comment preservation."""

    def __init__(self, data: Dict[str, Any] = None):
        """Initialize with raw configuration data.

        Args:
            data: Raw configuration dictionary
        """
        self.data = data or {}

    def to_yaml(self) -> str:
        """Convert to YAML string with comment preservation."""
        yaml = YAML()
        yaml.preserve_quotes = True
        yaml.default_flow_style = False

        stream = StringIO()
        yaml.dump(self.data, stream)
        return stream.getvalue()

    @classmethod
    def from_yaml(cls, yaml_str: str) -> 'WorkflowConfig':
        """Create from YAML string (supports both YAML and JSON since JSON is a subset of YAML 1.2)."""
        yaml = YAML()
        yaml.preserve_quotes = True

        data = yaml.load(yaml_str) or {}
        return cls(data)

    def get(self, key: str, default: Any = None) -> Any:
        """Get a configuration value by key."""
        return self.data.get(key, default)

    def set(self, key: str, value: Any) -> None:
        """Set a configuration value."""
        self.data[key] = value

    def __bool__(self) -> bool:
        """Return True if config has any data."""
        return bool(self.data)
