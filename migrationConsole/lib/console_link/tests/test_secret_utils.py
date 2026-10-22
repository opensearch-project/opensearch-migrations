from unittest.mock import MagicMock

import click
import pytest

from console_link.workflow.commands.secret_utils import process_secrets


def make_store(keys_by_name):
    store = MagicMock()
    store.get_secret_keys.side_effect = lambda name: keys_by_name.get(name)
    return store


@pytest.mark.parametrize("keys", [["accountKey"], ["sasToken"]])
def test_azure_secret_with_a_credential_key_passes(keys):
    store = make_store({"azure-creds": keys})

    process_secrets(store, {"valid": True, "validSecrets": [], "azureSecrets": ["azure-creds"]})

    store.get_secret_keys.assert_called_once_with("azure-creds")


@pytest.mark.parametrize("interactive", [True, False])
def test_missing_azure_secret_fails_with_kubectl_hint(interactive):
    store = make_store({})

    with pytest.raises(click.ClickException) as excinfo:
        process_secrets(store, {"valid": True, "azureSecrets": ["azure-creds"]}, interactive=interactive)

    assert "azure-creds" in excinfo.value.message
    assert "kubectl create secret generic" in excinfo.value.message
    store.save_secret.assert_not_called()


def test_azure_secret_without_a_credential_key_fails():
    store = make_store({"azure-creds": ["username", "password"]})

    with pytest.raises(click.ClickException) as excinfo:
        process_secrets(store, {"valid": True, "azureSecrets": ["azure-creds"]})

    assert "azure-creds" in excinfo.value.message


def test_azure_secrets_are_not_treated_as_basic_auth():
    store = make_store({"azure-creds": ["accountKey"]})

    process_secrets(store, {"valid": True, "validSecrets": [], "azureSecrets": ["azure-creds"]})

    store.secret_exists.assert_not_called()
    store.save_secret.assert_not_called()
