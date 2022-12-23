from pathlib import Path
import json
from typing import List
import operator

from upgrade_testing_framework.core.versions_engine import EngineVersion

class KnowledgeBaseDoesntExistException(Exception):
    def __init__(self, knowledge_base_path):
        super().__init__(f"The path specified for the knowledge base doesn't exist or is not a directory: {knowledge_base_path}")

class ExpectationCantReadFileException(Exception):
    def __init__(self, expecation_path, original_exception):
        super().__init__(f"Unable to read test config file at path {expecation_path}.  Details: {str(original_exception)}")

class ExpectationFileNotJSONException(Exception):
    def __init__(self, expecation_path, original_exception):
        super().__init__(f"The test config at path {expecation_path} is not parsible as JSON.  Details: {str(original_exception)}")

class ExpectationMissingIdException(Exception):
    def __init__(self):
        super().__init__("An expectation was missing its id") # TODO make this better


COMP_OPERATION = {
    "gt": operator.gt,
    "lt": operator.lt,
    "gte": operator.ge,
    "lte": operator.le
}


class Expectation:
    def __init__(self, raw_expectation: dict):
        self.id = raw_expectation.get("id", None)
        if self.id is None:
            raise ExpectationMissingIdException()
        self.description = raw_expectation.get("description", None)
        self.version_filter = raw_expectation.get("versions", None)
        

    def is_relevant_to_version(self, version: EngineVersion) -> bool:
        # If no version_filter, assume relevant to all versions
        if self.version_filter is None:
            return True

        version_filters = self.version_filter if type(self.version_filter) is list else [self.version_filter]

        # Version filters have sections which are ORed together, each of which has
        # components (gt/gte and/or lt/lte), which are ANDed together.
        for section in version_filters:
            section_valid = True
            for op, comp_value in section.items():
                if not COMP_OPERATION[op](version, comp_value):
                    section_valid = False
                    break
            if section_valid:
                return True
        return False

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "description": self.description,
            "versions": self.version_filter
        }    

    def __eq__(self, other):
        return self.to_dict() == other.to_dict()



def load_knowledge_base(knowledge_base_path: str) -> List[Expectation]:
    expectations: List[Expectation] = []

    kb_path = Path(knowledge_base_path).absolute()

    # Confirm the Knowledge Base directory exists
    if not (kb_path.exists() and kb_path.is_dir()):
        raise KnowledgeBaseDoesntExistException(knowledge_base_path)

    # Open each json file in the directory and load it as an Expectation
    for expectation_file in [ef for ef in kb_path.iterdir() if ef.match("*.json")]:
        try:
            with expectation_file.open('r') as file_handle:
                raw_expectation = json.load(file_handle)
        except json.JSONDecodeError as exception:
            raise ExpectationFileNotJSONException(expectation_file, exception)
        except IOError as exception:
            raise ExpectationCantReadFileException(expectation_file, exception)
        if type(raw_expectation) is dict:
            expectations.append(Expectation(raw_expectation))
        else:
            expectations += [Expectation(e) for e in raw_expectation]

    return expectations
