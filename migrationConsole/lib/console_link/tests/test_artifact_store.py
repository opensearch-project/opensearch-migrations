"""Tests for the workflow artifact store, including Azure Blob Storage support."""

import sys
import types
from unittest.mock import MagicMock

import pytest

from azure.core.credentials import AzureSasCredential

from console_link.workflow.commands import artifact_store
from console_link.workflow.commands.artifact_store import (
    ArtifactStoreError,
    _parse_azure_uri,
    _parse_bucket_uri,
    delete_artifact_prefix,
    list_artifacts,
    read_artifact_text,
)


@pytest.fixture(autouse=True)
def isolate_env(monkeypatch, tmp_path):
    """Point the mounted-artifact lookup at an empty dir and clear cloud credentials."""
    monkeypatch.setenv(artifact_store.ARTIFACT_MOUNT_ENV, str(tmp_path / "no-such-mount"))
    monkeypatch.delenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, raising=False)
    monkeypatch.delenv(artifact_store.ARTIFACT_ENDPOINT_ENV, raising=False)
    monkeypatch.delenv(artifact_store.AZURE_ACCOUNT_KEY_ENV, raising=False)
    monkeypatch.delenv(artifact_store.AZURE_SAS_TOKEN_ENV, raising=False)


@pytest.fixture
def fake_blob_sdk(monkeypatch):
    """Install a stub azure.storage.blob so the lazy import succeeds without the real SDK."""
    azure_pkg = types.ModuleType("azure")
    storage_pkg = types.ModuleType("azure.storage")
    blob_mod = types.ModuleType("azure.storage.blob")
    blob_mod.BlobServiceClient = MagicMock(name="BlobServiceClient")
    # azure-core is imported from the same try as azure-storage-blob, so it needs a stub too.
    # AzureSasCredential is the real class: the point of wrapping a SAS token is the type, so a
    # mock would let the assertions pass against a bare string.
    core_pkg = types.ModuleType("azure.core")
    credentials_mod = types.ModuleType("azure.core.credentials")
    credentials_mod.AzureSasCredential = AzureSasCredential
    # Register parents too so the import machinery never reaches the real filesystem.
    monkeypatch.setitem(sys.modules, "azure", azure_pkg)
    monkeypatch.setitem(sys.modules, "azure.storage", storage_pkg)
    monkeypatch.setitem(sys.modules, "azure.storage.blob", blob_mod)
    monkeypatch.setitem(sys.modules, "azure.core", core_pkg)
    monkeypatch.setitem(sys.modules, "azure.core.credentials", credentials_mod)
    return blob_mod


class TestParseBucketUriNoRegression:
    """_parse_bucket_uri must keep its exact (scheme, bucket) contract for s3 and gs."""

    def test_s3_uri_unchanged(self):
        assert _parse_bucket_uri("s3://my-bucket") == ("s3", "my-bucket")

    def test_s3_uri_with_path_unchanged(self):
        assert _parse_bucket_uri("s3://my-bucket/some/prefix") == ("s3", "my-bucket")

    def test_gs_uri_unchanged(self):
        assert _parse_bucket_uri("gs://my-bucket") == ("gs", "my-bucket")

    def test_gs_uri_with_path_unchanged(self):
        assert _parse_bucket_uri("gs://my-bucket/some/prefix") == ("gs", "my-bucket")

    def test_returns_exactly_two_elements(self):
        assert len(_parse_bucket_uri("s3://my-bucket/some/prefix")) == 2


class TestParseAzureUri:
    def test_account_and_container(self):
        assert _parse_azure_uri("azure://myacct/mycontainer") == ("myacct", "mycontainer")

    def test_container_comes_from_path_not_netloc(self):
        account, container = _parse_azure_uri("azure://myacct/mycontainer/nested/key.txt")
        assert account == "myacct"
        assert container == "mycontainer"

    def test_trailing_slash_is_tolerated(self):
        assert _parse_azure_uri("azure://myacct/mycontainer/") == ("myacct", "mycontainer")

    def test_missing_container_raises(self):
        with pytest.raises(ArtifactStoreError, match="invalid Azure artifact URI"):
            _parse_azure_uri("azure://myacct")

    def test_missing_account_raises(self):
        with pytest.raises(ArtifactStoreError, match="invalid Azure artifact URI"):
            _parse_azure_uri("azure:///mycontainer")


