import json

from kubernetes.client.rest import ApiException

from console_link.workflow.services.admission_preflight import (
    AdmissionPreflightService,
)


def _resource(
    *,
    kind="CapturedTraffic",
    name="p2-topic",
    parameters=None,
    policies=None,
):
    return {
        "apiVersion": "migrations.opensearch.org/v1alpha1",
        "kind": kind,
        "name": name,
        "parameters": parameters or {
            "sourceLabel": "next-source",
            "partitions": 3,
        },
        "parameterPolicies": policies or [],
    }


def _api_error(status, message):
    return ApiException(
        status=status,
        reason="Unprocessable Entity" if status == 422 else "Service Unavailable",
        http_resp=type("Response", (), {
            "status": status,
            "reason": "request failed",
            "data": json.dumps({
                "kind": "Status",
                "message": message,
                "reason": "Invalid" if status == 422 else "ServiceUnavailable",
                "code": status,
            }),
            "getheaders": lambda self: {},
        })(),
    )


class _CustomApi:
    def __init__(self, existing=None, error=None):
        self.existing = existing
        self.error = error
        self.replaced = []
        self.created = []

    def get_namespaced_custom_object(self, **_request):
        if self.existing is None:
            raise _api_error(404, "not found")
        return self.existing

    def replace_namespaced_custom_object(self, **request):
        self.replaced.append(request)
        if self.error:
            raise self.error
        return request["body"]

    def create_namespaced_custom_object(self, **request):
        self.created.append(request)
        if self.error:
            raise self.error
        return request["body"]


def _existing(spec=None, phase="Ready"):
    return {
        "apiVersion": "migrations.opensearch.org/v1alpha1",
        "kind": "CapturedTraffic",
        "metadata": {
            "name": "p2-topic",
            "namespace": "ma",
            "resourceVersion": "17",
            "labels": {
                "migrations.opensearch.org/run-number": "old-run",
            },
            "annotations": {
                "migrations.opensearch.org/approved-during-run": "old-run",
            },
        },
        "spec": spec or {
            "sourceLabel": "old-source",
            "partitions": 3,
        },
        "status": {"phase": phase},
    }


def test_preflight_marks_explicit_impossible_vap_denial_as_reset_required():
    api = _CustomApi(
        existing=_existing(),
        error=_api_error(
            422,
            'The capturedtraffics "p2-topic" is invalid: '
            "ValidatingAdmissionPolicy denied request: Impossible: "
            "sourceLabel cannot be changed. Delete and recreate.",
        ),
    )

    report = AdmissionPreflightService("ma", custom_api=api).check(
        {"resources": [_resource()]},
        workflow_name="migration",
        run_number="new-run",
    )

    assert report.allowed is False
    assert len(report.issues) == 1
    issue = report.issues[0]
    assert issue.classification == "recreate-required"
    assert issue.blocking is True
    assert issue.reset_target_id == "reset:capturedtraffics:p2-topic"
    assert api.replaced[0]["dry_run"] == "All"
    assert api.replaced[0]["body"]["spec"]["sourceLabel"] == "next-source"
    assert (
        api.replaced[0]["body"]["metadata"]["labels"][
            "migrations.opensearch.org/run-number"
        ]
        == "new-run"
    )


def test_preflight_does_not_block_gated_or_eventually_possible_denials():
    messages = [
        (
            "approval-required",
            "Gated changes detected on CapturedTraffic fields: partitions. "
            "Approve the corresponding ApprovalGate to proceed.",
        ),
        (
            "warning",
            "This resource is being deleted. Only Kubernetes deletion "
            "bookkeeping updates are permitted during teardown.",
        ),
        (
            "warning",
            "ValidatingAdmissionPolicy denied request: the resource must "
            "reach Ready before this update can proceed.",
        ),
    ]

    for expected_classification, message in messages:
        api = _CustomApi(
            existing=_existing(),
            error=_api_error(422, message),
        )
        report = AdmissionPreflightService("ma", custom_api=api).check(
            {"resources": [_resource()]},
            workflow_name="migration",
            run_number="new-run",
        )

        assert report.allowed is True
        assert report.issues[0].classification == expected_classification
        assert report.issues[0].blocking is False


def test_preflight_unavailability_warns_without_blocking_submission():
    api = _CustomApi(
        existing=_existing(),
        error=_api_error(503, "admission webhook is temporarily unavailable"),
    )

    report = AdmissionPreflightService("ma", custom_api=api).check(
        {"resources": [_resource()]},
        workflow_name="migration",
        run_number="new-run",
    )

    assert report.allowed is True
    assert report.issues[0].classification == "warning"
    assert "temporarily unavailable" in report.issues[0].message


def test_preflight_blocks_crd_schema_errors_that_cannot_converge_later():
    api = _CustomApi(
        existing=_existing(),
        error=_api_error(
            422,
            'CapturedTraffic "p2-topic" is invalid: spec.partitions: '
            "Invalid value: 0: must be greater than or equal to 1",
        ),
    )

    report = AdmissionPreflightService("ma", custom_api=api).check(
        {"resources": [_resource(parameters={"partitions": 0})]},
        workflow_name="migration",
        run_number="new-run",
    )

    assert report.allowed is False
    assert report.issues[0].classification == "invalid"
    assert report.issues[0].reset_target_id is None


def test_preflight_uses_projection_policy_when_live_admission_is_unavailable():
    api = _CustomApi(
        existing=_existing(),
        error=_api_error(503, "admission chain unavailable"),
    )
    pending = _resource(
        policies=[{
            "specPath": ["sourceLabel"],
            "changeRestriction": "impossible",
        }],
    )

    report = AdmissionPreflightService("ma", custom_api=api).check(
        {"resources": [pending]},
        workflow_name="migration",
        run_number="new-run",
    )

    assert report.allowed is False
    assert report.issues[0].classification == "recreate-required"
    assert report.issues[0].source == "projection-policy"


def test_preflight_dry_run_creates_resources_that_do_not_exist():
    api = _CustomApi(existing=None)

    report = AdmissionPreflightService("ma", custom_api=api).check(
        {"resources": [_resource()]},
        workflow_name="migration",
        run_number="new-run",
    )

    assert report.allowed is True
    assert report.issues == ()
    assert api.created[0]["dry_run"] == "All"
    assert api.created[0]["body"]["metadata"]["name"] == "p2-topic"
