# Custom Elasticsearch Docker Images

Build and test custom Elasticsearch Docker images for **every minor version** from ES 1.x through 8.x (latest patch each). Designed for migration testing with [OpenSearch Migrations](https://github.com/opensearch-project/opensearch-migrations).

## Versions Covered

| Major | Minor Versions | Install Method |
|-------|---------------|----------------|
| 1.x   | 1.0–1.7 (8 versions) | Tarball on JDK 8 |
| 2.x   | 2.0–2.4 (5 versions) | Tarball on JDK 8 |
| 5.x   | 5.0–5.6 (7 versions) | Official Docker image |
| 6.x   | 6.0–6.8 (9 versions) | Official Docker image |
| 7.x   | 7.0–7.17 (18 versions) | Official Docker image |
| 8.x   | 8.0–8.19 (20 versions) | Official Docker image |

**Total: 67 images** covering all ES minor versions.

## Architecture

Two Dockerfiles handle all 67 versions:

- **`Dockerfile.legacy`** (ES 1.x–2.x) — Multi-stage: downloads tarball in Alpine, installs on JDK 8 slim
- **`Dockerfile.official`** (ES 5.x–8.x) — Multi-stage: prepares version-specific config in Alpine, layers on official Elastic image

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

# Run an image
docker run -d -p 9200:9200 custom-elasticsearch:7.10.2
```

## Project Structure

```
custom-es-images/
├── versions.json              # Version manifest (67 versions)
├── build.sh                   # Build script
├── test.sh                    # Test script (health check + version verify)
├── Makefile                   # Convenience targets
├── dockerfiles/
│   ├── Dockerfile.legacy      # ES 1.x–2.x (tarball multi-stage)
│   └── Dockerfile.official    # ES 5.x–8.x (official image multi-stage)
└── README.md
```

## Updating Versions

Edit `versions.json` to add or update versions. Each entry specifies:
- `version`: Full version string (e.g., `"7.10.2"`)
- `method`: `"tarball"` or `"official"`
- `url`: Download URL (tarball method only)
