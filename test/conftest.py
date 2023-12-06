# conftest.py
import pytest


def pytest_addoption(parser):
    parser.addoption("--proxy_endpoint", action="store", default="https://localhost:9200")
    parser.addoption("--source_endpoint", action="store", default="https://localhost:19200")
    parser.addoption("--target_endpoint", action="store", default="https://localhost:29200")
    parser.addoption("--source_auth_type", action="store", default="basic", choices=["none", "basic", "sigv4"])
    parser.addoption("--source_verify_ssl", action="store", default="False", choices=["True", "False"])
    parser.addoption("--target_auth_type", action="store", default="basic", choices=["none", "basic", "sigv4"])
    parser.addoption("--target_verify_ssl", action="store", default="False", choices=["True", "False"])
    parser.addoption("--source_username", action="store", default="admin")
    parser.addoption("--source_password", action="store", default="admin")
    parser.addoption("--target_username", action="store", default="admin")
    parser.addoption("--target_password", action="store", default="admin")


@pytest.fixture
def proxy_endpoint(pytestconfig):
    return pytestconfig.getoption("proxy_endpoint")


@pytest.fixture
def source_endpoint(pytestconfig):
    return pytestconfig.getoption("source_endpoint")


@pytest.fixture
def target_endpoint(pytestconfig):
    return pytestconfig.getoption("target_endpoint")


@pytest.fixture
def source_auth_type(pytestconfig):
    return pytestconfig.getoption("source_auth_type")


@pytest.fixture
def source_username(pytestconfig):
    return pytestconfig.getoption("source_username")


@pytest.fixture
def source_password(pytestconfig):
    return pytestconfig.getoption("source_password")


@pytest.fixture
def target_auth_type(pytestconfig):
    return pytestconfig.getoption("target_auth_type")


@pytest.fixture
def target_username(pytestconfig):
    return pytestconfig.getoption("target_username")


@pytest.fixture
def target_password(pytestconfig):
    return pytestconfig.getoption("target_password")


@pytest.fixture
def target_verify_ssl(pytestconfig):
    return pytestconfig.getoption("target_verify_ssl")


@pytest.fixture
def source_verify_ssl(pytestconfig):
    return pytestconfig.getoption("source_verify_ssl")
