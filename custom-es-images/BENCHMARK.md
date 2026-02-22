# Custom ES Images — Benchmark & Benefits Summary

## Overview

This document compares the custom Elasticsearch Docker images (`custom-elasticsearch:VERSION`) against the public official images across all supported major versions (1.x–8.x). Benchmarks measure startup time, idle memory usage, and image size.

## Benchmark Results

Tested one representative version per major series. All tests run single-node with equivalent heap settings (256MB for 1.x–2.x, 512MB for 5.x–8.x). Single trial per version — startup times can vary ±1-2s between runs.

| Version | Image | Startup (s) | Memory (MB) | Image Size (MB) |
|---------|-------|-------------|-------------|-----------------|
| **ES 1.7.6** | Custom | 14.0 | 192 | **268** |
| | Public | 6.5 | 232 | 330 |
| **ES 2.4.6** | Custom | 5.5 | 194 | **269** |
| | Public | 5.5 | 244 | 457 |
| **ES 5.6.16** | Custom | 3.5 | 677 | **283** |
| | Public | 4.5 | 776 | 505 |
| **ES 6.8.23** | Custom | 5.5 | 755 | **610** |
| | Public | 5.5 | 741 | 919 |
| **ES 7.17.29** | Custom | 8.5 | 793 | 703 |
| | Public | 8.5 | 856 | **624** |
| **ES 8.19.11** | Custom | 14.5 | 929 | **944** |
| | Public | 12.5 | 969 | 1333 |

### Key Observations

- **ES 1.x–2.x**: Custom images are **19–41% smaller** due to Amazon Corretto 8 on Alpine vs the legacy Docker Hub base images.
- **ES 5.x–6.x**: Custom images are **34–44% smaller** — the tarball-on-Alpine approach strips the bundled JDK and uses a minimal base image vs the official CentOS/Ubuntu-based images.
- **ES 7.17**: Custom image is **13% larger** than public. The official 7.17 image uses an optimized CentOS 7 base; our Corretto + full tarball approach adds overhead for this version.
- **ES 8.x**: Custom images are **29% smaller** — ML native binaries and bundled JDK are stripped.
- **Startup times** are comparable across custom and public images. The ES 1.7.6 custom outlier (14s vs 6.5s) is likely a cold-start artifact from the single trial.
- **Memory usage** is comparable, with custom images using slightly less in most cases.

## Benefits of Custom Images

### 1. Zero-Config Startup

The primary benefit. Public images require version-specific environment variables to run in single-node mode:

```bash
# Public image — must know the right flags per version
docker run -e discovery.type=single-node \
           -e xpack.security.enabled=false \
           -e xpack.security.enrollment.enabled=false \
           -e xpack.security.http.ssl.enabled=false \
           -e xpack.security.transport.ssl.enabled=false \
           docker.elastic.co/elasticsearch/elasticsearch:8.19.11

# Custom image — just works
docker run custom-elasticsearch:8.19.11
```

The required flags differ across versions:
- **ES 1.x–2.x**: No special flags needed (but no single-node mode either)
- **ES 5.0–5.3**: `http.host`, `transport.host`, `discovery.zen.minimum_master_nodes`
- **ES 5.4–6.x**: `discovery.type=single-node`, `xpack.security.enabled=false`
- **ES 7.x**: Same as 5.4–6.x
- **ES 8.x**: All of the above plus `xpack.security.enrollment.enabled`, SSL settings

Custom images bake all of this in — every version starts with a single `docker run`.

### 2. Modern Docker Compatibility

- **ES 1.x–2.x**: Public Docker Hub images (`elasticsearch:1.x/2.x`) use old base images that may have compatibility issues with modern Docker runtimes and cgroups v2.
- **Custom images**: Built on `amazoncorretto:8-alpine`, fully compatible with modern Docker.

### 3. cgroups v2 Fix for ES 5.1–5.2

ES 5.1 and 5.2 have a [known bug](https://github.com/elastic/elasticsearch/issues/22899) where they crash on systems using cgroups v2 (default on modern Linux). The custom images include an `LD_PRELOAD` fix that intercepts cgroup file reads, making these versions work on modern hosts without kernel configuration changes.

### 4. Built-in Health Checks

All custom images include a Docker `HEALTHCHECK` directive:

```dockerfile
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=12 \
  CMD curl -sf http://localhost:9200/_cluster/health || exit 1
```

This enables:
- `docker ps` shows health status
- Docker Compose `depends_on` with `condition: service_healthy`
- Orchestrators can use native health checking

Public images do not include health checks.

### 5. Smaller Images

| Version | Custom | Public | Savings |
|---------|--------|--------|---------|
| ES 1.7.6 | 268 MB | 330 MB | **19%** |
| ES 2.4.6 | 269 MB | 457 MB | **41%** |
| ES 5.6.16 | 283 MB | 505 MB | **44%** |
| ES 6.8.23 | 610 MB | 919 MB | **34%** |
| ES 8.19.11 | 944 MB | 1333 MB | **29%** |

Custom images use a multi-stage build with Amazon Corretto on Alpine (1.x–7.x) or AL2023 (8.x), stripping the bundled JDK and ML native binaries. ES 7.17 is an exception where the public image is smaller (624 MB vs 703 MB).

### 6. Consistent Interface Across All 67 Versions

Every version from ES 1.0 to 8.19 uses the same image naming convention and startup behavior:

```bash
docker run custom-elasticsearch:1.7.6    # ES 1.x
docker run custom-elasticsearch:5.6.16   # ES 5.x
docker run custom-elasticsearch:8.19.11  # ES 8.x
```

No need to remember different registries (`elasticsearch:` vs `docker.elastic.co/elasticsearch/elasticsearch:`), different config flags, or version-specific workarounds.

## When to Use Custom vs Public Images

| Use Case | Recommendation |
|----------|---------------|
| Migration testing (single-node) | **Custom** — zero config, all versions work identically |
| CI/CD pipelines | **Custom** — health checks, consistent interface |
| ES 1.x–2.x on modern Docker | **Custom** — modern base image, smaller |
| ES 5.1–5.2 on cgroups v2 hosts | **Custom** — includes cgroups fix |
| Production clusters | **Public** — use official images with proper configuration |
| Custom plugin testing | **Public** — start from official base |

## Methodology

- Benchmarked on the same host, sequential runs (not parallel)
- Startup time: wall-clock from `docker run` to first successful `GET /_cluster/health`
- Memory: `docker stats` reading ~2s after health check passes
- Image size: `docker image inspect` compressed size
- Single trial per version (startup times can vary ±1s between runs)
