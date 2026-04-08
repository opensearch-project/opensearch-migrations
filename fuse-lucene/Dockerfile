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

# Runtime image: AL2023 with FUSE support + mount-s3 for per-pod S3 access.
# When an NFS PersistentVolume (S3 Files) is available, mount-s3 is not used —
# the entrypoint detects the pre-mounted /mnt/s3 and skips it.
FROM ${RUNTIME_IMAGE}

RUN dnf install -y fuse fuse-libs util-linux && \
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
