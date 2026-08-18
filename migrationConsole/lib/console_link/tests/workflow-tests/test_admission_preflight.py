from console_link.workflow.services.admission_preflight import (
    AdmissionPreflightReport,
)


def test_package_report_is_adapted_for_web_and_console_rendering():
    report = AdmissionPreflightReport.from_payload({
        "formatVersion": 1,
        "allowed": False,
        "checkedResources": 2,
        "issues": [{
            "kind": "CapturedTraffic",
            "name": "p2-topic",
            "plural": "capturedtraffics",
            "classification": "recreate-required",
            "blocking": True,
            "message": "sourceLabel cannot be changed",
            "source": "projection-policy",
            "resourceId": "resource:capturedtraffics:p2-topic",
            "resetTargetId": "reset:capturedtraffics:p2-topic",
        }, {
            "kind": "TrafficReplay",
            "name": "replay",
            "plural": "trafficreplays",
            "classification": "approval-required",
            "blocking": False,
            "message": "Approval will be required",
            "source": "kubernetes",
        }],
    })

    assert report.checked_resources == 2
    assert report.allowed is False
    assert report.blocking_issues[0].reset_target_id == (
        "reset:capturedtraffics:p2-topic"
    )
    assert report.blocking_issues[0].resource_id == (
        "resource:capturedtraffics:p2-topic"
    )
    assert report.warning_issues[0].classification == "approval-required"


def test_package_allowed_flag_cannot_override_blocking_classification():
    report = AdmissionPreflightReport.from_payload({
        "allowed": True,
        "checkedResources": 1,
        "issues": [{
            "kind": "DataSnapshot",
            "name": "source-snapshot",
            "plural": "datasnapshots",
            "classification": "invalid",
            "message": "spec.s3Region is required",
            "source": "kubernetes",
        }],
    })

    assert report.allowed is False
