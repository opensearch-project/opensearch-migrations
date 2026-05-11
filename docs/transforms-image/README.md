# Mountable Transforms — OCI Artifact Flow

This directory contains a worked example for packaging custom transformer
JARs and configuration as an **OCI artifact image**, which the
opensearch-migrations orchestration mounts read-only into the replayer,
reindex-from-snapshot (RFS), and metadata-migration pods.

The OCI artifact flow is the only delivery mechanism the orchestration
currently supports for shipping transforms into a running migration. It
requires Kubernetes **1.35+** (image volume sources are GA at 1.35).

> Looking for the alternative ConfigMap-based flow described in the original
> design doc (PR #2829)? It is intentionally **not** part of this slice — the
> OCI path is the canonical mechanism. The ConfigMap path may be added later
> for clusters on older Kubernetes versions.

---

## How it fits together

```
                       ┌──────────────────────────────────────────┐
                       │  user authoring a migration spec         │
                       └──────────────────────────────────────────┘
                                          │
                                          ▼
   transformsSources:                # top-level map of named entries
     myTransforms:
       image: my-registry/my-transforms:0.1.0
       imagePullPolicy: IfNotPresent
       entrypoint: /transforms/replayer-config.yaml

   traffic:
     replayers:
       primary:
         replayerConfig:
           transformsSource: myTransforms     # by-name reference
                                          │
                                          ▼
                       ┌──────────────────────────────────────────┐
                       │  orchestration template (replayer / RFS /│
                       │  metadata-migration)                     │
                       │                                          │
                       │  • adds an `image:` volume entry         │
                       │  • mounts it read-only at /transforms    │
                       │  • sets TRANSFORMS_ENTRYPOINT=<path>     │
                       └──────────────────────────────────────────┘
```

If a tool has no `transformsSource` configured, the orchestration substitutes
a tiny sentinel image (`registry.k8s.io/pause:3.10`) and an empty
`TRANSFORMS_ENTRYPOINT`. The volume is always present so the pod spec is
structurally identical whether or not transforms are configured; tools key off
of `TRANSFORMS_ENTRYPOINT` being non-empty to decide whether to read from
`/transforms`.

---

## Building the example image

```bash
mkdir -p transforms/lib
cp /path/to/my-replayer-config.yaml transforms/replayer-config.yaml
cp /path/to/my-transformer.jar      transforms/lib/

docker build \
  -f docs/transforms-image/Dockerfile.transforms-example \
  -t my-registry/my-transforms:0.1.0 \
  .

docker push my-registry/my-transforms:0.1.0
```

The Dockerfile uses `FROM scratch` because OCI image volumes never execute the
image — they only expose its filesystem. There is no need for an entrypoint,
shell, or base OS.

---

## Referencing the image from a migration spec

```yaml
transformsSources:
  myTransforms:
    image: my-registry/my-transforms:0.1.0
    imagePullPolicy: IfNotPresent
    entrypoint: /transforms/replayer-config.yaml

traffic:
  replayers:
    primary:
      replayerConfig:
        transformsSource: myTransforms

snapshotMigrationConfigs:
  - sourceLabel: src
    perSnapshotConfig:
      mySnapshot:
        - metadataMigrationConfig:
            transformsSource: myTransforms
          documentBackfillConfig:
            transformsSource: myTransforms
```

The validator rejects a `transformsSource` reference whose name is not present
in the top-level `transformsSources` map, with a message listing the available
names.

---

## Inside the pod

After the orchestration applies the workflow, each consumer pod sees:

| Path / Var | Value |
|---|---|
| `/transforms/...` | Filesystem of the OCI artifact (read-only) |
| `$TRANSFORMS_ENTRYPOINT` | Absolute path to the top-level config file (e.g. `/transforms/replayer-config.yaml`) |

Tools that already accept a `--config` (or equivalent) flag can simply read
`$TRANSFORMS_ENTRYPOINT` and load that file. The on-disk layout under
`/transforms/` is entirely up to the image author — the orchestration only
guarantees the mount path and the env var.

---

## Kubernetes version requirement

The image volume source went GA in Kubernetes 1.35
([KEP-4639](https://github.com/kubernetes/enhancements/issues/4639)). Earlier
clusters will reject the pod spec with
`spec.volumes[*].image: Forbidden: feature gate ImageVolume is disabled`. If
you need to ship transforms to an older cluster, do not use this flow.
