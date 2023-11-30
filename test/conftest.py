# conftest.py
import pytest


def pytest_addoption(parser):
    parser.addoption("--source_endpoint", action="store")
    parser.addoption("--target_endpoint", action="store")
    parser.addoption("--auth_type", action="store", default="none", choices=["none", "basic", "sigv4"])
    parser.addoption("--username", action="store", default="admin")
    parser.addoption("--password", action="store", default="admin")


@pytest.fixture
def source_endpoint(pytestconfig):
    return pytestconfig.getoption("source_endpoint")


@pytest.fixture
def target_endpoint(pytestconfig):
    return pytestconfig.getoption("target_endpoint")


@pytest.fixture
def auth_type(pytestconfig):
    return pytestconfig.getoption("auth_type")


@pytest.fixture
def username(pytestconfig):
    return pytestconfig.getoption("username")


@pytest.fixture
def password(pytestconfig):
    return pytestconfig.getoption("password")
