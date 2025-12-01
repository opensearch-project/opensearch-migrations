import pytest
from unittest.mock import patch

from console_link.models.container_utils import get_version_str, VERSION_PREFIX


@pytest.fixture
def fake_home(tmp_path):
    """Temporarily override Path.home() to point to a temp dir"""
    with patch("pathlib.Path.home", return_value=tmp_path):
        yield tmp_path


def test_version_file_present_and_valid(fake_home):
    version_path = fake_home / "VERSION"
    version_path.write_text("1.2.3\n")

    assert get_version_str() == f"{VERSION_PREFIX} 1.2.3"


def test_version_file_present_and_valid_no_new_line(fake_home):
    version_path = fake_home / "VERSION"
    version_path.write_text("1.2.3")

    assert get_version_str() == f"{VERSION_PREFIX} 1.2.3"


def test_version_file_empty(fake_home, caplog):
    version_path = fake_home / "VERSION"
    version_path.write_text("\n\n")

    with caplog.at_level("INFO"):
        result = get_version_str()
        assert result == f"{VERSION_PREFIX} unknown"
        assert "VERSION file is empty" in caplog.text


def test_version_file_missing(fake_home, caplog):
    with caplog.at_level("INFO"):
        result = get_version_str()
        assert result == f"{VERSION_PREFIX} unknown"
        assert "VERSION file not found" in caplog.text
