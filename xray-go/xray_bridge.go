package xraybridge

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/transport/internet"
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	mu       sync.Mutex
	instance *core.Instance
)

// Protector is implemented on the Android side. Protect must exclude the given
// socket fd from the VPN tun (VpnService.protect(fd)) so Xray's own outbound
// connections — the proxy link and the DoH resolver query — bypass the tun
// instead of looping back into it.
type Protector interface {
	Protect(fd int) bool
}

var (
	protectorMu      sync.Mutex
	currentProtector Protector
	controllerOnce   sync.Once
	controllerErr    error
)

// RegisterProtector sets the protector used for all subsequent Xray dials and
// installs the dial controller exactly once. Call before StartXray; calling it
// again on a later start simply swaps in the new protector (last start wins).
// Returns "" on success, or a non-empty error string if the one-time dial
// controller install failed (e.g. a non-default system dialer is in effect).
func RegisterProtector(p Protector) string {
	protectorMu.Lock()
	currentProtector = p
	protectorMu.Unlock()

	// internet.RegisterDialerController APPENDS to a global controller slice, so it
	// must run exactly once for the process; the per-dial body reads the swappable
	// currentProtector. The install error is captured once and surfaced to Kotlin.
	controllerOnce.Do(func() {
		controllerErr = internet.RegisterDialerController(func(network, address string, c syscall.RawConn) error {
			protectorMu.Lock()
			p := currentProtector
			protectorMu.Unlock()
			if p == nil {
				return nil
			}
			return c.Control(func(fd uintptr) {
				p.Protect(int(fd))
			})
		})
	})

	if controllerErr != nil {
		return fmt.Sprintf("register dialer controller failed: %v", controllerErr)
	}
	return ""
}

// StartXray starts a single Xray instance using json config and Android TUN fd.
// It returns an empty string on success; otherwise it returns an error message.
func StartXray(jsonConfig string, tunFd int, assetDir string) string {
	mu.Lock()
	defer mu.Unlock()

	if strings.TrimSpace(jsonConfig) == "" {
		return "empty config"
	}
	if tunFd <= 0 {
		return fmt.Sprintf("invalid tun fd: %d", tunFd)
	}
	if instance != nil {
		if err := instance.Close(); err != nil {
			return fmt.Sprintf("failed to stop previous instance: %v", err)
		}
		instance = nil
	}

	fdValue := fmt.Sprintf("%d", tunFd)
	_ = os.Setenv("xray.tun.fd", fdValue)
	_ = os.Setenv("XRAY_TUN_FD", fdValue)
	trimmedAssetDir := strings.TrimSpace(assetDir)
	if trimmedAssetDir != "" {
		_ = os.Setenv(platform.AssetLocation, trimmedAssetDir)
		_ = os.Setenv(platform.NormalizeEnvName(platform.AssetLocation), trimmedAssetDir)
	}

	config, err := core.LoadConfig("json", strings.NewReader(jsonConfig))
	if err != nil {
		return fmt.Sprintf("config parse error: %v", err)
	}

	created, err := core.New(config)
	if err != nil {
		return fmt.Sprintf("core init error: %v", err)
	}

	if err := created.Start(); err != nil {
		_ = created.Close()
		return fmt.Sprintf("core start error: %v", err)
	}

	instance = created
	return ""
}

// StopXray closes the running instance.
// It returns an empty string on success; otherwise it returns an error message.
func StopXray() string {
	mu.Lock()
	defer mu.Unlock()

	if instance == nil {
		return ""
	}

	err := instance.Close()
	instance = nil
	if err != nil {
		return fmt.Sprintf("core stop error: %v", err)
	}
	return ""
}

// pingErr marshals a failure reason as JSON so the Kotlin side can surface/log it.
func pingErr(reason string) string {
	b, _ := json.Marshal(map[string]string{"error": reason})
	return string(b)
}

// MeasureLatency runs a one-shot url-test in a throwaway instance. It does NOT lock mu or
// touch the global `instance`, so it is safe to run alongside a live tunnel. It dials
// targetURL through the config's outbound and times a clean HTTP 204 response. Returns
// {"latencyMs":N} on success or {"error":"..."} on any failure (dial, write, read, non-204,
// timeout, or config load).
func MeasureLatency(configJson string, targetURL string, timeoutMs int) string {
	if strings.TrimSpace(configJson) == "" {
		return pingErr("empty config")
	}
	u, err := url.Parse(targetURL)
	if err != nil || u.Hostname() == "" {
		return pingErr(fmt.Sprintf("bad target url: %q", targetURL))
	}
	port := u.Port()
	if port == "" {
		port = "80"
	}
	portNum, err := strconv.Atoi(port)
	if err != nil {
		return pingErr(fmt.Sprintf("bad target port: %q", port))
	}

	config, err := core.LoadConfig("json", strings.NewReader(configJson))
	if err != nil {
		return pingErr(fmt.Sprintf("config parse error: %v", err))
	}
	inst, err := core.New(config)
	if err != nil {
		return pingErr(fmt.Sprintf("core init error: %v", err))
	}
	if err := inst.Start(); err != nil {
		_ = inst.Close()
		return pingErr(fmt.Sprintf("core start error: %v", err))
	}
	defer inst.Close()

	timeout := time.Duration(timeoutMs) * time.Millisecond
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	dest := xnet.TCPDestination(xnet.ParseAddress(u.Hostname()), xnet.Port(portNum))
	start := time.Now()
	conn, err := core.Dial(ctx, inst, dest)
	if err != nil {
		return pingErr(fmt.Sprintf("dial: %v", err))
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(timeout))

	req, err := http.NewRequest(http.MethodGet, targetURL, nil)
	if err != nil {
		return pingErr(fmt.Sprintf("request: %v", err))
	}
	if err := req.Write(conn); err != nil {
		return pingErr(fmt.Sprintf("write: %v", err))
	}
	resp, err := http.ReadResponse(bufio.NewReader(conn), req)
	if err != nil {
		return pingErr(fmt.Sprintf("read: %v", err))
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		return pingErr(fmt.Sprintf("unexpected status %d", resp.StatusCode))
	}
	return fmt.Sprintf(`{"latencyMs":%d}`, time.Since(start).Milliseconds())
}
