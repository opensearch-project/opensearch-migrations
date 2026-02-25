# Custom Elasticsearch Docker Images

Build and test custom Elasticsearch Docker images for **every minor version** from ES 1.x through 8.x (latest patch each). Designed for migration testing with [OpenSearch Migrations](https://github.com/opensearch-project/opensearch-migrations).

## Versions Covered

| Major | Minor Versions | Base Image |
|-------|---------------|------------|
| 1.x   | 1.0–1.7 (8 versions) | Amazon Corretto 8 (Alpine) |
| 2.x   | 2.0–2.4 (5 versions) | Amazon Corretto 8 (Alpine) |
| 5.x   | 5.0–5.6 (7 versions) | Amazon Corretto 8 (Alpine) |
| 6.x   | 6.0–6.8 (9 versions) | Amazon Corretto 8–11 (Alpine/AL2023) |
| 7.x   | 7.0–7.17 (18 versions) | Amazon Corretto 11–17 (AL2023) |
| 8.x   | 8.0–8.19 (20 versions) | Amazon Corretto 21 (AL2023) |

**Total: 67 images** covering all ES minor versions. All versions are built from official Elastic tarballs on Amazon Corretto. Multi-arch support (x86_64/aarch64) is handled automatically via Docker's `TARGETARCH`.

## Architecture

A single unified Dockerfile handles all 67 versions:

- **`dockerfiles/Dockerfile`** — Multi-stage build: downloads the correct arch-specific tarball via `TARGETARCH`, generates version-specific config (network, discovery, security), and runs on Amazon Corretto

All images are configured for single-node development use with security disabled (ES 8.x).

## Quick Start

```bash
# List all versions
make list

# Build and test a specific version
./build.sh 7.10 && ./test.sh 7.10

# Build and test all versions for a major
make bt-7

# Build and test everything
make bt-all
```

## Usage

```bash
# Build
./build.sh 7.10 7.17 8.11    # Specific versions
./build.sh --major 6          # All 6.x
./build.sh --all              # Everything

# Test
./test.sh 7.10 7.17           # Specific versions
./test.sh --major 8           # All 8.x
./test.sh --all               # Everything

# Feature tests (CRUD, bulk, search, mappings, aliases, snapshots, etc.)
./feature-test.sh 7.10 7.17

# Run an image
docker run -d -p 9200:9200 custom-elasticsearch:7.10.2
```

## Gradle Integration

The primary build mechanism for CI and on-demand image building is Gradle (`build.gradle`). It handles per-version Docker builds and registry push support. Tarballs are downloaded inside the Dockerfile with automatic arch detection.

```bash
# Build a specific version
./gradlew :custom-es-images:buildImage_7_10

# Build core test images (versions used in integration tests)
./gradlew :custom-es-images:buildCoreTestImages

# Build all images for a major version
./gradlew :custom-es-images:buildMajor_7

# Build and push to registry
./gradlew :custom-es-images:pushImage_7_10 -PpushVersions=7.10 -PregistryEndpoint=localhost:5001
```

Images are also built **on-demand** during test execution — if a test needs `custom-elasticsearch:7.10.2` and it's not available locally, the test framework automatically invokes the Gradle build task.

## Project Structure

```
custom-es-images/
├── versions.json              # Version manifest (67 versions, each with version + tarball URL template)
├── build.gradle               # Gradle build (build, push tasks per version)
├── build.sh                   # Standalone build script
├── test.sh                    # Health check + version verify test
├── feature-test.sh            # Comprehensive feature tests (CRUD, bulk, search, snapshots, etc.)
├── benchmark.sh               # Benchmark script (startup, memory, image size)
├── Makefile                   # Convenience targets
├── dockerfiles/
│   └── Dockerfile             # All ES versions (1.x–8.x, unified multi-stage on Corretto)
├── BENCHMARK.md               # Benchmark results and benefits summary
├── .dockerignore
└── README.md
```

## Updating Versions

Edit `versions.json` to add or update versions. Each entry specifies:
- `version`: Full version string (e.g., `"7.10.2"`)
- `url`: Tarball download URL (7.0+ uses `ARCH` placeholder resolved by Docker's `TARGETARCH`)
