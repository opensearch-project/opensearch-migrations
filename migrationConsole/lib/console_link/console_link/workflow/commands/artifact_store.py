"""Helpers for workflow output artifacts stored in S3, GCS, or Azure Blob Storage."""

import logging
import os
from pathlib import Path
from typing import Dict, List, Optional
from urllib.parse import urlparse

import boto3
from botocore import UNSIGNED
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError

logger = logging.getLogger(__name__)

REPO_ARTIFACTS_BUCKET_ENV = "REPO_ARTIFACTS_BUCKET"
ARTIFACT_ENDPOINT_ENV = "REPO_ARTIFACTS_ENDPOINT_URL"
ARTIFACT_MOUNT_ENV = "REPO_ARTIFACTS_MOUNT_POINT"
DEFAULT_ARTIFACT_MOUNT = "/artifacts"
AZURE_ACCOUNT_KEY_ENV = "AZURE_STORAGE_ACCOUNT_KEY"
AZURE_SAS_TOKEN_ENV = "AZURE_STORAGE_SAS_TOKEN"
# The Blob Batch API behind delete_blobs accepts at most 256 subrequests per batch.
AZURE_DELETE_BATCH_SIZE = 256


class ArtifactStoreError(RuntimeError):
    """Raised when an artifact operation cannot be completed."""


def _artifact_uri() -> Optional[str]:
    return os.getenv(REPO_ARTIFACTS_BUCKET_ENV)


def _parse_bucket_uri(uri: str):
    """Parse a full bucket URI (s3://... or gs://...) into (scheme, bucket)."""
    parsed = urlparse(uri)
    return parsed.scheme, parsed.netloc


def _parse_azure_uri(uri: str):
    """Parse azure://<account>/<container>[/<path>] into (account, container).

    Azure needs its own parser because the container is in the URI path, not the netloc:
    urlparse("azure://acct/container/key") yields netloc="acct" and path="/container/key".
    Reusing the (scheme, netloc) pair from _parse_bucket_uri would address the storage
    account name as if it were a container.
    """
    parsed = urlparse(uri)
    account = parsed.netloc
    container = parsed.path.lstrip("/").split("/", 1)[0]
    if not account or not container:
        raise ArtifactStoreError(
            f"invalid Azure artifact URI {uri!r}; expected azure://<account>/<container>"
        )
    return account, container


def _s3_client(endpoint_url: Optional[str] = None):
    endpoint = endpoint_url or os.getenv(ARTIFACT_ENDPOINT_ENV)
    config = Config(signature_version=UNSIGNED) if endpoint else None
    return boto3.client(
        "s3",
        endpoint_url=endpoint or None,
        config=config,
    )


def _gcs_client():
    try:
        from google.cloud import storage as gcs
        endpoint = os.getenv(ARTIFACT_ENDPOINT_ENV)
        if endpoint:
            return gcs.Client(client_options={"api_endpoint": endpoint})
        return gcs.Client()
    except ImportError as e:
        raise ArtifactStoreError(
            "google-cloud-storage is required for GCS artifact support"
        ) from e


def _azure_container_client(account: str, container: str):
    """Build a container client for azure://<account>/<container>.

    Credential precedence matches the Java reader in AzureRepo.create: account key, then SAS
    token, then DefaultAzureCredential. An endpoint override is used verbatim as the account
    URL, like the s3 and gcs branches, so an emulator override must already include the
    account path segment (for example http://127.0.0.1:10000/devstoreaccount1).
    """
    try:
        from azure.storage.blob import BlobServiceClient
        # azure-core ships as a hard dependency of azure-storage-blob, so one check covers both.
        from azure.core.credentials import AzureSasCredential
    except ImportError as e:
        raise ArtifactStoreError(
            "azure-storage-blob is required for Azure Blob Storage artifact support"
        ) from e

    account_url = os.getenv(ARTIFACT_ENDPOINT_ENV) or f"https://{account}.blob.core.windows.net"

    account_key = os.getenv(AZURE_ACCOUNT_KEY_ENV)
    sas_token = os.getenv(AZURE_SAS_TOKEN_ENV)
    if account_key:
        # Name the account explicitly rather than passing the key as a bare string. Given a bare
        # string the SDK infers the account from the host, which it can only do for
        # *.blob.core.* or a literal localhost, and otherwise raises ValueError. That would make
        # an account key unusable against an emulator reached by service DNS, which is how the
        # chart already addresses the GCS emulator. This mirrors the Java reader's
        # StorageSharedKeyCredential(accountName, key).
        credential = {"account_name": account, "account_key": account_key}
    elif sas_token:
        # Wrapped rather than passed as a bare string, matching AzureRepo.create. A bare string
        # is only treated as a SAS token when every query parameter is one the SDK recognizes,
        # so an unrecognized parameter would silently reinterpret the token as an account key.
        credential = AzureSasCredential(sas_token)
    else:
        try:
            from azure.identity import DefaultAzureCredential
        except ImportError as e:
            raise ArtifactStoreError(
                "azure-identity is required for Azure Blob Storage artifact support "
                f"when neither {AZURE_ACCOUNT_KEY_ENV} nor {AZURE_SAS_TOKEN_ENV} is set"
            ) from e
        credential = DefaultAzureCredential()

    service = BlobServiceClient(account_url=account_url, credential=credential)
    return service.get_container_client(container)


