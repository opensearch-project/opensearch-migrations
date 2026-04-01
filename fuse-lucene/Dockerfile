# Multi-stage build: compile Rust binary, then copy to minimal runtime image
FROM rust:1.83-slim AS builder

RUN apt-get update && apt-get install -y pkg-config libfuse-dev && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY Cargo.toml Cargo.lock* ./
COPY src/ src/

RUN cargo build --release && strip target/release/snapshot-fuse

# Runtime image: minimal with FUSE support + mount-s3 for per-pod S3 access
FROM debian:bookworm-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates fuse libfuse2 wget && \
    DEB_ARCH=$(dpkg --print-architecture) && \
    case "$DEB_ARCH" in amd64) S3_ARCH=x86_64;; arm64) S3_ARCH=aarch64;; *) echo "Unsupported arch: $DEB_ARCH" && exit 1;; esac && \
    wget --tries=3 --waitretry=5 --timeout=30 "https://s3.amazonaws.com/mountpoint-s3-release/latest/${S3_ARCH}/mount-s3.deb" -O /tmp/mount-s3.deb && \
    dpkg-deb --info /tmp/mount-s3.deb > /dev/null && \
    dpkg -i /tmp/mount-s3.deb || (apt-get install -y -f --no-install-recommends && dpkg -i /tmp/mount-s3.deb) && \
    mount-s3 --version && \
    rm /tmp/mount-s3.deb && \
    apt-get purge -y wget && \
    apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/target/release/snapshot-fuse /usr/local/bin/snapshot-fuse
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# FUSE requires /dev/fuse and SYS_ADMIN capability
ENTRYPOINT ["entrypoint.sh"]
