package skillkit

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
)

func init() { Register(claudeCodeAdapter{}) }

// claudeCodeAdapter installs the bundle at
// <workdir>/.claude/skills/opensearch-migration/. It expects an
// agent-agnostic layout (skills/*.md at the tar root); files outside
// skills/ are also copied as a fallback.
type claudeCodeAdapter struct{}

func (claudeCodeAdapter) Agent() Agent { return AgentClaudeCode }

func (claudeCodeAdapter) Install(workdir, extractDir string) error {
	dst := filepath.Join(workdir, ".claude", "skills", "opensearch-migration")
	if err := os.RemoveAll(dst); err != nil && !os.IsNotExist(err) {
		return err
	}
	if err := os.MkdirAll(dst, 0o755); err != nil {
		return err
	}

	// Prefer extractDir/skills/; fall back to extractDir itself if
	// the bundle is laid out flat.
	srcRoot := filepath.Join(extractDir, "skills")
	if _, err := os.Stat(srcRoot); err != nil {
		srcRoot = extractDir
	}

	entries, err := os.ReadDir(srcRoot)
	if err != nil {
		return err
	}
	var index []string
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		if !strings.HasSuffix(name, ".md") {
			continue
		}
		if err := copyFile(filepath.Join(srcRoot, name), filepath.Join(dst, name)); err != nil {
			return err
		}
		index = append(index, name)
	}
	sort.Strings(index)

	var bullets strings.Builder
	for _, n := range index {
		bullets.WriteString("- ")
		bullets.WriteString(n)
		bullets.WriteString("\n")
	}
	idx := "# OpenSearch Migration Skills (Claude Code adapter)\n\n" +
		"Read `start.md` first, then follow the migration phase your skill kit refers to.\n\n" +
		"Files:\n" + bullets.String()
	return os.WriteFile(filepath.Join(dst, "SKILL.md"), []byte(idx), 0o644)
}
