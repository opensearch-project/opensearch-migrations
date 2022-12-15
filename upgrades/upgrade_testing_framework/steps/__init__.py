# Bring all the individual steps into a single convenient namespace
from upgrade_testing_framework.steps.step_bootstrap_docker import BootstrapDocker
from upgrade_testing_framework.steps.step_create_source_snapshot import CreateSourceSnapshot
from upgrade_testing_framework.steps.step_load_test_config import LoadTestConfig
from upgrade_testing_framework.steps.step_snapshot_restore_setup import SnapshotRestoreSetup
from upgrade_testing_framework.steps.step_snapshot_restore_teardown import SnapshotRestoreTeardown
from upgrade_testing_framework.steps.step_start_source_cluster import StartSourceCluster
from upgrade_testing_framework.steps.step_start_target_cluster import StartTargetCluster
from upgrade_testing_framework.steps.step_stop_source_cluster import StopSourceCluster
from upgrade_testing_framework.steps.step_stop_target_cluster import StopTargetCluster
from upgrade_testing_framework.steps.step_test_source_cluster import TestSourceCluster
from upgrade_testing_framework.steps.step_test_target_cluster import TestTargetCluster
