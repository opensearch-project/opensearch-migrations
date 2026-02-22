# Custom ES Images — Benchmark & Benefits Summary

## Overview

This document compares the custom Elasticsearch Docker images (`custom-elasticsearch:VERSION`) against the public official images across all supported major versions (1.x–8.x). Benchmarks measure startup time, idle memory usage, and image size.

## Benchmark Results

Tested one representative version per major series. All tests run single-node with equivalent heap settings (256MB for 1.x–2.x, 512MB for 5.x–8.x). Single trial per version — startup times can vary ±1-2s between runs.

| Version | Image | Startup (s) | Memory (MB) | Image Size (MB) |
|---------|-------|-------------|-------------|-----------------|
| **ES 1.7.6** | Custom | 5.4 | 241 | **361** |
| | Public | 6.5 | 226 | 330 |
| **ES 2.4.6** | Custom | 5.5 | 253 | **362** |
| | Public | 5.4 | 261 | 457 |
| **ES 5.6.16** | Custom | 3.5 | 727 | **376** |
| | Public | 4.5 | 753 | 505 |
| **ES 6.8.23** | Custom | 5.6 | 788 | **721** |
| | Public | 5.5 | 770 | 919 |
| **ES 7.17.29** | Custom | 8.5 | 802 | 814 |
| | Public | 8.5 | 865 | **624** |
| **ES 8.19.11** | Custom | 13.5 | 996 | **1048** |
| | Public | 12.5 | 965 | 1333 |

### Key Observations

- **ES 1.x–5.x**: Custom images are now **significantly smaller** than the previous AL2023-JRE approach. Multi-stage build copies only the stripped Corretto 8 JRE (~110MB) into a clean `amazonlinux:2023` base, avoiding ~700MB of JavaFX/GTK3/mesa bloat. ES 2.x–5.x custom images are **21–26% smaller** than public.
- **ES 1.x**: Custom is slightly larger than public (361 vs 330 MB) — the public image uses a very compact legacy Debian base.
- **ES 6.x**: Custom images are **22% smaller** (721 vs 919 MB) — Corretto 11 headless on AL2023 is lean, and the tarball approach strips the bundled JDK.
- **ES 7.17**: Custom image is **30% larger** than public. The official 7.17 image uses an optimized CentOS 7 base.
- **ES 8.x**: Custom images are **21% smaller** (1048 vs 1333 MB) — ML native binaries and bundled JDK are stripped.
- **Startup times** are comparable across custom and public images.
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
- **Custom images**: Built on `amazonlinux:2023` with Amazon Corretto, fully compatible with modern Docker.

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

### 5. Smaller Images (ES 2.x+)

| Version | Custom | Public | Savings |
|---------|--------|--------|---------|
| ES 2.4.6 | 362 MB | 457 MB | **21%** |
| ES 5.6.16 | 376 MB | 505 MB | **26%** |
| ES 6.8.23 | 721 MB | 919 MB | **22%** |
| ES 8.19.11 | 1048 MB | 1333 MB | **21%** |

Custom images use a multi-stage build: Corretto 8 JRE is installed in a builder stage, JavaFX files are stripped, and only the JRE is copied to a clean `amazonlinux:2023` base — avoiding ~700MB of JavaFX/GTK3/mesa dependencies. ES 6.x+ uses Corretto 11/17/21 headless on AL2023, stripping the bundled JDK and ML native binaries. ES 7.17 is an exception where the public image is smaller (624 MB vs 814 MB).

Note: ES 1.x custom images are slightly larger than public (361 vs 330 MB) because the public images use very compact legacy Debian bases.

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