class TestAzureContainerClient:
    def test_missing_sdk_raises_artifact_store_error(self, monkeypatch):
        monkeypatch.setitem(sys.modules, "azure.storage.blob", None)
        with pytest.raises(ArtifactStoreError, match="azure-storage-blob is required"):
            artifact_store._azure_container_client("myacct", "mycontainer")

    def test_account_key_takes_precedence(self, monkeypatch, fake_blob_sdk):
        monkeypatch.setenv(artifact_store.AZURE_ACCOUNT_KEY_ENV, "the-key")
        monkeypatch.setenv(artifact_store.AZURE_SAS_TOKEN_ENV, "the-sas")
        artifact_store._azure_container_client("myacct", "mycontainer")
        # Named form, not the bare key: the SDK cannot infer the account from a custom endpoint.
        fake_blob_sdk.BlobServiceClient.assert_called_once_with(
            account_url="https://myacct.blob.core.windows.net",
            credential={"account_name": "myacct", "account_key": "the-key"},
        )

    def test_sas_token_used_when_no_account_key(self, monkeypatch, fake_blob_sdk):
        monkeypatch.setenv(artifact_store.AZURE_SAS_TOKEN_ENV, "the-sas")
        artifact_store._azure_container_client("myacct", "mycontainer")
        kwargs = fake_blob_sdk.BlobServiceClient.call_args.kwargs
        assert kwargs["account_url"] == "https://myacct.blob.core.windows.net"
        # Assert the wrapper type, not just the value: a bare string is only interpreted as a SAS
        # token when every query parameter is one the SDK recognizes.
        assert isinstance(kwargs["credential"], AzureSasCredential)
        assert kwargs["credential"].signature == "the-sas"

    def test_default_credential_used_when_no_env_credentials(self, monkeypatch, fake_blob_sdk):
        identity_mod = types.ModuleType("azure.identity")
        sentinel = object()
        identity_mod.DefaultAzureCredential = MagicMock(return_value=sentinel)
        monkeypatch.setitem(sys.modules, "azure.identity", identity_mod)
        artifact_store._azure_container_client("myacct", "mycontainer")
        fake_blob_sdk.BlobServiceClient.assert_called_once_with(
            account_url="https://myacct.blob.core.windows.net",
            credential=sentinel,
        )

    def test_missing_identity_sdk_raises_artifact_store_error(self, monkeypatch, fake_blob_sdk):
        monkeypatch.setitem(sys.modules, "azure.identity", None)
        with pytest.raises(ArtifactStoreError, match="azure-identity is required"):
            artifact_store._azure_container_client("myacct", "mycontainer")

    def test_endpoint_override_is_used_as_account_url(self, monkeypatch, fake_blob_sdk):
        monkeypatch.setenv(artifact_store.ARTIFACT_ENDPOINT_ENV, "http://127.0.0.1:10000/devstoreaccount1")
        monkeypatch.setenv(artifact_store.AZURE_ACCOUNT_KEY_ENV, "the-key")
        artifact_store._azure_container_client("devstoreaccount1", "mycontainer")
        fake_blob_sdk.BlobServiceClient.assert_called_once_with(
            account_url="http://127.0.0.1:10000/devstoreaccount1",
            credential={"account_name": "devstoreaccount1", "account_key": "the-key"},
        )

    @pytest.mark.parametrize("endpoint,account", [
        (None, "myacct"),
        ("http://127.0.0.1:10000/devstoreaccount1", "devstoreaccount1"),
        # The case the stubbed tests above cannot see. An emulator reached by service DNS is how
        # the chart already addresses the GCS emulator, and a bare account key here made the real
        # SDK raise "Unable to determine account name for shared key credential".
        ("http://azurite.migrations.svc.cluster.local:10000/devstoreaccount1", "devstoreaccount1"),
    ])
    def test_real_sdk_accepts_the_credential_for_any_endpoint(self, monkeypatch, endpoint, account):
        monkeypatch.setenv(artifact_store.AZURE_ACCOUNT_KEY_ENV, "a2V5")
        if endpoint:
            monkeypatch.setenv(artifact_store.ARTIFACT_ENDPOINT_ENV, endpoint)
        # No fake_blob_sdk: this has to reach the installed azure-storage-blob to be meaningful.
        client = artifact_store._azure_container_client(account, "mycontainer")
        assert client.account_name == account
        assert client.container_name == "mycontainer"

    def test_returns_container_client_for_requested_container(self, monkeypatch, fake_blob_sdk):
        monkeypatch.setenv(artifact_store.AZURE_ACCOUNT_KEY_ENV, "the-key")
        service = fake_blob_sdk.BlobServiceClient.return_value
        result = artifact_store._azure_container_client("myacct", "mycontainer")
        service.get_container_client.assert_called_once_with("mycontainer")
        assert result is service.get_container_client.return_value


