/**
 * Solr externally-managed-snapshot IMPORT integration test (k8s-local).
 *
 * Runs TestSolr0070ExternalSnapshotImport, which exercises the import-prepare path:
 * an externally-managed Solr snapshot whose importConfig makes the workflow run
 * CreateSnapshot --mode import (uploading the live source's schema into the snapshot)
 * before metadata + backfill migration.
 *
 * This is a bring-your-own-snapshot test: the snapshot must already exist in S3, and the
 * live Solr source must be reachable so the import step can fetch the schema. Supply the
 * snapshot/repo/collection via the SOLR_IMPORT_* environment variables (see
 * test_cases/solr_import_tests.py for the contract).
 */
def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'solr-import-snapshot-test',
            sourceVersion: config.sourceVersion ?: 'SOLR_8.11',
            targetVersion: config.targetVersion ?: 'OS_2.19',
            testIds: 'Solr0070'
    )
}
