from datetime import datetime, timezone
from io import BytesIO

import pytest

from console_link.workflow.commands import artifact_store
from console_link.workflow.application.outputs import (
    OutputReadFailed,
    OutputService,
    OutputStale,
    OutputUnavailable,
)


def _resource(outputs=None):
    return {
        "metadata": {
            "name": "migration-0",
            "uid": "resource-uid",
        },
        "status": {
            "outputs": outputs or {},
        },
    }


def _ref(output_name, timestamp):
    return {
        "s3Key": (
            "migration-outputs/snapshotmigration/migration-0/resource-uid/"
            f"{output_name}/migration.log"
        ),
        "workflowName": "migration",
        "workflowCreationTimestamp": timestamp,
    }


def test_descriptors_are_resource_owned_and_stage_ordered():
    resources = {
        ("snapshotmigrations", "migration-0"): _resource({
            "metadataMigrate": _ref(
                "metadataMigrate",
                "2026-08-13T12:05:00Z",
            ),
            "metadataEvaluate": _ref(
                "metadataEvaluate",
                "2026-08-13T12:00:00Z",
            ),
        }),
    }
    service = OutputService(
        namespace="ma",
        resource_loader=lambda plural, name: resources[(plural, name)],
        artifact_reader=lambda _key: "{}",
        artifact_source=lambda key: f"s3://outputs/{key}",
        clock=lambda: datetime(2026, 8, 13, tzinfo=timezone.utc),
    )

    result = service.list_outputs(
        "output:snapshotmigrations:migration-0:metadataMigrate",
    )

    assert result.resource_id == "resource:snapshotmigrations:migration-0"
    assert [item.output_name for item in result.outputs] == [
        "metadataEvaluate",
        "metadataMigrate",
    ]
    assert [item.stage for item in result.outputs] == ["Evaluate", "Migrate"]
    assert result.outputs[0].attempt == "migration"
    assert result.outputs[0].timestamp == "2026-08-13T12:00:00Z"
    assert result.outputs[0].source.startswith("s3://outputs/")
    assert "s3Key" not in result.outputs[0].to_dict()


def test_output_content_rejects_stale_reference_and_bounds_inline_reads():
    current = _resource({
        "metadataEvaluate": _ref(
            "metadataEvaluate",
            "2026-08-13T12:00:00Z",
        ),
    })
    content = "x" * 12
    service = OutputService(
        namespace="ma",
        resource_loader=lambda _plural, _name: current,
        artifact_reader=lambda _key: content,
        inline_limit=8,
    )
    descriptor = service.list_outputs(
        "output:snapshotmigrations:migration-0:metadataEvaluate",
    ).outputs[0]

    bounded = service.read_output(descriptor.id)

    assert bounded.content is None
    assert bounded.inline is False
    assert bounded.size == len(content.encode("utf-8"))
    assert "Download" in bounded.message

    current["status"]["outputs"]["metadataEvaluate"] = {
        **current["status"]["outputs"]["metadataEvaluate"],
        "s3Key": "replacement.log",
    }
    with pytest.raises(OutputStale):
        service.read_output(descriptor.id)


def test_missing_and_failed_outputs_are_distinct():
    resource = _resource()
    service = OutputService(
        namespace="ma",
        resource_loader=lambda _plural, _name: resource,
        artifact_reader=lambda _key: (_ for _ in ()).throw(
            RuntimeError("artifact store unavailable")
        ),
    )

    with pytest.raises(OutputUnavailable):
        service.list_outputs(
            "output:snapshotmigrations:migration-0:metadataEvaluate",
        )

    resource["status"]["outputs"]["metadataEvaluate"] = _ref(
        "metadataEvaluate",
        "2026-08-13T12:00:00Z",
    )
    descriptor = service.list_outputs(
        "output:snapshotmigrations:migration-0:metadataEvaluate",
    ).outputs[0]
    with pytest.raises(OutputReadFailed, match="artifact store unavailable"):
        service.read_output(descriptor.id)


def _service_using_configured_store(monkeypatch, tmp_path, bucket_uri):
    key = _ref("metadataEvaluate", "2026-08-13T12:00:00Z")["s3Key"]
    monkeypatch.setenv("REPO_ARTIFACTS_MOUNT_POINT", str(tmp_path / "mount"))
    monkeypatch.setenv("REPO_ARTIFACTS_BUCKET", bucket_uri)
    service = OutputService(
        namespace="ma",
        resource_loader=lambda _plural, _name: _resource({
            "metadataEvaluate": _ref(
                "metadataEvaluate",
                "2026-08-13T12:00:00Z",
            ),
        }),
    )
    descriptor = service.list_outputs(
        "output:snapshotmigrations:migration-0:metadataEvaluate",
    ).outputs[0]
    return service, descriptor, key


def test_reads_output_from_mounted_artifact_store(monkeypatch, tmp_path):
    service, descriptor, key = _service_using_configured_store(
        monkeypatch,
        tmp_path,
        "s3://unused-bucket",
    )
    artifact = tmp_path / "mount" / key
    artifact.parent.mkdir(parents=True)
    artifact.write_text("mounted output", encoding="utf-8")

    assert service.read_output(descriptor.id).content == "mounted output"


def test_reads_output_from_s3_artifact_store(monkeypatch, tmp_path):
    service, descriptor, key = _service_using_configured_store(
        monkeypatch,
        tmp_path,
        "s3://output-bucket",
    )

    class S3Client:
        def get_object(self, *, Bucket, Key):
            assert (Bucket, Key) == ("output-bucket", key)
            return {"Body": BytesIO(b"s3 output")}

    monkeypatch.setattr(artifact_store, "_s3_client", lambda: S3Client())

    assert service.read_output(descriptor.id).content == "s3 output"


def test_reads_output_from_gcs_artifact_store(monkeypatch, tmp_path):
    service, descriptor, key = _service_using_configured_store(
        monkeypatch,
        tmp_path,
        "gs://output-bucket",
    )

    class Blob:
        def download_as_text(self, *, encoding):
            assert encoding == "utf-8"
            return "gcs output"

    class Bucket:
        def blob(self, name):
            assert name == key
            return Blob()

    class GcsClient:
        def bucket(self, name):
            assert name == "output-bucket"
            return Bucket()

    monkeypatch.setattr(artifact_store, "_gcs_client", lambda: GcsClient())

    assert service.read_output(descriptor.id).content == "gcs output"