def _mounted_artifact_path(key: str) -> Path:
    mount = Path(os.getenv(ARTIFACT_MOUNT_ENV, DEFAULT_ARTIFACT_MOUNT))
    return mount / key


def read_artifact_text(key: str) -> str:
    """Read an artifact by key, preferring the console's mounted artifact bucket."""
    mounted_path = _mounted_artifact_path(key)
    if mounted_path.exists():
        return mounted_path.read_text(encoding="utf-8", errors="replace")

    uri = _artifact_uri()
    if not uri:
        raise ArtifactStoreError(f"{REPO_ARTIFACTS_BUCKET_ENV} is not configured")

    scheme, bucket = _parse_bucket_uri(uri)

    if scheme == "s3":
        try:
            response = _s3_client().get_object(Bucket=bucket, Key=key)
            return response["Body"].read().decode("utf-8", errors="replace")
        except (BotoCoreError, ClientError) as e:
            raise ArtifactStoreError(f"could not read s3://{bucket}/{key}: {e}") from e

    elif scheme == "gs":
        try:
            blob = _gcs_client().bucket(bucket).blob(key)
            return blob.download_as_text(encoding="utf-8")
        except Exception as e:
            raise ArtifactStoreError(f"could not read gs://{bucket}/{key}: {e}") from e

    elif scheme == "azure":
        account, container = _parse_azure_uri(uri)
        try:
            # Client construction is inside the try so that a credential or endpoint problem
            # becomes an ArtifactStoreError like every other failure here, since callers catch
            # only that. The passthrough keeps the SDK-missing message from being rewrapped
            # into a "could not read" message that hides what to install.
            container_client = _azure_container_client(account, container)
            data = container_client.get_blob_client(key).download_blob().readall()
            return data.decode("utf-8", errors="replace")
        except ArtifactStoreError:
            raise
        except Exception as e:
            raise ArtifactStoreError(
                f"could not read azure://{account}/{container}/{key}: {e}"
            ) from e

    raise ArtifactStoreError(f"unsupported URI scheme: {uri}")


def artifact_uri(prefix_or_key: str) -> str:
    uri = _artifact_uri()
    return f"{uri}/{prefix_or_key}" if uri else prefix_or_key


def _list_artifacts_s3(bucket: str, prefix: str) -> List[Dict[str, object]]:
    try:
        s3 = _s3_client()
        paginator = s3.get_paginator("list_objects_v2")
        objects = []
        for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
            for item in page.get("Contents", []):
                objects.append({
                    "key": item["Key"],
                    "last_modified": item.get("LastModified"),
                    "size": item.get("Size", 0),
                })
        return objects
    except (BotoCoreError, ClientError) as e:
        raise ArtifactStoreError(f"could not list s3://{bucket}/{prefix}: {e}") from e


def _list_artifacts_gcs(bucket: str, prefix: str) -> List[Dict[str, object]]:
    try:
        blobs = _gcs_client().list_blobs(bucket, prefix=prefix)
        return [
            {
                "key": blob.name,
                "last_modified": blob.updated,
                "size": blob.size,
            }
            for blob in blobs
        ]
    except Exception as e:
        raise ArtifactStoreError(f"could not list gs://{bucket}/{prefix}: {e}") from e


