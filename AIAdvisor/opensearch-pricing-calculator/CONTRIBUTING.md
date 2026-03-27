# Contributing to OpenSearch Pricing Calculator

We welcome contributions from the community! This document provides guidelines to help you get started.

## Ways to Contribute

- **Bug reports** — File an issue describing the bug, steps to reproduce, and expected behavior
- **Feature requests** — Open an issue describing the use case and proposed solution
- **Code contributions** — Submit pull requests for bug fixes, features, or documentation improvements
- **Documentation** — Improve READMEs, API docs, or code comments

## Getting Started

1. Fork the repository
2. Clone your fork:
   ```bash
   git clone https://github.com/<your-username>/opensearch-pricing-calculator.git
   cd opensearch-pricing-calculator
   ```
3. Create a feature branch:
   ```bash
   git checkout -b my-feature
   ```
4. Make your changes and ensure tests pass:
   ```bash
   go test ./...
   ```
5. Commit with a descriptive message (see [Commit Guidelines](#commit-guidelines))
6. Push and open a pull request

## Development Setup

### Prerequisites

- Go 1.24+
- (Optional) Docker for container builds
- (Optional) AWS credentials for Bedrock-powered AI assistant features

### Build and Test

```bash
go mod download
go build -o opensearch-pricing-calculator .
go test ./... -v
```

### Regenerate Swagger Docs

If you modify API handler annotations:

```bash
go install github.com/swaggo/swag/cmd/swag@latest
swag init
```

## Commit Guidelines

- Write clear, concise commit messages
- Use the present tense ("Add feature" not "Added feature")
- Reference issue numbers where applicable (e.g., "Fix #42: correct warm node calculation")

## Pull Request Process

1. Ensure all tests pass (`go test ./...`)
2. Ensure the build succeeds (`go build ./...`)
3. Update documentation if your change affects API behavior or configuration
4. Keep pull requests focused — one logical change per PR
5. Expect feedback and be responsive to review comments

## Code Style

- Follow standard Go conventions (`gofmt`, `go vet`)
- Use structured logging via `zap` (not `fmt.Println`)
- Add SPDX license headers to new `.go` files:
  ```go
  // Copyright OpenSearch Contributors
  // SPDX-License-Identifier: Apache-2.0
  ```
- Write tests for new functionality

## Developer Certificate of Origin

All contributions require a sign-off acknowledging the [Developer Certificate of Origin (DCO)](https://developercertificate.org/). Add a `Signed-off-by` line to your commit messages:

```
Signed-off-by: Your Name <your-email@example.com>
```

You can do this automatically with `git commit -s`.

## First-Time Contributors

Look for issues labeled **good first issue** for tasks that are suitable for newcomers. If you're unsure where to start, open an issue and ask — we're happy to help.

## Code of Conduct

This project follows the [OpenSearch Code of Conduct](CODE_OF_CONDUCT.md). Please read it before participating.
