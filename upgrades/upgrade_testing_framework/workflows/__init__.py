import upgrade_testing_framework.steps as steps

# Each of these lists represents a user-workflow composed of a number of steps to be executed in series.  Once these
# get more numerous and/or complex, we can explore moving them to their own files.
SNAPSHOT_RESTORE_STEPS = [
    steps.LoadTestConfig,
    steps.BootstrapDocker,
    steps.SnapshotRestoreSetup,
    steps.SelectExpectations,
    steps.StartSourceCluster,
    steps.TestSourceCluster,
    steps.PerformPreUpgradeTest,
    steps.CreateSourceSnapshot,
    steps.StartTargetCluster,
    steps.TestTargetCluster,
    steps.RestoreSourceSnapshot,
    steps.PerformPostUpgradeTest,
    steps.StopSourceCluster,
    steps.StopTargetCluster,
    steps.SnapshotRestoreTeardown
]