class TestReadArtifactTextAzure:
    def test_reads_blob_from_container_in_path(self, monkeypatch):
        monkeypatch.setenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, "azure://myacct/mycontainer")
        container_client = MagicMock()
        container_client.get_blob_client.return_value.download_blob.return_value.readall.return_value = b"hello"
        factory = MagicMock(return_value=container_client)
        monkeypatch.setattr(artifact_store, "_azure_container_client", factory)

        assert read_artifact_text("some/key.txt") == "hello"
        factory.assert_called_once_with("myacct", "mycontainer")
        container_client.get_blob_client.assert_called_once_with("some/key.txt")

    def test_failure_is_wrapped_with_azure_uri(self, monkeypatch):
        monkeypatch.setenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, "azure://myacct/mycontainer")
        container_client = MagicMock()
        container_client.get_blob_client.side_effect = RuntimeError("boom")
        monkeypatch.setattr(artifact_store, "_azure_container_client", MagicMock(return_value=container_client))

        with pytest.raises(ArtifactStoreError, match=r"could not read azure://myacct/mycontainer/some/key.txt"):
            read_artifact_text("some/key.txt")

    def test_unsupported_scheme_still_raises(self, monkeypatch):
        monkeypatch.setenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, "ftp://myacct/mycontainer")
        with pytest.raises(ArtifactStoreError, match="unsupported URI scheme"):
            read_artifact_text("some/key.txt")

    def test_missing_sdk_error_is_not_rewrapped(self, monkeypatch):
        """A missing-SDK ArtifactStoreError must surface as-is, not as "could not read"."""
        monkeypatch.setenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, "azure://myacct/mycontainer")
        factory = MagicMock(side_effect=ArtifactStoreError("azure-storage-blob is required"))
        monkeypatch.setattr(artifact_store, "_azure_container_client", factory)

        with pytest.raises(ArtifactStoreError, match="^azure-storage-blob is required$"):
            read_artifact_text("some/key.txt")


class TestListArtifactsAzure:
    def test_lists_blobs_under_prefix(self, monkeypatch):
        monkeypatch.setenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, "azure://myacct/mycontainer")
        blob = MagicMock()
        blob.name = "wf-1/status.json"
        blob.last_modified = "2026-01-01T00:00:00Z"
        blob.size = 42
        container_client = MagicMock()
        container_client.list_blobs.return_value = [blob]
        factory = MagicMock(return_value=container_client)
        monkeypatch.setattr(artifact_store, "_azure_container_client", factory)

        assert list_artifacts("wf-1/") == [
            {"key": "wf-1/status.json", "last_modified": "2026-01-01T00:00:00Z", "size": 42}
        ]
        factory.assert_called_once_with("myacct", "mycontainer")
        container_client.list_blobs.assert_called_once_with(name_starts_with="wf-1/")

    def test_failure_is_wrapped_with_azure_uri(self, monkeypatch):
        monkeypatch.setenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, "azure://myacct/mycontainer")
        container_client = MagicMock()
        container_client.list_blobs.side_effect = RuntimeError("boom")
        monkeypatch.setattr(artifact_store, "_azure_container_client", MagicMock(return_value=container_client))

        with pytest.raises(ArtifactStoreError, match=r"could not list azure://myacct/mycontainer/wf-1/"):
            list_artifacts("wf-1/")

    def test_missing_sdk_error_is_not_rewrapped(self, monkeypatch):
        monkeypatch.setenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, "azure://myacct/mycontainer")
        factory = MagicMock(side_effect=ArtifactStoreError("azure-storage-blob is required"))
        monkeypatch.setattr(artifact_store, "_azure_container_client", factory)

        with pytest.raises(ArtifactStoreError, match="^azure-storage-blob is required$"):
            list_artifacts("wf-1/")


