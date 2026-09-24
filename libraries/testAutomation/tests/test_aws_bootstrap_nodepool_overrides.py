import shutil
import subprocess
from pathlib import Path

import pytest


REPO_ROOT = Path(__file__).resolve().parents[3]
CHART_DIR = REPO_ROOT / "deployment/k8s/charts/aggregates/migrationAssistantWithArgo"
VALUES_FILE = CHART_DIR / "values.yaml"
VALUES_EKS_FILE = CHART_DIR / "valuesEks.yaml"
NODEPOOL_TEST_FILE = REPO_ROOT / "deployment/k8s/aws/examples/nodepool-test.yaml"
BOOTSTRAP_SCRIPT = REPO_ROOT / "deployment/k8s/aws/aws-bootstrap.sh"


pytestmark = pytest.mark.skipif(
    shutil.which("helm") is None,
    reason="helm is required for chart rendering and schema validation tests",
)


def _run_helm(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["helm", *args],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )


class TestGeneralWorkNodePoolOverrides:
    def test_repo_override_renders_into_general_work_pool(self):
        completed = _run_helm(
            "template",
            "ma",
            str(CHART_DIR),
            "--show-only",
            "templates/resources/aws/workloadsNodePool.yaml",
            "-f",
            str(VALUES_FILE),
            "-f",
            str(VALUES_EKS_FILE),
            "-f",
            str(NODEPOOL_TEST_FILE),
            "--set",
            "stageName=dev",
            "--set",
            "aws.region=us-east-2",
            "--set",
            "aws.account=123456789012",
        )

        assert completed.returncode == 0, completed.stderr
        assert "name: general-work-pool" in completed.stdout
        assert "cpu: 68000m" in completed.stdout
        assert "memory: 136Gi" in completed.stdout
        assert 'values: ["spot"]' in completed.stdout
        assert 'values: ["m","r"]' in completed.stdout
        assert 'values: ["5"]' in completed.stdout
        assert 'values: ["amd64"]' in completed.stdout
        assert 'values: ["large","xlarge","2xlarge"]' in completed.stdout
        assert "consolidationPolicy: WhenEmptyOrUnderutilized" in completed.stdout
        assert "consolidateAfter: 35m" in completed.stdout

    @pytest.mark.parametrize(
        ("contents", "expected_fragment"),
        [
            (
                "workloadsNodePool:\n"
                "  disruption:\n"
                "    consolidationPolicy: WhenUnderutilized\n",
                "consolidationPolicy",
            ),
            (
                "workloadsNodePool:\n"
                "  architectures: [x86]\n",
                "architectures",
            ),
            (
                "workloadsNodePool:\n"
                "  capacityTypess: [spot]\n",
                "additional properties 'capacityTypess' not allowed",
            ),
            (
                "workloadsNodePool:\n"
                "  limits:\n"
                "    cpu: 68 cores\n",
                "limits/cpu",
            ),
            (
                "workloadsNodePool:\n"
                "  disruption:\n"
                "    consolidateAfter: later\n",
                "consolidateAfter",
            ),
        ],
    )
    def test_invalid_override_values_are_rejected_by_schema(
        self, tmp_path: Path, contents: str, expected_fragment: str
    ):
        invalid_values = tmp_path / "nodepool-invalid.yaml"
        invalid_values.write_text(contents)

        completed = _run_helm(
            "lint",
            str(CHART_DIR),
            "-f",
            str(VALUES_FILE),
            "-f",
            str(VALUES_EKS_FILE),
            "-f",
            str(invalid_values),
            "--set",
            "stageName=dev",
            "--set",
            "aws.region=us-east-2",
            "--set",
            "aws.account=123456789012",
            "--kube-version",
            "1.35.0",
        )

        assert completed.returncode != 0
        assert expected_fragment in completed.stdout + completed.stderr

    def test_bootstrap_runs_helm_preflight_before_cfn(self):
        script = BOOTSTRAP_SCRIPT.read_text()
        preflight_call = script.index("\npreflight_validate_helm_values\n")
        cfn_block = script.index("\n# --- CFN deployment (optional) ---\n")
        assert preflight_call < cfn_block
