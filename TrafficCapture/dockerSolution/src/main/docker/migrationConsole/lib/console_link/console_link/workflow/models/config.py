"""Configuration models for the workflow library."""

from typing import Dict, Any
import yaml
import json


class WorkflowConfig:
    """Simple workflow configuration that stores raw YAML/JSON data."""

    def __init__(self, data: Dict[str, Any] = None):
        """Initialize with raw configuration data.

        Args:
            data: Raw configuration dictionary
        """
        self.data = data or {}

    def to_yaml(self) -> str:
        """Convert to YAML string."""
        return yaml.dump(self.data, default_flow_style=False, sort_keys=False)

    def to_json(self) -> str:
        """Convert to JSON string."""
        return json.dumps(self.data, indent=2)

    @classmethod
    def from_yaml(cls, yaml_str: str) -> 'WorkflowConfig':
        """Create from YAML string."""
        data = yaml.safe_load(yaml_str) or {}
        return cls(data)

    @classmethod
    def from_json(cls, json_str: str) -> 'WorkflowConfig':
        """Create from JSON string."""
        data = json.loads(json_str)
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
