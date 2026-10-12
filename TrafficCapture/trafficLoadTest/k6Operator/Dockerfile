# syntax=docker/dockerfile:1.7

# This build deliberately downloads one immutable upstream source archive and applies the small
# repository-owned patch beside this Dockerfile. It avoids vendoring the k6-operator source tree
# while keeping the exact source and patch independently reviewable.
ARG BUILDER_IMAGE=golang:1.26.4-alpine@sha256:3ad57304ad93bbec8548a0437ad9e06a455660655d9af011d58b993f6f615648
ARG RUNTIME_IMAGE=gcr.io/distroless/static:nonroot@sha256:963fa6c544fe5ce420f1f54fb88b6fb01479f054c8056d0f74cc2c6000df5240

FROM ${BUILDER_IMAGE} AS builder

ARG K6_OPERATOR_COMMIT=3f43f7a5915587851f27063b8e28694a29a53f43
ARG K6_OPERATOR_SOURCE_SHA256=87808985b4c5519b13673d1c1227250e6d215a710f04d5fe7c65c370105c7c0e

RUN apk add --no-cache curl patch
WORKDIR /workspace

RUN curl -fsSL \
      "https://codeload.github.com/grafana/k6-operator/tar.gz/${K6_OPERATOR_COMMIT}" \
      -o /tmp/k6-operator.tar.gz \
    && echo "${K6_OPERATOR_SOURCE_SHA256}  /tmp/k6-operator.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/k6-operator.tar.gz --strip-components=1

COPY patches/termination-message-local-inspect.patch /tmp/
RUN patch -p1 < /tmp/termination-message-local-inspect.patch

# The patch carries focused tests for both local termination-message transport and retained Cloud
# log transport. Running them in the image build prevents publishing an unverified controller.
RUN go test ./internal/controller ./pkg/resources/jobs
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /manager ./cmd/main.go

FROM ${RUNTIME_IMAGE}
WORKDIR /
COPY --from=builder /manager /manager
USER 65532:65532
ENTRYPOINT ["/manager"]
