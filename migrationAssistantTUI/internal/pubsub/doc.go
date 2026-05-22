// Package pubsub is the migration-assistant TUI's sanctioned backend → UI
// message channel (DESIGN §2.R4).
//
// The TUI's bubbletea Update loop is a single goroutine. Backend code
// (deploy phases, log tail, version-check polling) runs in its own
// goroutines. The ONLY pattern by which a backend goroutine delivers a
// message into Update is:
//
//   1. Backend publishes to a *Broker via Publish.
//   2. The root Model has subscribed once via Subscribe.
//   3. The root Model's Init returns a tea.Sub that pulls from the
//      subscription channel and yields tea.Msg values into Update.
//
// This package is deliberately tiny. It does NOT depend on bubbletea,
// because it is shared by feature packages that the UI cannot import
// (DESIGN §2.R1). The bubbletea adapter (tea.Sub wrapper) lives in
// internal/ui where it is allowed to import tea.
//
// Three design properties are load-bearing:
//
//   - Slow consumers do not block publishers. A subscriber whose channel
//     is full has its message dropped (and counted via DropCount); the
//     publisher is never blocked. This matters because deploy-phase
//     events arrive in bursts, and a hung renderer must not block the
//     deploy goroutine — that is what causes "deploy is done but UI
//     never updated" bugs.
//
//   - Unsubscribe is idempotent and safe to call from any goroutine.
//     This makes tea.Sub teardown (which can race with broker close)
//     correct without higher-level coordination.
//
//   - Close() is a one-shot terminal operation. After Close, all
//     subscription channels are closed and Publish becomes a no-op.
package pubsub
