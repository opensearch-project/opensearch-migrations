# Global ARGs (must be before first FROM to be usable in FROM lines)
ARG BUILDER_IMAGE=docker.io/library/rust:1.94.1-alpine
ARG RUNTIME_IMAGE=docker.io/library/amazonlinux:2023

# Multi-stage build: compile statically-linked Rust binary via musl,
# then copy to AL2023 runtime (avoids glibc version mismatch).
FROM ${BUILDER_IMAGE} AS builder

RUN apk add --no-cache musl-dev fuse-dev pkgconf

WORKDIR /build
COPY Cargo.toml Cargo.lock* ./
COPY src/ src/

RUN cargo build --release && strip target/release/snapshot-fuse

# Runtime image: AL2023 with FUSE support for snapshot-fuse.
# S3 data is provided via NFS PersistentVolume (S3 Files) — no mount-s3 needed.
FROM ${RUNTIME_IMAGE}

RUN dnf install -y fuse fuse-libs util-linux && \
    dnf clean all && \
    rm -rf /var/cache/dnf

COPY --from=builder /build/target/release/snapshot-fuse /usr/local/bin/snapshot-fuse
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# FUSE requires /dev/fuse and SYS_ADMIN capability
ENTRYPOINT ["entrypoint.sh"]
