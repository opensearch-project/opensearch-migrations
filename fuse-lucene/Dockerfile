# Global ARGs (must be before first FROM to be usable in FROM lines)
ARG BUILDER_IMAGE=docker.io/library/rust:1.83-slim
ARG RUNTIME_IMAGE=docker.io/library/amazonlinux:2023

# Multi-stage build: compile Rust binary, then copy to minimal runtime image
FROM ${BUILDER_IMAGE} AS builder

RUN apt-get update && apt-get install -y pkg-config libfuse-dev && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY Cargo.toml Cargo.lock* ./
COPY src/ src/

RUN cargo build --release && strip target/release/snapshot-fuse

# Runtime image: AL2023 with FUSE support + mount-s3 for per-pod S3 access
FROM ${RUNTIME_IMAGE}

RUN dnf install -y fuse fuse-libs && \
    ARCH=$(uname -m) && \
    case "$ARCH" in x86_64) S3_ARCH=x86_64;; aarch64) S3_ARCH=arm64;; *) echo "Unsupported arch: $ARCH" && exit 1;; esac && \
    curl --fail --silent --show-error --location --retry 5 --retry-delay 5 --retry-all-errors \
      "https://s3.amazonaws.com/mountpoint-s3-release/latest/${S3_ARCH}/mount-s3.rpm" -o /tmp/mount-s3.rpm && \
    rpm -K /tmp/mount-s3.rpm && \
    dnf install -y /tmp/mount-s3.rpm && \
    mount-s3 --version && \
    rm /tmp/mount-s3.rpm && \
    dnf clean all && \
    rm -rf /var/cache/dnf

COPY --from=builder /build/target/release/snapshot-fuse /usr/local/bin/snapshot-fuse
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# FUSE requires /dev/fuse and SYS_ADMIN capability
ENTRYPOINT ["entrypoint.sh"]
