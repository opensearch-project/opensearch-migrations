# Holds constants for unit tests
from os.path import dirname

TEST_RESOURCES_SUBPATH = "/resources/"
PIPELINE_CONFIG_RAW_FILE_PATH = dirname(__file__) + TEST_RESOURCES_SUBPATH + "test_pipeline_input.yaml"
PIPELINE_CONFIG_PICKLE_FILE_PATH = dirname(__file__) + TEST_RESOURCES_SUBPATH + "expected_parse_output.pickle"

INDEX1_NAME = "index1"
INDEX2_NAME = "index2"
INDEX3_NAME = "index3"
SETTINGS_KEY = "settings"
MAPPINGS_KEY = "mappings"
INDEX_KEY = "index"
NUM_SHARDS_SETTING = "number_of_shards"
NUM_REPLICAS_SETTING = "number_of_replicas"
BASE_INDICES_DATA = {
    INDEX1_NAME: {
        SETTINGS_KEY: {
            INDEX_KEY: {
                # Internal data
                "version": 1,
                "uuid": "test",
                # Non-internal
                "is_filtered": False
            },
            "location": "test"
        },
        MAPPINGS_KEY: {
            "bool_key": {"type": "boolean"},
            "location": {"type": "location"}
        }
    },
    INDEX2_NAME: {
        SETTINGS_KEY: {
            INDEX_KEY: {
                NUM_SHARDS_SETTING: 2,
                NUM_REPLICAS_SETTING: 1
            }
        },
        MAPPINGS_KEY: {
            "dynamic": "strict"
        }
    },
    INDEX3_NAME: {
        SETTINGS_KEY: {
            INDEX_KEY: {
                NUM_SHARDS_SETTING: 1,
                NUM_REPLICAS_SETTING: 1
            }
        },
        MAPPINGS_KEY: {
            "id": {"type": "keyword"}
        }
    }
}
# Based on the contents of test_pipeline_input.yaml
SOURCE_ENDPOINT = "http://host1/"
TARGET_ENDPOINT = "https://os_host/"

# Utility logic to update the pickle file if/when the input file is updated
# import yaml
# import pickle
# if __name__ == '__main__':
#     with open(PIPELINE_CONFIG_RAW_FILE_PATH, 'r') as test_input:
#         test_config = yaml.safe_load(test_input)
#     with open(PIPELINE_CONFIG_PICKLE_FILE_PATH, 'wb') as out:
#         pickle.dump(test_config, out)
