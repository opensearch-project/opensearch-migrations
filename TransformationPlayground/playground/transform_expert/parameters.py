from enum import Enum

"""
This module contains enumerated types representing the separate axes that a transformation can be defined along.
"""

class SourceVersion(Enum):
    ES_6_8 = "Elasticsearch 6.8"
    ES_7_10 = "Elasticsearch 7.10"

class TargetVersion(Enum):
    OS_2_17 = "OpenSearch 2.17"

class TransformType(Enum):
    INDEX = "Index"

class TransformLanguage(Enum):
    PYTHON = "Python"