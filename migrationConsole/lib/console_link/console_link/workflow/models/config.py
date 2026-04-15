"""Configuration models for the workflow library."""

from typing import Dict, Any, Optional
from ruamel.yaml import YAML


class WorkflowConfig:
    """Workflow configuration that preserves the user's original YAML text."""

    def __init__(self, data: Dict[str, Any] = None, raw_yaml: Optional[str] = None):
        """Initialize with raw configuration data.

        Args:
            data: Raw configuration dictionary (used by tests and legacy callers)
            raw_yaml: Original YAML string to preserve formatting
        """
        self.data = data or {}
        self.raw_yaml = raw_yaml or ""

    @classmethod
    def from_yaml(cls, yaml_str: str) -> 'WorkflowConfig':
        """Create from YAML string, preserving the original text."""
        yaml = YAML()
        yaml.preserve_quotes = True

        data = yaml.load(yaml_str) or {}
        return cls(data, raw_yaml=yaml_str)

    def get(self, key: str, default: Any = None) -> Any:
        """Get a configuration value by key."""
        return self.data.get(key, default)

    def set(self, key: str, value: Any) -> None:
        """Set a configuration value."""
        self.data[key] = value

    def __bool__(self) -> bool:
        """Return True if config has any data or raw YAML."""
        return bool(self.data) or bool(self.raw_yaml)
