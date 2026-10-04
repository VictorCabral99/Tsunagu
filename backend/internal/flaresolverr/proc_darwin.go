//go:build darwin

package flaresolverr

import "syscall"

func childSysProcAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{Setpgid: true}
}
