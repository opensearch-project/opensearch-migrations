package skillkit

import (
	"os"
	"path/filepath"
)

func init() { Register(kiroAdapter{}) }

// kiroAdapter installs the bundle at <workdir>/.kiro/.
//
// Source layout: the upstream `kiro-assistant.tar.gz` bundle ships a
// top-level `.kiro/` directory; we move that subtree into place. If
// the bundle has no `.kiro/` (some test fixtures), the entire
// extractDir is copied to `.kiro/` instead.
type kiroAdapter struct{}

func (kiroAdapter) Agent() Agent { return AgentKiro }

func (kiroAdapter) Install(workdir, extractDir string) error {
	dst := filepath.Join(workdir, ".kiro")
	src := filepath.Join(extractDir, ".kiro")
	if _, err := os.Stat(src); err != nil {
		// Bundle didn't ship a .kiro/ root — copy the extract verbatim.
		if err := os.RemoveAll(dst); err != nil && !os.IsNotExist(err) {
			return err
		}
		return copyTree(extractDir, dst)
	}
	// Atomic-ish swap: remove old then rename. Both paths live under
	// workdir so rename is same-filesystem.
	if err := os.RemoveAll(dst); err != nil && !os.IsNotExist(err) {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	return os.Rename(src, dst)
}
