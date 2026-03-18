"""
Tests for CommandResult.display() handling of falsy values.

CommandResult.display() previously used `if self.value:` which returns ""
for falsy values like 0, False, and "". The fix uses `is not None` instead.
"""
from console_link.models.command_result import CommandResult


class TestCommandResultDisplay:
    def test_display_string_value(self):
        assert CommandResult(success=True, value="hello").display() == "hello"

    def test_display_none_value(self):
        assert CommandResult(success=True, value=None).display() == ""

    def test_display_zero_value(self):
        """Zero is a valid value and should display as '0', not ''."""
        assert CommandResult(success=True, value=0).display() == "0"

    def test_display_false_value(self):
        """False is a valid value and should display as 'False', not ''."""
        assert CommandResult(success=True, value=False).display() == "False"

    def test_display_empty_string_value(self):
        """Empty string is a valid value and should display as '', not ''."""
        assert CommandResult(success=True, value="").display() == ""

    def test_display_exception_value(self):
        assert CommandResult(success=False, value=ValueError("err")).display() == "err"

    def test_display_integer_value(self):
        assert CommandResult(success=True, value=42).display() == "42"
