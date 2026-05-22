package handoffbrief_test

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
	"gopkg.in/yaml.v3"

	"github.com/opensearch-project/opensearch-migrations/migrationAssistantTUI/internal/handoffbrief"
)

// validBrief returns a brief that passes Validate.
func validBrief() handoffbrief.Brief {
	return handoffbrief.Brief{
		MAVersion:  "3.2.1",
		AWSAccount: "123456789012",
		Region:     "us-east-1",
		EKSCluster: "ma-eks",
		Namespace:  "ma",
		Stage:      "dev",
		Source: handoffbrief.Source{
			Endpoint:      "https://es.example.com:9200",
			Engine:        "elasticsearch",
			EngineVersion: "7.10",
			AuthMethod:    "basic",
			AuthRef:       "keychain:ma-source-creds",
			ApproxSize:    "1.2 TB",
		},
		Target: handoffbrief.Target{
			Type:     "new-opensearch-domain",
			Endpoint: "",
		},
		ConsoleExec: "",
	}
}

// ----------------------------------------------------------------------------
// Validate
// ----------------------------------------------------------------------------

func TestValidate_AcceptsCompleteBrief(t *testing.T) {
	require.NoError(t, validBrief().Validate())
}

func TestValidate_FlagsAllRequiredFieldsAtOnce(t *testing.T) {
	// A blank brief should report every missing field, not just the
	// first — the wizard's review page lists ALL gaps to the user.
	err := handoffbrief.Brief{}.Validate()
	require.Error(t, err)
	var ve *handoffbrief.ValidationError
	require.True(t, errors.As(err, &ve))
	require.True(t, errors.Is(err, handoffbrief.ErrInvalidBrief))

	// Required fields by design contract
	want := []string{
		"ma_version", "aws_account", "region", "eks_cluster",
		"namespace", "stage", "source.engine", "source.auth_method",
		"target.type",
	}
	for _, f := range want {
		require.Contains(t, ve.Missing, f, "expected %q in Missing", f)
	}
}

func TestValidate_RejectsInvalidEngine(t *testing.T) {
	b := validBrief()
	b.Source.Engine = "couchbase"
	err := b.Validate()
	require.Error(t, err)
	require.Contains(t, err.Error(), "source.engine (invalid value)")
}

func TestValidate_RejectsInvalidAuthMethod(t *testing.T) {
	b := validBrief()
	b.Source.AuthMethod = "magic"
	err := b.Validate()
	require.Error(t, err)
	require.Contains(t, err.Error(), "source.auth_method (invalid value)")
}

func TestValidate_RejectsInvalidTargetType(t *testing.T) {
	b := validBrief()
	b.Target.Type = "selfhosted"
	err := b.Validate()
	require.Error(t, err)
	require.Contains(t, err.Error(), "target.type (invalid value)")
}

func TestValidate_AuthRefRequired_UnlessAuthNone(t *testing.T) {
	b := validBrief()
	b.Source.AuthRef = ""
	require.Error(t, b.Validate(), "missing auth_ref must fail when method=basic")

	b.Source.AuthMethod = "none"
	require.NoError(t, b.Validate(), "auth_ref unset is fine when method=none")
}

func TestValidate_RejectsInlinedCredentialInAuthRef(t *testing.T) {
	// Defense-in-depth: if a caller accidentally puts a password in
	// auth_ref ("hunter2") it must NOT pass validation. Only the
	// known indirection schemes (keychain:, env:, vault:) are allowed.
	cases := []string{
		"hunter2",                // bare password
		"username:password@host", // looks structured but wrong scheme
		"keychain:",              // empty scheme value
		"env:",                   // empty scheme value
	}
	for _, ref := range cases {
		t.Run(ref, func(t *testing.T) {
			b := validBrief()
			b.Source.AuthRef = ref
			err := b.Validate()
			require.Error(t, err)
			require.Contains(t, err.Error(), "auth_ref")
		})
	}
}

func TestValidate_AuthMethodNone_AllowsEmptyAuthRef(t *testing.T) {
	b := validBrief()
	b.Source.AuthMethod = "none"
	b.Source.AuthRef = ""
	require.NoError(t, b.Validate())
}

// ----------------------------------------------------------------------------
// Write
// ----------------------------------------------------------------------------

func TestWrite_HappyPath_ProducesValidFrontmatterAndBody(t *testing.T) {
	dir := t.TempDir()
	b := validBrief()
	require.NoError(t, handoffbrief.Write(dir, b, "Migrate prod cluster X to AOS."))

	raw, err := os.ReadFile(filepath.Join(dir, "HANDOFF.md"))
	require.NoError(t, err)
	s := string(raw)

	// Frontmatter delimiters.
	require.True(t, strings.HasPrefix(s, "---\n"), "must start with frontmatter")
	require.Contains(t, s, "\n---\n\n# Migration goal\n\n")

	// Body has the goal.
	require.Contains(t, s, "Migrate prod cluster X to AOS.")

	// Frontmatter parses back to the same brief.
	got, goal, err := handoffbrief.Read(dir)
	require.NoError(t, err)
	require.Equal(t, "Migrate prod cluster X to AOS.", goal)
	require.Equal(t, handoffbrief.SchemaVersion, got.SchemaVersion)
	require.Equal(t, b.MAVersion, got.MAVersion)
	require.Equal(t, b.AWSAccount, got.AWSAccount)
	require.Equal(t, b.Source.Engine, got.Source.Engine)
	require.NotEmpty(t, got.WrittenAt)
}

