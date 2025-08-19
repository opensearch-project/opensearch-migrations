import re
from pydantic import BaseModel, ConfigDict

from console_link.models.flavor import Flavor


class Version(BaseModel):
    flavor: Flavor
    major: int = 0
    minor: int = 0
    patch: int = 0
    
    model_config = ConfigDict(
        json_encoders={
            Flavor: lambda v: v.value
        }
    )
    
    def __str__(self) -> str:
        """Convert version to string format"""
        return f"{self.flavor.value} {self.major}.{self.minor}.{self.patch}"
    
    @classmethod
    def from_string(cls, version_str: str) -> 'Version':
        """Parse a version string into a Version object"""
        if not version_str:
            raise ValueError("Version string cannot be empty")
        
        version_str = version_str.lower().strip()
        
        # Try to match flavor
        flavor_match = None
        remaining_str = version_str
        
        # Try each flavor, starting with the longest shorthand
        for flavor in sorted(Flavor, key=lambda f: len(f.shorthand), reverse=True):
            if version_str.startswith(flavor.value):
                flavor_match = flavor
                remaining_str = version_str[len(flavor.value):].strip()
                break
            elif version_str.startswith(flavor.shorthand):
                flavor_match = flavor
                remaining_str = version_str[len(flavor.shorthand):].strip()
                break
        
        if not flavor_match:
            raise ValueError(f"Unable to determine flavor from '{version_str}'")
        
        # Clean up and parse version numbers
        remaining_str = re.sub(r'^[_v]+', '', remaining_str)
        version_parts = re.split(r'[\\._-]', remaining_str)
        
        try:
            major = int(version_parts[0])
            minor = 0 if len(version_parts) <= 1 or version_parts[1] == 'x' else int(version_parts[1])
            patch = 0 if len(version_parts) <= 2 or version_parts[2] == 'x' else int(version_parts[2])
            
            return cls(flavor=flavor_match, major=major, minor=minor, patch=patch)
        except Exception as e:
            raise ValueError(f"Unable to parse version numbers from '{version_str}': {str(e)}")
    
    @classmethod
    def __get_validators__(cls):
        yield cls.validate
    
    @classmethod
    def validate(cls, value):
        if isinstance(value, cls):
            return value
        if isinstance(value, str):
            return cls.from_string(value)
        if isinstance(value, dict):
            return cls(**value)
        raise ValueError(f"Cannot parse Version from {value}")
