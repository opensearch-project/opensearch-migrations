"""Guards against AOSS-incompatible tests being instantiated for AOSS runs.

Pytest discovery uses substring matching on test_ids (e.g. '0041' matches
both Test0041CdcFullE2eAossTarget and Test0041CdcFullE2eMountableTransforms).
Tests authored for OS-only paths (allow_source_target_combinations=non-empty)
must skip cleanly when the run targets AOSS, rather than NPE downstream on
self.target_version (which is None for AOSS).
"""
import pytest

from integ_test.cluster_version import CDC_MIGRATION_COMBINATIONS
from integ_test.test_cases.ma_argo_test_base import (
    ClusterVersionCombinationUnsupported,
    MATestBase,
    MATestUserArguments,
)


class _OsOnlyTest(MATestBase):
    def __init__(self, user_args):
        super().__init__(
            user_args=user_args,
            description="os-only fixture",
            allow_source_target_combinations=CDC_MIGRATION_COMBINATIONS,
        )


class _AossFriendlyTest(MATestBase):
    def __init__(self, user_args):
        super().__init__(
            user_args=user_args,
            description="aoss-friendly fixture",
            allow_source_target_combinations=[],
        )


def _aoss_args():
    # target_version is None in AOSS runs (see conftest.pytest_generate_tests);
    # the runtime tolerates None even though the dataclass annotation says str.
    return MATestUserArguments(
        source_version="ES_7.10",
        target_version=None,  # type: ignore[arg-type]
        target_type="AOSS",
        unique_id="t",
        reuse_clusters=False,
    )


def _os_args():
    return MATestUserArguments(
        source_version="ES_7.10",
        target_version="OS_2.19",
        target_type="OS",
        unique_id="t",
        reuse_clusters=False,
    )


def test_os_only_test_skipped_for_aoss_run():
    """OS-only tests (non-empty combos) must skip when target_type='AOSS'."""
    with pytest.raises(ClusterVersionCombinationUnsupported):
        _OsOnlyTest(_aoss_args())


def test_aoss_friendly_test_instantiates_for_aoss_run():
    """AOSS-friendly tests (empty combos) must instantiate with target_version=None."""
    t = _AossFriendlyTest(_aoss_args())
    assert t.is_aoss is True
    assert t.target_version is None
    assert t.target_cluster is None


def test_os_only_test_still_works_for_os_run():
    """The new AOSS guard must not regress OS-only test selection on OS runs."""
    t = _OsOnlyTest(_os_args())
    assert t.is_aoss is False
    assert str(t.target_version) == "OS_2.19"
