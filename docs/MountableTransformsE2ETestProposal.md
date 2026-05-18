# Mountable Transforms E2E Proposal

## Goals

Add an automated EKS integration test that proves transform files can be packaged into OCI images, mounted into workflow pods, and used by every transform surface:

- Metadata migration adds a mapping field.
- Document backfill writes data into that field.
- Traffic replay request transform adds a replayed-request header.
- Traffic replay tuple transform adds different tuple-only headers.
- At least one program uses multiple transforms as an ordered list.

The same test should be runnable locally against kind with minimal argument changes.

## What The Current E2E Flow Looks Like

The outer runner is `libraries/testAutomation/testAutomation/test_runner.py`. Jenkins and local runs invoke it from `libraries/testAutomation` with `pipenv run app ...`.

That runner installs or reuses the MA deployment, waits for the `migration-console` pod, then execs pytest inside that pod:

```text
pipenv run pytest /root/lib/integ_test/integ_test/ma_workflow_test.py ...
```

The test cases live under `migrationConsole/lib/integ_test/integ_test/test_cases`. `MATestBase.prepare_workflow_snapshot_and_migration_config()` builds the per-snapshot metadata/backfill config, and `prepare_workflow_parameters()` passes it to the Argo workflow.

For EKS CDC tests, Jenkins uses the imported-cluster path:

- `vars/eksIntegPipeline.groovy`
- `vars/eksCdcIntegPipeline.groovy`

Those pass `--reuse-clusters --skip-delete --skip-install`, so pytest uses existing source/target configmaps and the `full-migration-imported-clusters` / `cdc-full-e2e-imported-clusters` workflow templates.

The `migration-console` image has kubectl, helm, stern, node, jq, Python, and the config processor. It does not include Docker, crane, buildah, or kaniko. In-pod image building requires adding more tooling and registry push permissions.

## Recommendation

Build the transform images in the outer test runner before pytest starts, then pass digest-pinned image refs into pytest as arguments.

This keeps image creation outside the `migration-console` pod, where Jenkins/local runs already have the relevant kube context, AWS credentials, Docker/crane availability, and registry access. It also keeps pytest focused on workflow behavior instead of registry plumbing.

The runner should receive already-built transform image refs:

```text
pipenv run app \
  --source-version=ES_7.10 \
  --target-version=OS_2.19 \
  --test-ids=0041 \
  --reuse-clusters \
  --skip-delete \
  --skip-install \
  --kube-context="$EKS_CONTEXT" \
  --transform-image-basic="$TRANSFORM_IMAGE_BASIC" \
  --transform-image-sequence="$TRANSFORM_IMAGE_SEQUENCE"
```

For local kind:

```text
pipenv run app \
  --source-version=ES_7.10 \
  --target-version=OS_2.19 \
  --test-ids=0041 \
  --reuse-clusters \
  --skip-delete \
  --skip-install \
  --kube-context=kind-ma \
  --transform-image-basic=localhost:5002/migrations/mountable-transforms@sha256:... \
  --transform-image-sequence=localhost:5002/migrations/mountable-transforms@sha256:...
```

The exact local context name can vary, but the image repo should point at the registry that kind nodes can pull from. The existing kind image-volume smoke test already uses `localhost:5002` with `kindest/node:v1.35.1`.

## Jenkins Transform Image Build Step

Build the transform fixture images in Jenkins after `bootstrapMA(...)` has populated `env.registryEndpoint` and before `pipenv run app ...` starts pytest. In `vars/eksCdcIntegPipeline.groovy`, the clearest location is a separate stage between `Post-Cluster Setup` and `Perform CDC E2E Tests`.

That placement gives the step:

- The ECR repository exported as `env.registryEndpoint`.
- AWS credentials from `withMigrationsTestAccount`.
- The checked-out repo fixture files.
- A normal Jenkins host shell, before execution moves into the `migration-console` pod.

For the transform E2E job, make this stage unconditional so every run builds or refreshes the fixture images it will test.

Example stage shape:

```groovy
stage('Build Transform Test Images') {
    steps {
        timeout(time: 15, unit: 'MINUTES') {
            script {
                withMigrationsTestAccount(region: params.REGION, duration: 1200) { accountId ->
                    def basicDir = 'migrationConsole/lib/integ_test/integ_test/transform_assets/mountable/basic'
                    def sequenceDir = 'migrationConsole/lib/integ_test/integ_test/transform_assets/mountable/sequence'

                    def hashFor = { path ->
                        sh(
                            script: "find '${path}' -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1",
                            returnStdout: true
                        ).trim().take(16)
                    }

                    def ecrHost = env.registryEndpoint.split('/')[0]
                    sh """
                      set -euo pipefail
                      aws ecr get-login-password --region '${params.REGION}' |
                        docker login --username AWS --password-stdin '${ecrHost}'
                    """

                    def buildTransformImage = { path, name ->
                        def tag = "mountable_transforms_${name}_${hashFor(path)}"
                        def output = sh(
                            script: "deployment/k8s/package-transforms.sh '${path}' '${env.registryEndpoint}' '${tag}'",
                            returnStdout: true
                        )
                        def matcher = output =~ /${java.util.regex.Pattern.quote(env.registryEndpoint)}@sha256:[a-f0-9]{64}/
                        if (!matcher.find()) {
                            error("Could not find digest-pinned transform image ref in package-transforms.sh output")
                        }
                        return matcher.group(0)
                    }

                    env.TRANSFORM_IMAGE_BASIC = buildTransformImage(basicDir, 'basic')
                    env.TRANSFORM_IMAGE_SEQUENCE = buildTransformImage(sequenceDir, 'sequence')
                }
            }
        }
    }
}
```

