//go:build windows

package flaresolverr

import (
	"os/exec"
	"strconv"
	"syscall"
)

func childSysProcAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{HideWindow: true}
}

// taskkill /T takes the Chrome children down with the solver.
func killTree(c *exec.Cmd) error {
	return exec.Command("taskkill", "/T", "/F", "/PID", strconv.Itoa(c.Process.Pid)).Run()
}