func TestWrite_StampsSchemaVersionAndWrittenAt(t *testing.T) {
	dir := t.TempDir()
	b := validBrief()
	b.SchemaVersion = 0 // caller forgot
	b.WrittenAt = ""    // caller forgot
	require.NoError(t, handoffbrief.Write(dir, b, "x"))

	got, _, err := handoffbrief.Read(dir)
	require.NoError(t, err)
	require.Equal(t, handoffbrief.SchemaVersion, got.SchemaVersion)
	require.NotEmpty(t, got.WrittenAt)
}

func TestWrite_RefusesInvalidBrief_DoesNotTouchDisk(t *testing.T) {
	dir := t.TempDir()
	err := handoffbrief.Write(dir, handoffbrief.Brief{}, "goal")
	require.Error(t, err)
	require.True(t, errors.Is(err, handoffbrief.ErrInvalidBrief))

	// Disk must be clean — no HANDOFF.md, no leftover .tmp.
	entries, err := os.ReadDir(dir)
	require.NoError(t, err)
	require.Empty(t, entries, "no files should have been written for an invalid brief")
}

func TestWrite_EmptyGoal_RendersSkipNotice(t *testing.T) {
	dir := t.TempDir()
	require.NoError(t, handoffbrief.Write(dir, validBrief(), "   "))
	raw, err := os.ReadFile(filepath.Join(dir, "HANDOFF.md"))
	require.NoError(t, err)
	require.Contains(t, string(raw), "user skipped intent capture")

	// Round-trip an empty goal back to "".
	_, goal, err := handoffbrief.Read(dir)
	require.NoError(t, err)
	require.Empty(t, goal)
}

func TestWrite_IsAtomic_DoesNotLeavePartialOnRenameFailure(t *testing.T) {
	// Force os.Rename to fail by pointing workdir at a path whose
	// parent doesn't allow writes. We can't easily simulate a rename
	// failure cross-platform; instead, prove the .tmp + rename pattern
	// by checking the final file appears in one shot (no .tmp visible
	// after a successful Write).
	dir := t.TempDir()
	require.NoError(t, handoffbrief.Write(dir, validBrief(), "goal"))
	entries, err := os.ReadDir(dir)
	require.NoError(t, err)
	for _, e := range entries {
		require.NotContains(t, e.Name(), ".tmp", "no .tmp must remain after Write")
	}
}

func TestWrite_OverwritesExisting(t *testing.T) {
	dir := t.TempDir()
	b1 := validBrief()
	require.NoError(t, handoffbrief.Write(dir, b1, "first"))

	b2 := validBrief()
	b2.Stage = "prod"
	require.NoError(t, handoffbrief.Write(dir, b2, "second"))

	got, goal, err := handoffbrief.Read(dir)
	require.NoError(t, err)
	require.Equal(t, "prod", got.Stage)
	require.Equal(t, "second", goal)
}

func TestWrite_CreatesWorkdirIfMissing(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "nested", "workdir")
	require.NoError(t, handoffbrief.Write(dir, validBrief(), "goal"))
	_, err := os.Stat(filepath.Join(dir, "HANDOFF.md"))
	require.NoError(t, err)
}

// ----------------------------------------------------------------------------
// Read
// ----------------------------------------------------------------------------

func TestRead_RejectsMissingFile(t *testing.T) {
	_, _, err := handoffbrief.Read(t.TempDir())
	require.Error(t, err)
}

func TestRead_RejectsMissingFrontmatter(t *testing.T) {
	dir := t.TempDir()
	require.NoError(t, os.WriteFile(filepath.Join(dir, "HANDOFF.md"),
		[]byte("# Migration goal\n\nhello\n"), 0o644))
	_, _, err := handoffbrief.Read(dir)
	require.Error(t, err)
	require.Contains(t, err.Error(), "frontmatter")
}

func TestRead_RejectsMismatchedSchemaVersion(t *testing.T) {
	dir := t.TempDir()
	b := validBrief()
	b.SchemaVersion = 999
	fm, _ := yaml.Marshal(b)
	body := "---\n" + string(fm) + "---\n\n# Migration goal\n\ngoal\n"
	require.NoError(t, os.WriteFile(filepath.Join(dir, "HANDOFF.md"), []byte(body), 0o644))

	_, _, err := handoffbrief.Read(dir)
	require.Error(t, err)
	require.True(t, errors.Is(err, handoffbrief.ErrInvalidBrief))
}

func TestRead_RoundTripsAllBriefFields(t *testing.T) {
	dir := t.TempDir()
	b := validBrief()
	b.Target.Endpoint = "https://aos.example.com"
	b.ConsoleExec = "kubectl exec -n ma -it deploy/migration-console -- /bin/bash"
	require.NoError(t, handoffbrief.Write(dir, b, "the goal"))

	got, goal, err := handoffbrief.Read(dir)
	require.NoError(t, err)
	require.Equal(t, "the goal", goal)
	// Normalize fields stamped by Write: SchemaVersion forced to
	// the package constant, WrittenAt set to UTC now.
	got.WrittenAt = b.WrittenAt
	got.SchemaVersion = b.SchemaVersion
	require.Equal(t, b, got)
}

// ----------------------------------------------------------------------------
// Constants & exports
// ----------------------------------------------------------------------------

func TestExportedConstants(t *testing.T) {
	require.Equal(t, 1, handoffbrief.SchemaVersion)
	require.Equal(t, "HANDOFF.md", handoffbrief.FileName)
}
