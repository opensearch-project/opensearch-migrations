from dataclasses import dataclass
import json
from typing import Any, Callable, Dict, List

from transform_expert.utils.opensearch_client import OpenSearchClient

@dataclass
class TransformReport:
    input: Dict[str, Any]
    output: List[Dict[str, Any]]
    report: str
    passed: bool

    def to_json(self) -> Dict[str, Any]:
        return {
            "input": self.input,
            "output": self.output,
            "report": self.report,
            "passed": self.passed
        }

def test_index_transform(input: Dict[str, Any], transform: Callable[[Dict[str, Any]], Dict[str, Any]], test_client: OpenSearchClient) -> TransformReport:
    report_incidents = []
    output = []
    try:
        output = transform(input)
        report_incidents.append("Invoked the transform function without errors.")

        for index_def in output:
            index_name = index_def["indexName"]
            settings = index_def["indexJson"]
            report_incidents.append(f"Attempting to create & delete index '{index_name}' with transformed settings...")
            create_response = test_client.create_index(index_name, settings)
            report_incidents.append(f"Created index '{index_name}'.  Response: \n{json.dumps(create_response)}")

            delete_response = test_client.delete_index(index_name)
            report_incidents.append(f"Deleted index '{index_name}'.  Response: \n{json.dumps(delete_response)}")

        passed = True
    except Exception as e:
        report_incidents.append(f"Error: {str(e)}")
        passed = False

    return TransformReport(input=input, output=output, report=" ".join(report_incidents), passed=passed)