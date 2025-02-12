from abc import ABC, abstractmethod
from dataclasses import dataclass
import json
import logging
from typing import Any, Callable, Dict, List

from transform_expert.utils.opensearch_client import OpenSearchClient
from transform_expert.utils.transforms import TransformTask, load_transform


logger = logging.getLogger("transform_expert")


class TestTargetInnaccessibleError(Exception):
    pass

def test_target_connection(test_client: OpenSearchClient):
    # Check if the OpenSearch cluster is accessible
    if test_client.is_accessible():
        logger.info(f"OpenSearch cluster '{test_client.get_url()}' is accessible")
    else:
        error_message = f"OpenSearch cluster '{test_client.get_url()}' is not accessible"
        logger.error(error_message)
        raise TestTargetInnaccessibleError(error_message)

@dataclass
class ValidationReport:
    task: TransformTask
    report_entries: List[str]
    passed: bool

    def to_json(self) -> Dict[str, Any]:
        return {
            "task": self.task.to_json(),
            "report_entries": self.report_entries,
            "passed": self.passed
        }
    
    def append_entry(self, entry: str, logging_function: Callable[..., None]):
        logging_function(entry)
        self.report_entries.append(entry)

class TransformValidatorBase(ABC):
    def __init__(self, transform_task: TransformTask, test_client: OpenSearchClient):
        self.transform_task = transform_task
        self.test_client = test_client

    def _try_load_transform(self, report: ValidationReport) -> Callable[[Dict[str, Any]], List[Dict[str, Any]]]:
        try:
            report.append_entry("Attempting to load the transform function...", logger.info)
            transform_func = load_transform(report.task.transform)
            report.append_entry("Loaded the transform function without exceptions", logger.info)
        except Exception as e:
            report.append_entry("The transform function loading has failed", logger.error)
            raise e
        
        return transform_func
    
    def _try_invoke_transform(self, transform_func: Callable[[Dict[str, Any]], List[Dict[str, Any]]], report: ValidationReport) -> List[Dict[str, Any]]:
        try:
            report.append_entry("Attempting to invoke the transform function against the input...", logger.info)
            output = transform_func(report.task.input)
            report.task.output = output
            report.append_entry("Invoked the transform function without exceptions", logger.info)
        except Exception as e:
            report.append_entry("The transform function invocation has failed", logger.error)
            raise e

        return output
    
    @abstractmethod
    def _try_test_against_target(self, output: List[Dict[str, Any]], report: ValidationReport):
        pass

    def validate(self) -> ValidationReport:
        report = ValidationReport(task=self.transform_task, report_entries=[], passed=False)
        try:
            transform_func = self._try_load_transform(report)
            output = self._try_invoke_transform(transform_func, report)
            self._try_test_against_target(output, report)
            report.passed = True
        except Exception as e:
            report.passed = False
            report.append_entry(f"Error: {str(e)}", logger.error)

        logger.info(f"Transform function testing complete.  Passed: {report.passed}")
        logger.debug(f"Transform function testing report:\n{json.dumps(report.to_json(), indent=4)}")

        return report

class IndexTransformValidator(TransformValidatorBase):
    def _try_test_against_target(self, output: List[Dict[str, Any]], report: ValidationReport):
        if self.test_client:
            report.append_entry(f"The transformed output has {len(output)} Index entries.", logger.info)
            report.append_entry(f"Using target cluster for testing: {self.test_client.get_url()}", logger.info)
            for index_def in output:
                try:
                    index_name = index_def["index_name"]
                    settings = index_def["index_json"]
                    report.append_entry(f"Attempting to create & delete index '{index_name}' with transformed settings...", logger.info)
                    create_response = self.test_client.create_index(index_name, settings)
                    report.append_entry(f"Created index '{index_name}'.  Response: \n{json.dumps(create_response)}", logger.info)

                    delete_response = self.test_client.delete_index(index_name)
                    report.append_entry(f"Deleted index '{index_name}'.  Response: \n{json.dumps(delete_response)}", logger.info)
                except Exception as e:
                    report.append_entry(f"Error when testing creation/deletion of index: {str(e)}", logger.error)
                    raise e
        else:
            report.append_entry("No target cluster provided.  Skipping index creation/deletion tests.", logger.info)
