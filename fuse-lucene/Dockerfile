# Multi-stage build: compile Rust binary, then copy to minimal runtime image
FROM rust:1.83-slim AS builder

RUN apt-get update && apt-get install -y pkg-config libfuse-dev && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY Cargo.toml Cargo.lock* ./
COPY src/ src/

RUN cargo build --release && strip target/release/snapshot-fuse

# Runtime image: minimal with FUSE support
FROM debian:bookworm-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends fuse libfuse2 && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/target/release/snapshot-fuse /usr/local/bin/snapshot-fuse

# FUSE requires /dev/fuse and SYS_ADMIN capability
ENTRYPOINT ["snapshot-fuse"]
