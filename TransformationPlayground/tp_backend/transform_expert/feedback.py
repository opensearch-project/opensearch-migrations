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
class TransformTaskTestReport:
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

def test_index_transform(transform_task: TransformTask, test_client: OpenSearchClient) -> TransformTaskTestReport:
    logger.info(f"Testing the transform function for task '{transform_task.transform_id}'...")
    report = TransformTaskTestReport(task=transform_task, report_entries=[], passed=False)
    try:
        try:
            report.append_entry("Attempting to load the transform function...", logger.info)
            transform_func = load_transform(transform_task.transform)
            report.append_entry("Loaded the transform function without exceptions", logger.info)
        except Exception as e:
            report.append_entry("The transform function loading has failed", logger.error)
            raise e

        report.append_entry("Attempting to invoke the transform function against the input...", logger.info)
        output = transform_func(transform_task.input)
        report.task.output = output
        report.append_entry("Invoked the transform function without exceptions", logger.info)

        # TODO: Validate the output shape matches the spec

        if test_client:
            report.append_entry(f"The transformed output has {len(output)} Index entries.", logger.info)
            for index_def in output:
                try:
                    index_name = index_def["index_name"]
                    settings = index_def["index_json"]
                    report.append_entry(f"Attempting to create & delete index '{index_name}' with transformed settings...", logger.info)
                    create_response = test_client.create_index(index_name, settings)
                    report.append_entry(f"Created index '{index_name}'.  Response: \n{json.dumps(create_response)}", logger.info)

                    delete_response = test_client.delete_index(index_name)
                    report.append_entry(f"Deleted index '{index_name}'.  Response: \n{json.dumps(delete_response)}", logger.info)
                except Exception as e:
                    report.append_entry(f"Error when testing creation/deletion of index: {str(e)}", logger.error)
                    raise e
        else:
            report.append_entry("No target cluster provided.  Skipping index creation/deletion tests.", logger.info)

        report.passed = True
    except Exception as e:
        report.append_entry("The transform function testing has failed", logger.error)
        report.append_entry(f"Error: {str(e)}", logger.error)

    logger.info(f"Transform function testing complete.  Passed: {report.passed}")
    logger.debug(f"Transform function testing report:\n{json.dumps(report.to_json(), indent=4)}")

    return report