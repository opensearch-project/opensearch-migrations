package main

// version.go — package-level vars populated by -ldflags. See
// cmd/tui/AGENTS.md Rule 3.
//
//   go build -ldflags '-X main.Version=$(git describe) -X main.MAVersion=$(cat MA_VERSION)' ./cmd/tui

// Version is the TUI's own version (the binary running the wizard).
// "dev" is the dev-build sentinel.
var Version = "dev"

// MAVersion is the migration-assistant CLI version this TUI pins
// artifacts against. "0.0.0" is the dev-build sentinel; production
// builds inject the real semver via -ldflags.
var MAVersion = "0.0.0"
