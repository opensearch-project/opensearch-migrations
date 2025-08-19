from enum import Enum


class Flavor(str, Enum):
    ELASTICSEARCH = "elasticsearch"
    OPENSEARCH = "opensearch"
    
    @property
    def shorthand(self) -> str:
        """Return a shorthand representation of the flavor"""
        if self == Flavor.ELASTICSEARCH:
            return "es"
        elif self == Flavor.OPENSEARCH:
            return "os"
        return ""
