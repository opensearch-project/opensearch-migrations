from pathlib import Path
import json
from typing import List
import operator
import logging

from cluster_migration_core.core.versions_engine import EngineVersion


class KnowledgeBaseDirectoryDoesntExistException(Exception):
    def __init__(self, knowledge_base_path):
        super().__init__(f"The path specified for the knowledge base doesn't exist or "
                         f"is not a directory: {knowledge_base_path}")


class ExpectationCantReadFileException(Exception):
    def __init__(self, expecation_path, original_exception):
        super().__init__(f"Unable to read test config file at path {expecation_path}. "
                         f"Details: {str(original_exception)}")


class ExpectationFileNotJSONException(Exception):
    def __init__(self, expecation_path, original_exception):
        super().__init__(f"The test config at path {expecation_path} is not parsible as JSON. "
                         f"Details: {str(original_exception)}")


class ExpectationMissingIdException(Exception):
    def __init__(self):
        super().__init__("An expectation was missing its id")  # TODO make this better


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
        self.version_filters = raw_expectation.get("versions", None)
        self.logger = logging.getLogger(__name__)

    def applies_to_version(self, version: EngineVersion) -> bool:
        # If no version_filter, assume relevant to all versions
        if self.version_filters is None:
            return True

        self.logger.debug(f"Comparing version {version} to the version filter {self.version_filters}.")
        # Version filters have sections which are OR'd together, each of which has
        # components (gt/gte and/or lt/lte), which are AND'd together.
        for section in self.version_filters:  # For each set of conditionals
            section_valid = True

            # Check the individual components within the AND'd set and exit the section if any are false.
            for op, comp_value in section.items():
                if not COMP_OPERATION[op](version, comp_value):
                    self.logger.debug(f"Version {version} does not satisfy conditon `{op} {comp_value}`")
                    section_valid = False
                    break

            if section_valid:  # All the AND'd conditionals yielded True
                self.logger.debug(f"Version {version} applies because it satisfies {section}.")
                return True  # If any of the OR'd sections yields True, return True

        self.logger.debug(f"Version {version} does not apply because it failed to satisfy any "
                          "sections of the version filter.")
        return False  # None of the OR'd sections yielded True

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "description": self.description,
            "versions": self.version_filters
        }

    def __eq__(self, other):
        return self.to_dict() == other.to_dict()


def load_knowledge_base(knowledge_base_path: str) -> List[Expectation]:
    expectations: List[Expectation] = []

    kb_path = Path(knowledge_base_path).absolute()

    # Confirm the Knowledge Base directory exists
    if not (kb_path.exists() and kb_path.is_dir()):
        raise KnowledgeBaseDirectoryDoesntExistException(knowledge_base_path)

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
