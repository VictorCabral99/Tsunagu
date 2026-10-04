//go:build !windows

package flaresolverr

import (
	"os/exec"
	"syscall"
)

// Kills the whole process group so Chrome children started by FlareSolverr die with it.
func killTree(c *exec.Cmd) error {
	return syscall.Kill(-c.Process.Pid, syscall.SIGKILL)
}
