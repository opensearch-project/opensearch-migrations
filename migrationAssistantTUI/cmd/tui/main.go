// Package main is the migrationAssistantTUI entry point.
//
// See cmd/tui/AGENTS.md for the load-bearing rules. The TL;DR:
//
//   1. main is dumb — log.Setup, app wiring, Run, then optional Exec.
//   2. fmt.Print* is allowed here ONLY after tea.Program.Run() returns.
//   3. Version + MAVersion are package vars populated by -ldflags.
//
// This file holds (1) — the lifecycle. resolveHandoffBin lives in
// lookpath.go so handoff_test.go can exercise it without spinning up
// a Program.
//
//go:build !windows

package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	tea "charm.land/bubbletea/v2"

	deployfeat "github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/deploy"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/log"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/pubsub"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/deploy"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/handoff"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/intent"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/review"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/welcome"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/wizard"
)

// exitCode constants. Stable: external scripts MAY inspect them.
const (
	exitOK       = 0
	exitUsage    = 2 // bad CLI args (reserved; not used yet)
	exitLogSetup = 3 // could not open log file in any candidate dir
	exitProgram  = 4 // tea.Program.Run() returned error
	exitHandoff  = 5 // handoff requested but resolveHandoffBin failed
	exitChdir    = 6 // syscall.Chdir to handoff workdir failed
	exitExec     = 7 // syscall.Exec returned (only on EBADF / ENOEXEC)
)

// run is the testable entry point. main() defers to it so test code
// can invoke the lifecycle with custom stdio + exit semantics. The
// returned int is the process exit code.
//
// Implementation note: this is intentionally a slim wiring shim, not
// a place for business logic. Anything fancy belongs in internal/.
func run(ctx context.Context, args []string, stdout, stderr *os.File) int {
	_ = args // CLI parsing wave will pull from here.

	// (1) log.Setup — file-only slog with truncate-on-launch.
	cleanup, err := log.Setup(log.Options{})
	if err != nil {
		fmt.Fprintf(stderr, "log setup failed: %v\n", err)
		return exitLogSetup
	}
	defer cleanup()

	logger := log.L()
	logger.Info("migrationAssistantTUI starting",
		"Version", Version,
		"MAVersion", MAVersion,
	)

	// Honor SIGINT/SIGTERM so a stuck deploy goroutine doesn't pin
	// the process. tea handles ^C as a quit message; this hook is
	// belt-and-suspenders for kill -TERM.
	ctx, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()

	// (2) Compose root model. Construction-only; all I/O deferred to
	// page Init/Update.
	broker := pubsub.NewBroker()
	defer broker.Close()

	registry := ui.NewPageRegistry()
	registry.Register(welcome.New(welcome.Config{MAVersion: MAVersion}))
	registry.Register(intent.New(intent.Config{}))
	registry.Register(wizard.New(wizard.Config{MAVersion: MAVersion}))
	registry.Register(review.New(review.Config{}))
	registry.Register(deploy.New(deploy.Config{Plan: deployfeat.Plan{}}))
	registry.Register(handoff.New(handoff.Config{}))

	model := ui.NewModel(ui.Config{
		Pages:     registry,
		Broker:    broker,
		StartPage: msg.PageWelcome,
		MAVersion: MAVersion,
	})

	// Charm v2 dropped WithAltScreen — alt-screen is requested via a
	// tea.Cmd from Model.Init (tea.EnterAltScreen). The root model
	// is responsible for issuing it.
	prog := tea.NewProgram(
		model,
		tea.WithContext(ctx),
	)

	// (Adjustment A) Start the broker pump AFTER NewProgram so the
	// pump can call prog.Send. Stop on exit so the goroutine drains.
	stopPump := ui.StartBrokerPump(broker, prog)
	defer stopPump()

	// (3) Block until tea.Quit.
	if _, err := prog.Run(); err != nil {
		// Run() returns context.Canceled when SIGINT lands; treat
		// that as a clean exit, not a crash.
		if errors.Is(err, context.Canceled) {
			logger.Info("program canceled by signal")
			return exitOK
		}
		fmt.Fprintf(stderr, "program error: %v\n", err)
		logger.Error("program error", "err", err)
		return exitProgram
	}

	// (4) Inspect for Handoff payload.
	h := model.Handoff()
	if h == nil {
		// User quit normally — no exec. Per AGENTS.md Rule 2,
		// fmt.Print* is safe here (alt-screen is gone).
		fmt.Fprintln(stdout, "bye.")
		return exitOK
	}

	// (5) Resolve + exec. The resolve gate ALSO runs inside the
	// handoff page before the HandoffMsg is emitted, but we re-check
	// here as defense-in-depth: if a future page bypasses the page
	// gate, we still refuse to strand the user.
	bin, err := resolveHandoffBin(h.Bin)
	if err != nil {
		fmt.Fprintf(stderr, "handoff aborted: %v\n", err)
		fmt.Fprintf(stderr, "log:   %s\n", log.LogPath())
		fmt.Fprintf(stderr, "brief: %s\n", h.BriefPath)
		logger.Error("handoff resolveBin failed", "bin", h.Bin, "err", err)
		return exitHandoff
	}

	// argv MUST start with bin (Go convention; differs from os.Exec).
	argv := append([]string{bin}, h.Args...)
	env := h.Env
	if len(env) == 0 {
		env = os.Environ()
	}

	// Print one banner so the user sees the brief path even if the
	// agent itself doesn't surface it.
	fmt.Fprintf(stdout, "→ %s\n", bin)
	if h.BriefPath != "" {
		fmt.Fprintf(stdout, "  brief: %s\n", h.BriefPath)
	}

	if err := syscall.Exec(bin, argv, env); err != nil {
		// syscall.Exec only returns on failure (success replaces
		// the process image and never returns).
		fmt.Fprintf(stderr, "exec %s failed: %v\n", bin, err)
		logger.Error("exec failed", "bin", bin, "err", err)
		return exitExec
	}

	// Unreachable.
	return exitOK
}

func main() {
	os.Exit(run(context.Background(), os.Args[1:], os.Stdout, os.Stderr))
}
