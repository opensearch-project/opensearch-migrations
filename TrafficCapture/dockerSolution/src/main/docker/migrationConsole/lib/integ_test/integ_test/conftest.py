# conftest.py
import json
import os
from pathlib import Path

import pytest
import uuid
import logging
from typing import List

from .test_cases.ma_test_base import ClusterVersionCombinationUnsupported
from .test_cases.basic_tests import *
from .test_cases.multi_type_tests import *
from .test_cases.backfill_tests import *

from console_link.cli import Context
from console_link.environment import Environment

logger = logging.getLogger(__name__)

# Dynamically collect all test case classes that have been imported above
ALL_TEST_CASES = [cls for cls in globals().values() if isinstance(cls, type) and
                  cls.__module__.__contains__("integ_test.test_cases") and
                  cls.__name__.startswith("Test")]


def _split_test_ids(option_str: str):
    # Split the string by comma and strip whitespace
    return [tid.strip() for tid in option_str.split(",")]


def pytest_addoption(parser):
    parser.addoption("--unique_id", action="store", default=uuid.uuid4().hex)
    parser.addoption("--stage", action="store", default="dev")
    parser.addoption("--test_ids", action="store", default=[], type=_split_test_ids,
                     help="Specify test IDs like '0001,0003' to filter tests to execute")
    parser.addoption("--config_file_path", action="store", default="/config/migration_services.yaml",
                     help="Path to config file for console library")
    parser.addoption("--source_proxy_alb_endpoint", action="store", default=None,
                     help="Specify the Migration ALB endpoint for the source capture proxy")
    parser.addoption("--target_proxy_alb_endpoint", action="store", default=None,
                     help="Specify the Migration ALB endpoint for the target proxy")


def pytest_configure(config):
    # Store data to print in report at end of test execution
    config.collected_data = []
    config.test_summary = {
        "passed": 0,
        "failed": 0,
        "source_version": "",
        "target_version": ""
    }


def pytest_generate_tests(metafunc):
    if metafunc.function.__name__ == "test_migration_assistant_workflow":
        console_config_path = metafunc.config.getoption("config_file_path")
        console_link_env: Environment = Context(console_config_path).env
        unique_id = metafunc.config.getoption("unique_id")
        metafunc.config.test_summary["source_version"] = console_link_env.source_cluster.version
        metafunc.config.test_summary["target_version"] = console_link_env.target_cluster.version
        test_ids_list = metafunc.config.getoption("test_ids")
        test_cases_param = _generate_test_cases(console_config_path, console_link_env, unique_id, test_ids_list)
        metafunc.parametrize("test_cases", test_cases_param)


def _filter_test_cases(test_ids_list: List[str]) -> List:
    if not test_ids_list:
        return ALL_TEST_CASES
    filtered_cases = []
    for case in ALL_TEST_CASES:
        if test_ids_list and any(tid in str(case) for tid in test_ids_list):
            filtered_cases.append(case)
    return filtered_cases


def _generate_test_cases(console_config_path: str, console_link_env: Environment, unique_id: str,
                         test_ids_list: List[str]):
    aggregated_test_cases_to_run = []
    isolated_test_cases_to_run = []
    unsupported_test_cases = []
    cases = _filter_test_cases(test_ids_list)
    for test_case in cases:
        try:
            valid_case = test_case(console_config_path=console_config_path, console_link_env=console_link_env,
                                   unique_id=unique_id)
            if valid_case.run_isolated:
                isolated_test_cases_to_run.append([valid_case])
            else:
                aggregated_test_cases_to_run.append(valid_case)
        except ClusterVersionCombinationUnsupported:
            unsupported_test_cases.append(test_case)
    logger.info(f"Aggregated test cases to run ({len(aggregated_test_cases_to_run)}) - {aggregated_test_cases_to_run}")
    logger.info(f"Isolated test cases to run ({len(isolated_test_cases_to_run)}) - {isolated_test_cases_to_run}")
    if aggregated_test_cases_to_run:
        isolated_test_cases_to_run.append(aggregated_test_cases_to_run)
    if unsupported_test_cases:
        logger.info(f"The following tests are incompatible with the cluster version specified and will be "
                    f"skipped: {unsupported_test_cases}")
    return isolated_test_cases_to_run


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    # Hook invoked after each test phase, used to update test success/failure data
    outcome = yield
    rep = outcome.get_result()
    if rep.when == "call":
        # Retrieve any custom data attached during the test, if available.
        test_data = getattr(item, "test_data", {})
        test_data["result"] = "passed" if rep.passed else "failed"
        test_data["duration"] = rep.duration
        # Update the summary counts.
        if rep.passed:
            item.config.test_summary["passed"] += 1
        else:
            item.config.test_summary["failed"] += 1
            if hasattr(rep.longrepr, "reprcrash"):
                test_data["error"] = rep.longrepr.reprcrash.message
        item.config.collected_data.append(test_data)


def pytest_sessionfinish(session, exitstatus):
    # Write test report file at end of test session
    unique_id = session.config.getoption("unique_id")
    results = {
        "summary": session.config.test_summary,
        "tests": session.config.collected_data,
    }
    library_dir = Path(__file__).resolve().parent.parent
    file_path_to_create = f"{library_dir}/results/{unique_id}/test_report.json"
    directory = os.path.dirname(file_path_to_create)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(file_path_to_create, "w") as f:
        json.dump(results, f, indent=4)


@pytest.fixture
def record_data(request):
    # Fixture for tests to record data to print in test report
    def _record(test_data):
        request.node.test_data = test_data
    return _record


@pytest.fixture
def unique_id(pytestconfig):
    return pytestconfig.getoption("unique_id")
