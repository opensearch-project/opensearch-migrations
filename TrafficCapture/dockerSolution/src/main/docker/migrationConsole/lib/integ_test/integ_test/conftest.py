# conftest.py
import pytest
import uuid
import logging
from typing import List
from .cluster_version import ClusterVersion
from .test_cases.ma_test_base import ClusterVersionCombinationUnsupported
from .test_cases.basic_tests import *
from .test_cases.multi_type_tests import *

from console_link.cli import Context
from console_link.environment import Environment

logger = logging.getLogger(__name__)

# Dynamically collect all test case classes that have been imported above
ALL_TEST_CASES = [cls for cls in globals().values() if isinstance(cls, type)
                  and cls.__module__.__contains__("integ_test.test_cases")
                  and cls.__name__.startswith("Test")]


def _split_test_ids(option_str: str):
    # Split the string by comma and strip whitespace
    return [tid.strip() for tid in option_str.split(",")]


def pytest_addoption(parser):
    parser.addoption("--unique_id", action="store", default=uuid.uuid4().hex)
    parser.addoption("--stage", action="store", default="dev")
    parser.addoption("--source_version", action="store", default="ES_7.10")
    parser.addoption("--target_version", action="store", default="OS_2.x")
    parser.addoption("--test_ids", action="store", default=[], type=_split_test_ids,
                     help="Specify test IDs like '0001,0003' to filter tests to execute")
    parser.addoption("--config_file_path", action="store", default="/config/migration_services.yaml",
                     help="Path to config file for console library")
    parser.addoption("--source_proxy_alb_endpoint", action="store", default=None,
                     help="Specify the Migration ALB endpoint for the source capture proxy")
    parser.addoption("--target_proxy_alb_endpoint", action="store", default=None,
                     help="Specify the Migration ALB endpoint for the target proxy")


def pytest_generate_tests(metafunc):
    if metafunc.function.__name__ == "test_migration_assistant_workflow":
        source_option = metafunc.config.getoption("source_version")
        source_version = ClusterVersion(version_str=source_option)
        target_option = metafunc.config.getoption("target_version")
        target_version = ClusterVersion(version_str=target_option)
        console_config_path = metafunc.config.getoption("config_file_path")
        console_link_env: Environment = Context(console_config_path).env
        unique_id = metafunc.config.getoption("unique_id")
        test_ids_list = metafunc.config.getoption("test_ids")
        test_cases_param = _generate_test_cases(source_version, target_version, console_config_path, console_link_env,
                                                unique_id, test_ids_list)
        metafunc.parametrize("test_cases", test_cases_param)


def _filter_test_cases(test_ids_list: List[str]) -> List:
    if not test_ids_list:
        return ALL_TEST_CASES
    filtered_cases = []
    for case in ALL_TEST_CASES:
        if test_ids_list and any(tid in str(case) for tid in test_ids_list):
            filtered_cases.append(case)
    return filtered_cases


def _generate_test_cases(source_version: ClusterVersion, target_version: ClusterVersion, console_config_path: str,
                         console_link_env: Environment, unique_id: str, test_ids_list: List[str]):
    parallel_test_cases_to_run = []
    isolated_test_cases_to_run = []
    unsupported_test_cases = []
    cases = _filter_test_cases(test_ids_list)
    for test_case in cases:
        try:
            valid_case = test_case(source_version=source_version, target_version=target_version,
                                   console_config_path=console_config_path, console_link_env=console_link_env,
                                   unique_id=unique_id)
            if valid_case.run_isolated:
                isolated_test_cases_to_run.append([valid_case])
            else:
                parallel_test_cases_to_run.append(valid_case)
        except ClusterVersionCombinationUnsupported:
            unsupported_test_cases.append(test_case)
    logger.info(f"Parallel test cases to run ({len(parallel_test_cases_to_run)}) - {parallel_test_cases_to_run}")
    logger.info(f"Isolated test cases to run ({len(isolated_test_cases_to_run)}) - {isolated_test_cases_to_run}")
    if parallel_test_cases_to_run:
        isolated_test_cases_to_run.append(parallel_test_cases_to_run)
    if unsupported_test_cases:
        logger.info(f"The following tests are incompatible with the cluster version specified and will be skipped: {unsupported_test_cases}")
    return isolated_test_cases_to_run


@pytest.fixture
def unique_id(pytestconfig):
    return pytestconfig.getoption("unique_id")