class TestDeleteArtifactPrefixAzure:
    def test_deletes_blobs_and_returns_count(self, monkeypatch):
        monkeypatch.setenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, "azure://myacct/mycontainer")
        first, second = MagicMock(), MagicMock()
        first.name = "wf-1/a.json"
        second.name = "wf-1/b.json"
        container_client = MagicMock()
        container_client.list_blobs.return_value = [first, second]
        factory = MagicMock(return_value=container_client)
        monkeypatch.setattr(artifact_store, "_azure_container_client", factory)

        assert delete_artifact_prefix("wf-1/") == 2
        factory.assert_called_once_with("myacct", "mycontainer")
        container_client.delete_blobs.assert_called_once_with("wf-1/a.json", "wf-1/b.json")

    def test_empty_prefix_deletes_nothing(self, monkeypatch):
        monkeypatch.setenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, "azure://myacct/mycontainer")
        container_client = MagicMock()
        container_client.list_blobs.return_value = []
        monkeypatch.setattr(artifact_store, "_azure_container_client", MagicMock(return_value=container_client))

        assert delete_artifact_prefix("wf-1/") == 0
        container_client.delete_blobs.assert_not_called()

    def test_failure_is_wrapped_with_azure_uri(self, monkeypatch):
        monkeypatch.setenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, "azure://myacct/mycontainer")
        container_client = MagicMock()
        container_client.list_blobs.side_effect = RuntimeError("boom")
        monkeypatch.setattr(artifact_store, "_azure_container_client", MagicMock(return_value=container_client))

        with pytest.raises(ArtifactStoreError, match=r"could not delete azure://myacct/mycontainer/wf-1/"):
            delete_artifact_prefix("wf-1/")

    def test_unsupported_scheme_still_raises(self, monkeypatch):
        monkeypatch.setenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, "ftp://myacct/mycontainer")
        with pytest.raises(ArtifactStoreError, match="unsupported URI scheme"):
            delete_artifact_prefix("wf-1/")

    def test_missing_sdk_error_is_not_rewrapped(self, monkeypatch):
        monkeypatch.setenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, "azure://myacct/mycontainer")
        factory = MagicMock(side_effect=ArtifactStoreError("azure-storage-blob is required"))
        monkeypatch.setattr(artifact_store, "_azure_container_client", factory)

        with pytest.raises(ArtifactStoreError, match="^azure-storage-blob is required$"):
            delete_artifact_prefix("wf-1/")

    def test_deletes_are_chunked_to_the_batch_limit(self, monkeypatch):
        """More blobs than one Blob Batch allows must be split across several delete_blobs calls."""
        monkeypatch.setenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, "azure://myacct/mycontainer")
        total = artifact_store.AZURE_DELETE_BATCH_SIZE + 1
        blobs = []
        for index in range(total):
            blob = MagicMock()
            blob.name = f"wf-1/{index}.json"
            blobs.append(blob)
        container_client = MagicMock()
        container_client.list_blobs.return_value = blobs
        monkeypatch.setattr(artifact_store, "_azure_container_client", MagicMock(return_value=container_client))

        assert delete_artifact_prefix("wf-1/") == total
        batch_sizes = [len(call.args) for call in container_client.delete_blobs.call_args_list]
        assert batch_sizes == [artifact_store.AZURE_DELETE_BATCH_SIZE, 1]
        deleted = [name for call in container_client.delete_blobs.call_args_list for name in call.args]
        assert deleted == [blob.name for blob in blobs]


class TestAzureClientConstructionFailuresAreWrapped:
    """A credential or endpoint problem must reach callers as ArtifactStoreError.

    show.py and reset.py catch only ArtifactStoreError, so anything else reaches the user as a
    traceback. The real SDK raises ValueError for cases this module can produce, for example a
    token credential against an http:// emulator endpoint.
    """

    @pytest.mark.parametrize("operation", [
        lambda: read_artifact_text("some/key.json"),
        lambda: list_artifacts("some/prefix"),
        lambda: delete_artifact_prefix("some/prefix"),
    ], ids=["read", "list", "delete"])
    def test_construction_valueerror_becomes_artifact_store_error(self, monkeypatch, operation):
        monkeypatch.setenv(artifact_store.REPO_ARTIFACTS_BUCKET_ENV, "azure://myacct/mycontainer")
        factory = MagicMock(side_effect=ValueError("Unable to determine account name"))
        monkeypatch.setattr(artifact_store, "_azure_container_client", factory)
        with pytest.raises(ArtifactStoreError, match="azure://myacct/mycontainer"):
            operation()
