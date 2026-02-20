import pytest

from console_link.models.client_options import ClientOptions


def test_valid_client_options_config():
    user_agent = "test_agent_v1.0"
    custom_client_config = {
        "user_agent_extra": user_agent
    }
    client_options = ClientOptions(custom_client_config)

    assert isinstance(client_options, ClientOptions)
    assert client_options.user_agent_extra == user_agent


def test_valid_empty_client_options_config():
    custom_client_config = {}
    client_options = ClientOptions(custom_client_config)

    assert isinstance(client_options, ClientOptions)
    assert client_options.user_agent_extra is None


def test_invalid_client_options_config():
    custom_client_config = {
        "agent": "test-agent_v1.0"
    }
    with pytest.raises(ValueError) as excinfo:
        ClientOptions(custom_client_config)
    assert "Invalid config file for client options" in excinfo.value.args[0]
