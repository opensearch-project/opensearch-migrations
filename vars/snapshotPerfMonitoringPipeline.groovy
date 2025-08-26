// Concrete implementation of the snapshot-based migration pipeline with performance monitoring
// This pipeline is ready to use and provides all the functionality described in the plan

def call(Map config = [:]) {
    snapshotBasedMigrationPipeline([
        defaultStageId: 'external-snapshot',
        jobName: 'snapshot-performance-monitoring-pipeline',
        workerAgent: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host'
    ])
}
