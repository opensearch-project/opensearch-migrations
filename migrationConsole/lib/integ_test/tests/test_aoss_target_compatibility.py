"""Pin-down tests for AOSS target_type compatibility filtering.

When --target_type=AOSS is requested, MATestBase must reject tests that are
configured for typed (ES/OS) targets via a non-empty
allow_source_target_combinations. Without this, the default
import_existing_clusters() crashes at runtime on
`self.target_version.full_cluster_type` because target_version is None for
AOSS runs.
"""
import pytest

from integ_test.test_cases.ma_argo_test_base import (
    ClusterVersionCombinationUnsupported, MATestUserArguments,
)
# Aliased to keep pytest from collecting these classes (`Test*` prefix) as
# tests in this file. The alias must NOT start with `Test` either, even if
# leading-underscore'd.
from integ_test.test_cases import cdc_aoss_tests as _aoss_tests
from integ_test.test_cases import mountable_transform_tests as _mt_tests
from integ_test.test_cases import cdc_simple_bulk_e2e_tests as _bulk_tests


def _aoss_user_args():
    return MATestUserArguments(
        source_version="ES_7.10",
        target_version=None,
        target_type="AOSS",
        unique_id="unit-test",
        reuse_clusters=True,
    )


def _typed_user_args():
    return MATestUserArguments(
        source_version="ES_7.10",
        target_version="OS_1.3",
        target_type="OS",
        unique_id="unit-test",
        reuse_clusters=True,
    )


def test_aoss_target_drops_typed_only_test():
    # Test0042CdcFullE2eMountableTransforms has CDC_SOURCE_TARGET_COMBINATIONS,
    # so it is NOT AOSS-compatible and must be rejected at construction time
    # under target_type=AOSS.
    with pytest.raises(ClusterVersionCombinationUnsupported):
        _mt_tests.Test0042CdcFullE2eMountableTransforms(user_args=_aoss_user_args())


def test_aoss_target_drops_typed_only_simple_bulk():
    with pytest.raises(ClusterVersionCombinationUnsupported):
        _bulk_tests.Test0040CdcFullE2eSimpleBulk(user_args=_aoss_user_args())


def test_aoss_target_accepts_aoss_native_test():
    # AOSS-native tests have empty allow_source_target_combinations and must
    # construct successfully.
    _aoss_tests.Test0034CdcOnlyAossTarget(user_args=_aoss_user_args())
    _aoss_tests.Test0041CdcFullE2eAossTarget(user_args=_aoss_user_args())


def test_typed_target_keeps_typed_only_tests():
    # For non-AOSS runs, typed-only tests still need to instantiate.
    _bulk_tests.Test0040CdcFullE2eSimpleBulk(user_args=_typed_user_args())
    _mt_tests.Test0042CdcFullE2eMountableTransforms(user_args=_typed_user_args())
