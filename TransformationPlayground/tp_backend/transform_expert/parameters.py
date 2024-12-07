from enum import Enum

"""
This module contains enumerated types representing the separate axes that a transformation can be defined along.
"""

class SourceVersion(Enum):
    ES_6_8 = "Elasticsearch 6.8"

class TargetVersion(Enum):
    OS_2_14 = "OpenSearch 2.14"

class InputShapeType(Enum):
    INDEX = "Index"

class TransformLanguage(Enum):
    PYTHON = "Python"