Then pass those digest-pinned refs into the Python runner:

```groovy
sh "pipenv run app --source-version=$sourceVer --target-version=$targetVer " +
   "--test-ids='${params.TEST_IDS}' " +
   "--transform-image-basic='${env.TRANSFORM_IMAGE_BASIC}' " +
   "--transform-image-sequence='${env.TRANSFORM_IMAGE_SEQUENCE}' " +
   "--speedup-factor=${params.SPEEDUP_FACTOR} " +
   "--reuse-clusters --skip-delete --skip-install --kube-context=${env.eksKubeContext}"
```

This assumes the Jenkins agent has Docker CLI and daemon access. The EKS CDC job should fail fast if Docker is unavailable; do not add a crane fallback or move image building into the cluster.

### Tag And Cleanup Behavior

Tags are controlled by the Jenkins step. Use a content-hash tag, as shown above, if the desired behavior is "same fixture files, same tag." That keeps the number of tagged ECR images bounded by actual fixture changes.

The digest-pinned ref passed to the test is the source of truth. With `package-transforms.sh`, identical files usually produce the same digest for this `FROM scratch` image, but the Docker build path can include file metadata, so the test should not depend on digest stability. If Docker produces a different digest for the same content-hash tag, ECR may retain an old untagged manifest. These images are tiny, and slow-changing fixtures make cleanup low priority; an ECR lifecycle rule for untagged images can handle the residuals.

## Test Fixture Images

Create two JavaScript-only fixture image directories in the repo, for example:

```text
migrationConsole/lib/integ_test/integ_test/transform_assets/mountable/basic/
  metadata.js
  document.js
  request.js
  tuple.js

migrationConsole/lib/integ_test/integ_test/transform_assets/mountable/sequence/
  metadata.js
  document-1.js
  document-2.js
  request.js
  tuple-1.js
  tuple-2.js
```

These are two separate banks of transform files, and each bank is packaged into a separate image:

- `basic/` becomes `TRANSFORM_IMAGE_BASIC` and is referenced by `transform-basic`.
- `sequence/` becomes `TRANSFORM_IMAGE_SEQUENCE` and is referenced by `transform-sequence`.

The `sequence/` image lets one application run two transforms from the same mounted source. That matters because one process config has one `transformsSource`; multiple transforms for that process must be files in that selected source.

The test config can use both images:

```yaml
transformsSources:
  transform-basic:
    image: "<basic digest ref>"
  transform-sequence:
    image: "<sequence digest ref>"

snapshotMigrationConfigs:
  - fromSource: source1
    toTarget: target1
    perSnapshotConfig:
      testsnapshot:
        - metadataMigrationConfig:
            transformsSource: transform-basic
            metadataTransforms:
              language: javascript
              file: metadata.js
              bindingsObject:
                fieldName: mountable_transform_marker
                fieldType: keyword
          documentBackfillConfig:
            transformsSource: transform-sequence
            documentTransforms:
              - language: javascript
                file: document-1.js
                bindingsObject:
                  fieldName: mountable_transform_marker
                  fieldValue: backfilled
              - language: javascript
                file: document-2.js
                bindingsObject:
                  fieldName: mountable_transform_order
                  fieldValue: second

traffic:
  replayers:
    replay1:
      replayerConfig:
        transformsSource: transform-sequence
        requestTransforms:
          language: javascript
          file: request.js
          bindingsObject:
            headerName: x-mountable-request-transform
            headerValue: replay
        tupleTransforms:
          - language: javascript
            file: tuple-1.js
            bindingsObject:
              headerName: x-mountable-tuple-transform-1
              headerValue: tuple-one
          - language: javascript
            file: tuple-2.js
            bindingsObject:
              headerName: x-mountable-tuple-transform-2
              headerValue: tuple-two
        tupleMaxBufferSeconds: 5
```

## Proposed Test Case

Add a CDC full E2E test, probably `Test0041MountableTransformsImageFullE2E`, in `mountable_transform_tests.py`, and include it in the default CDC EKS run.

Use `cdc-full-e2e-imported-clusters`, matching the EKS CDC path. The test should:

