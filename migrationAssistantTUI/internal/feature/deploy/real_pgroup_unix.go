//go:build unix

package deploy

import (
	"os/exec"
	"syscall"
)

// configureProcessGroup makes c the leader of a new process group.
// Combined with killProcessGroup below, this lets us SIGKILL every
// descendant (including backgrounded grandchildren that inherited
// stdout/stderr pipes) on ctx cancel, breaking the liveness chain
// that would otherwise block cmd.Wait until natural process exit.
//
// See the regression guard in real_exec_pgroup_test.go and the
// CI failure documented at
// https://github.com/opensearch-project/opensearch-migrations/pull/3008
// (TestExecCommandRunner_Run_ContextCancelKillsProcess elapsed=5.002s
// against unmodified CommandContext on ubuntu-22.04 / dash).
func configureProcessGroup(c *exec.Cmd) {
	if c.SysProcAttr == nil {
		c.SysProcAttr = &syscall.SysProcAttr{}
	}
	c.SysProcAttr.Setpgid = true
}

// killProcessGroup sends SIGKILL to the entire process group rooted at
// c.Process.Pid. The negative-pid form (kill(-pgid, sig)) is the POSIX
// idiom for "every process in this group". Returns nil if the process
// hasn't started yet (defensive — Cancel can theoretically race Start).
func killProcessGroup(c *exec.Cmd) error {
	if c.Process == nil {
		return nil
	}
	// We set Setpgid above so the child's pgid == its pid; -pid
	// targets that group. If something raced and the group is gone,
	// Kill returns ESRCH which we treat as success (Cancel contract:
	// nil or os.ErrProcessDone-equivalent is fine).
	if err := syscall.Kill(-c.Process.Pid, syscall.SIGKILL); err != nil && err != syscall.ESRCH {
		return err
	}
	return nil
}
