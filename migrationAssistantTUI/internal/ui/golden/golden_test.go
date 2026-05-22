package golden_test

// golden_test.go — visual regression suite for every page.
//
// Each test case:
//
//   1. Constructs a page with a fixed Config.
//   2. Drives it through a documented input sequence via the f18
//      teatest harness.
//   3. Captures PlainView() (ANSI-stripped) and compares against
//      testdata/<page>_<scenario>.golden.
//
// Run with -update to regenerate goldens after an intentional UI
// change:
//
//   go test ./internal/ui/golden -run TestGolden -update
//
// Goldens are committed under testdata/. Per PLAN §11.3 the testdata
// budget for this package is 1 MiB total — keep scenarios small.
//
// # Why this lives in its own package
//
// Per-page test files (welcome_test.go, intent_test.go, etc.) own
// behavior tests (key handling, navigation, error states). Visual
// goldens are cross-cutting: they exercise the same View() across a
// matrix of sizes + scenarios, and they're more sensitive to lipgloss
// version bumps than behavior. Keeping them in one package lets a
// single -update sweep refresh the whole matrix and lets a single CI
// job own the visual diff.

import (
	"bytes"
	"flag"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"

	deployfeat "github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/feature/deploy"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/handoffbrief"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/testutil/teatest"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/deploy"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/handoff"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/intent"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/review"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/welcome"
	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/ui/pages/wizard"
)

// Update regenerates the .golden files instead of asserting. Set
// via `go test -update`. CI runs without this flag.
var update = flag.Bool("update", false, "regenerate .golden testdata")

// Standard render size. Must match what main.go's typical terminal
// dimensions look like; 100x30 is a reasonable default that comfortably
// fits every page.
const (
	stdW = 100
	stdH = 30
)

// scenario is one (page-builder, name, optional pre-render driver) tuple.
type scenario struct {
	name  string
	build func() common.Page
	drive func(*teatest.Harness) // optional: keystrokes/messages before View()
}

// allScenarios is the full golden matrix. Keep this list ordered by
// page-flow (welcome → intent → wizard → review → deploy → handoff)
// so a new contributor can read it top-to-bottom.
func allScenarios() []scenario {
	briefSample := &handoffbrief.Brief{
		SchemaVersion: 1,
		MAVersion:     "3.2.1",
		AWSAccount:    "123456789012",
		Region:        "us-east-1",
		EKSCluster:    "ma-cluster",
		Namespace:     "ma",
		Stage:         "dev",
	}

	return []scenario{
		// -------- welcome ----------------------------------------
		{
			name:  "welcome_default",
			build: func() common.Page { return welcome.New(welcome.Config{MAVersion: "3.2.1"}) },
		},
		{
			name:  "welcome_dev_version",
			build: func() common.Page { return welcome.New(welcome.Config{}) },
		},

		// -------- intent -----------------------------------------
		{
			name:  "intent_default",
			build: func() common.Page { return intent.New(intent.Config{}) },
		},

		// -------- wizard -----------------------------------------
		{
			name: "wizard_default",
			build: func() common.Page {
				return wizard.New(wizard.Config{
					MAVersion:  "3.2.1",
					AWSAccount: "123456789012",
					Region:     "us-east-1",
					EKSCluster: "ma-cluster",
					Namespace:  "ma",
					Stage:      "dev",
				})
			},
		},

		// -------- review -----------------------------------------
		{
			name:  "review_no_brief",
			build: func() common.Page { return review.New(review.Config{}) },
		},
		{
			name:  "review_with_brief",
			build: func() common.Page { return review.New(review.Config{Brief: briefSample}) },
		},

		// -------- deploy -----------------------------------------
		{
			name: "deploy_default",
			build: func() common.Page {
				return deploy.New(deploy.Config{Plan: deployfeat.Plan{}})
			},
		},
		{
			name: "deploy_with_plan",
			build: func() common.Page {
				return deploy.New(deploy.Config{
					Plan: deployfeat.Plan{
						Items: []deployfeat.PlanItem{
							{Phase: "preflight", Description: "Validate AWS credentials"},
							{Phase: "imageops", Description: "Push container images"},
							{Phase: "nodepool", Description: "Provision EKS node pool"},
							{Phase: "helm", Description: "Install MA Helm chart"},
							{Phase: "smoke", Description: "Run smoke checks"},
						},
					},
				})
			},
		},

		// -------- handoff ----------------------------------------
		{
			name:  "handoff_no_agent",
			build: func() common.Page { return handoff.New(handoff.Config{}) },
		},
		{
			name: "handoff_claude",
			build: func() common.Page {
				return handoff.New(handoff.Config{
					AgentBin:  "/usr/local/bin/claude",
					AgentName: "Claude Code",
					BriefPath: "/tmp/work/.ma/handoff/brief.md",
				})
			},
		},
	}
}

func TestGolden(t *testing.T) {
	for _, sc := range allScenarios() {
		t.Run(sc.name, func(t *testing.T) {
			page := sc.build()
			h := teatest.New(t, page)
			h.Resize(stdW, stdH)
			if sc.drive != nil {
				sc.drive(h)
			}

			got := h.PlainView()
			// Normalize trailing whitespace to keep diffs minimal —
			// lipgloss occasionally pads rows with trailing spaces.
			got = trimTrailingSpaces(got)
			// Ensure the file ends with exactly one newline.
			got = strings.TrimRight(got, "\n") + "\n"

			path := filepath.Join("testdata", sc.name+".golden")

			if *update {
				require.NoError(t, os.MkdirAll("testdata", 0o755))
				require.NoError(t, os.WriteFile(path, []byte(got), 0o644))
				return
			}

			want, err := os.ReadFile(path)
			require.NoError(t, err,
				"missing golden %s — run `go test ./internal/ui/golden -run TestGolden -update`",
				path)

			if !bytes.Equal(want, []byte(got)) {
				t.Fatalf("golden mismatch for %s\n--- want ---\n%s\n--- got ---\n%s",
					sc.name, string(want), got)
			}
		})
	}
}

// trimTrailingSpaces removes trailing spaces from every line. Lipgloss
// sometimes pads rendered cells with U+0020 to enforce width; we don't
// care about that for visual-regression purposes and stripping makes
// diffs easier to read.
func trimTrailingSpaces(s string) string {
	lines := strings.Split(s, "\n")
	for i, ln := range lines {
		lines[i] = strings.TrimRight(ln, " \t")
	}
	return strings.Join(lines, "\n")
}

// -----------------------------------------------------------------------
// Golden file budget guard (PLAN §11.3: ≤ 1 MiB)
// -----------------------------------------------------------------------

func TestGoldenSizeBudget(t *testing.T) {
	dir := "testdata"
	entries, err := os.ReadDir(dir)
	if os.IsNotExist(err) {
		t.Skip("no testdata yet — run -update first")
	}
	require.NoError(t, err)

	const budget = 1 << 20 // 1 MiB
	var total int64
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		fi, err := e.Info()
		require.NoError(t, err)
		total += fi.Size()
	}
	if total > budget {
		t.Fatalf("testdata exceeds 1 MiB budget: %d bytes (PLAN §11.3)", total)
	}
}