1. Seed a source index with an explicit mapping before the workflow starts.
2. Create one or more source documents directly so metadata and backfill cover them.
3. Start the CDC full workflow.
4. Wait for the capture proxy and replayer.
5. Send a small request through the proxy so replay and tuple transforms run.
6. Verify target mapping contains `mountable_transform_marker`.
7. Verify backfilled target docs contain `mountable_transform_marker=backfilled` and `mountable_transform_order=second`.
8. Configure tuple output to rotate quickly, for example `tupleMaxBufferSeconds: 5`, then wait long enough for the S3 object to appear.
9. Read tuple gzip JSONL files from the migration-console S3 artifact mount and verify:
   - `targetRequest` contains `x-mountable-request-transform: replay`.
   - `targetRequest` or a tuple-local marker contains `x-mountable-tuple-transform-1: tuple-one`.
   - `targetRequest` or a tuple-local marker contains `x-mountable-tuple-transform-2: tuple-two`.

The replayer writes tuple files to S3, and the migration-console mounts that artifact bucket read-only at `/s3/artifacts`. With the default tuple S3 prefix, the test searches:

```python
tuple_glob = "/s3/artifacts/tuples/**/tuples-*.log.gz"
tuple_files = glob.glob(tuple_glob, recursive=True)
```

The S3 key shape is `tuples/<replayer-id>/<yyyy>/<MM>/<dd>/<HH>/tuples-<sink>-<timestamp>-<seq>.log.gz`, so the recursive glob avoids assuming the replayer pod name or hour shard. The test filters files by object/file mtime after the test start, opens them with `gzip.open(path, "rt")`, parses each JSON line, and asserts the transformed headers are present in at least one tuple.

Tuple validation is the final workflow assertion. It checks the S3 tuple output after replay has run and after the tuple object has rotated. The implementation should confirm that `targetRequest` in tuple output represents the transformed target request. If tuple output does not expose the request-transform header, the test still verifies tuple transforms from the tuple file and verifies the request transform through another observable path, such as a test target/shim or a replayer debug log.

## Workflow Template Changes

The workflow templates synthesize the final migration JSON with jq. They need a way to include top-level `transformsSources`.

Add a workflow parameter, defaulting to `{}`:

```yaml
- name: transforms-sources
  value: "{}"
```

Then include it in generated migration config:

```sh
MIGRATION_CONFIG=$(jq -n \
  --argjson sources "$SOURCE_CLUSTERS" \
  --argjson target "$TARGET_TRANSFORMED" \
  --argjson snapshotConfigs "$SNAPSHOT_CONFIGS" \
  --argjson transformsSources "{{inputs.parameters.transforms-sources}}" \
  '{
    skipApprovals: true,
    sourceClusters: $sources,
    targetClusters: {target1: $target},
    transformsSources: $transformsSources,
    snapshotMigrationConfigs: [...]
  }')
```

Apply that to:

- `migrationConsole/lib/integ_test/testWorkflows/fullMigrationImportedClusters.yaml`
- `migrationConsole/lib/integ_test/testWorkflows/fullMigrationWithClusters.yaml`

For `cdcFullE2eImportedClusters.yaml`, pass the parameter through to `full-migration-imported-clusters.generate-migration-configs`, and include the replayer transform config in the `add-traffic-config` jq overlay for this test. A clean implementation is to pass a `replayer-config-overrides` JSON parameter defaulting to `{}` and merge it into `replayerConfig`.

## Python Plumbing

Add pytest arguments in `conftest.py`:

```text
--transform_image_basic
--transform_image_sequence
```

Carry those through `MATestUserArguments`, then make them available to the mountable-transform test case.

In `MATestBase.prepare_workflow_parameters()`, add `transforms-sources` when a test has transform sources:

```python
self.parameters["transforms-sources"] = {
    "transform-basic": {"image": self.transform_image_basic},
    "transform-sequence": {"image": self.transform_image_sequence},
}
```

The mountable-transform test overrides `prepare_workflow_snapshot_and_migration_config()` to add `metadataTransforms` and `documentTransforms`, and overrides `prepare_workflow_parameters()` to select `cdc-full-e2e-imported-clusters` and pass replayer transform overrides.

## Jenkins Changes

In `vars/eksCdcIntegPipeline.groovy`:

- Add the unconditional `Build Transform Test Images` stage after `Post-Cluster Setup`.
- Store the returned digest refs in `env.TRANSFORM_IMAGE_BASIC` and `env.TRANSFORM_IMAGE_SEQUENCE`.
- Update `Perform CDC E2E Tests` to pass those refs to `pipenv run app`.

Do not pass an image repository or builder mode into pytest. The Jenkins job owns image creation; the Python test only receives immutable image refs and injects them into `transformsSources`.

## Local Kind Notes

The kind cluster must support native image volumes. Use the same Kubernetes version family as the image-volume smoke test, such as `kindest/node:v1.35.1`.

For local runs, use a registry reachable from both the host push command and the kind nodes. The existing kind setup with `localhost:5002` is the best fit.

The local run should use the same imported-cluster route as EKS for minimal changes. If we want this to work without pre-created source/target configmaps later, we should add a non-imported CDC full workflow path; that is separate from the transform-image coverage.

## Decisions

- Include this test in the default CDC EKS run.
- Require Docker on the Jenkins agent and use `deployment/k8s/package-transforms.sh`; no crane fallback.
- Do not add a separate cheaper metadata/backfill-only transform test. The CDC E2E test is the canonical coverage for this interface.
