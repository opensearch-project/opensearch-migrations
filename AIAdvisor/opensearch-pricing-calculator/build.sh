#!/bin/bash
set -e

BINARY_NAME="opensearch-pricing-calculator"
VERSION="${VERSION:-2.0.0}"
IMAGE_NAME="${IMAGE_NAME:-$BINARY_NAME}"

echo "Building linux binary"
rm -f ./$BINARY_NAME
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -a -installsuffix cgo -o $BINARY_NAME .

echo "Building docker image"
docker build -t $IMAGE_NAME:$VERSION .

echo "Build complete: $IMAGE_NAME:$VERSION"
