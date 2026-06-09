# release/bundle/ — assistant bundle source tree
#
# Everything in this directory ships in migration-assistant-bundle.tar.gz
# and lands in ~/.opensearch-migration-assistant/ at install time.
#
# Subdirectories (populated by the release pipeline; this README is the
# spec of what each is expected to contain):
#
#   helm/      Helm charts for migration-assistant deployment
#              (one chart per workflow flavor, e.g. full-migration,
#              traffic-replay, sourceless-es).
#
#   cfn/       CloudFormation templates for the AWS-managed paths
#              (EKS Auto Mode + IRSA + S3 snapshot bucket + IAM roles).
#
#   values/    Sample values.yaml files for each helm chart, one per
#              source-engine version (es-6.x, es-7.x, es-8.x, solr-8.x,
#              solr-9.x, sourceless).
#
#   skills/    Agent skills bundle. Each subdirectory is one skill in
#              the format the migration-tui expects (SKILL.md +
#              optional references/templates/scripts/assets).
#
#   samples/   Sample workflow YAMLs the TUI's ConfigStore reads via
#              ConfigStore::list_samples(). One YAML per file. Filename
#              minus .wf.yaml becomes the sample name.
#
#   manifest.yaml
#              Top-level manifest describing what's in this bundle.
#              Schema version is 1. Format:
#
#                  schema_version: 1
#                  bundle_version: 0.4.2
#                  built_at: "2026-06-09T18:42:00Z"
#                  contents:
#                    helm:
#                      - name: full-migration
#                        path: helm/full-migration
#                        chart_version: 0.4.2
#                    cfn:
#                      - name: eks-automode
#                        path: cfn/eks-automode.yaml
#                    values:
#                      - name: es-7.10
#                        path: values/es-7.10.yaml
#                    skills:
#                      - name: opensearch-migrations-eks-deploy-ops
#                        path: skills/opensearch-migrations-eks-deploy-ops
#                    samples:
#                      - name: full-migration-imported-clusters
#                        path: samples/full-migration-imported-clusters.wf.yaml
#
#              The TUI reads manifest.yaml at startup to discover what's
#              available without globbing the filesystem. If a future
#              bundle adds a new artifact category, it adds a new key
#              here and the TUI ignores categories it doesn't recognize.
#
# Layout discipline:
#   * Filenames inside this tree must be stable. Renaming an artifact
#     breaks bundle consumers that have referenced it from a CI script
#     or runbook.
#   * Binary blobs do not belong here. Put them in the GitHub Release
#     attachments and reference them from the manifest as URLs.
