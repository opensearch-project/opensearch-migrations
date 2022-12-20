from dataclasses import dataclass

ENGINE_ELASTICSEARCH = "Elasticsearch"
ENGINE_OPENSEARCH = "OpenSearch"

class CouldNotParseEngineVersionException(Exception):
    def __init__(self, version_string: str):
        super().__init__(f"Could not parse version string: {version_string}.  Expected something like 'ES_7_10_2' or"
            " 'OS_1_3_6'."
        )

@dataclass
class EngineVersion:
    engine: str
    major: int
    minor: int
    patch: int

def get_version(version_string: str) -> EngineVersion:
    # expected an input string like: "ES_7_10_2"
    try:
        raw_engine, raw_major, raw_minor, raw_patch = version_string.split("_")
    except:
        raise CouldNotParseEngineVersionException(version_string)

    if "ES" == raw_engine:
        engine = ENGINE_ELASTICSEARCH
    elif "OS" == raw_engine:
        engine = ENGINE_OPENSEARCH
    else:
        raise CouldNotParseEngineVersionException(version_string)

    try:
        engine_version = EngineVersion(engine, int(raw_major), int(raw_minor), int(raw_patch))
    except:
        raise CouldNotParseEngineVersionException(version_string)

    return engine_version