def _list_artifacts_azure(account: str, container: str, prefix: str) -> List[Dict[str, object]]:
    try:
        # See read_artifact_text's azure branch for why construction is inside the try.
        container_client = _azure_container_client(account, container)
        return [
            {
                "key": blob.name,
                "last_modified": blob.last_modified,
                "size": blob.size,
            }
            for blob in container_client.list_blobs(name_starts_with=prefix)
        ]
    except ArtifactStoreError:
        raise
    except Exception as e:
        raise ArtifactStoreError(
            f"could not list azure://{account}/{container}/{prefix}: {e}"
        ) from e


def _list_artifacts_mounted(prefix: str) -> List[Dict[str, object]]:
    mounted_prefix = _mounted_artifact_path(prefix)
    if not mounted_prefix.exists():
        return []
    return [
        {
            "key": str(path.relative_to(_mounted_artifact_path(""))),
            "last_modified": path.stat().st_mtime,
            "size": path.stat().st_size,
        }
        for path in mounted_prefix.rglob("*")
        if path.is_file()
    ]


def list_artifacts(prefix: str) -> List[Dict[str, object]]:
    """List artifact objects under a prefix."""
    uri = _artifact_uri()
    if uri:
        scheme, bucket = _parse_bucket_uri(uri)
        if scheme == "s3":
            return _list_artifacts_s3(bucket, prefix)
        if scheme == "gs":
            return _list_artifacts_gcs(bucket, prefix)
        if scheme == "azure":
            account, container = _parse_azure_uri(uri)
            return _list_artifacts_azure(account, container, prefix)

    return _list_artifacts_mounted(prefix)


def _delete_artifact_prefix_s3(bucket: str, prefix: str) -> int:
    try:
        s3 = _s3_client()
        paginator = s3.get_paginator("list_objects_v2")
        deleted = 0
        for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
            objects = [{"Key": item["Key"]} for item in page.get("Contents", [])]
            for start in range(0, len(objects), 1000):
                chunk = objects[start:start + 1000]
                if chunk:
                    s3.delete_objects(Bucket=bucket, Delete={"Objects": chunk})
                    deleted += len(chunk)
        return deleted
    except (BotoCoreError, ClientError) as e:
        raise ArtifactStoreError(f"could not delete s3://{bucket}/{prefix}: {e}") from e


def _delete_artifact_prefix_gcs(bucket: str, prefix: str) -> int:
    try:
        client = _gcs_client()
        blobs = list(client.list_blobs(bucket, prefix=prefix))
        client.bucket(bucket).delete_blobs(blobs)
        return len(blobs)
    except Exception as e:
        raise ArtifactStoreError(f"could not delete gs://{bucket}/{prefix}: {e}") from e


def _delete_artifact_prefix_azure(account: str, container: str, prefix: str) -> int:
    try:
        # See read_artifact_text's azure branch for why construction is inside the try.
        container_client = _azure_container_client(account, container)
        names = [blob.name for blob in container_client.list_blobs(name_starts_with=prefix)]
        for start in range(0, len(names), AZURE_DELETE_BATCH_SIZE):
            chunk = names[start:start + AZURE_DELETE_BATCH_SIZE]
            container_client.delete_blobs(*chunk)
        return len(names)
    except ArtifactStoreError:
        raise
    except Exception as e:
        raise ArtifactStoreError(
            f"could not delete azure://{account}/{container}/{prefix}: {e}"
        ) from e


def delete_artifact_prefix(prefix: str) -> int:
    """Delete all objects under a prefix. Returns the number of deleted keys."""
    uri = _artifact_uri()
    if not uri:
        logger.debug("%s is not configured; skipping artifact cleanup for %s", REPO_ARTIFACTS_BUCKET_ENV, prefix)
        return 0

    scheme, bucket = _parse_bucket_uri(uri)
    if scheme == "s3":
        return _delete_artifact_prefix_s3(bucket, prefix)
    if scheme == "gs":
        return _delete_artifact_prefix_gcs(bucket, prefix)
    if scheme == "azure":
        account, container = _parse_azure_uri(uri)
        return _delete_artifact_prefix_azure(account, container, prefix)

    raise ArtifactStoreError(f"unsupported URI scheme: {uri}")
