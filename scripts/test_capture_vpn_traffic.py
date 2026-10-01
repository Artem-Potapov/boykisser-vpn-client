"""Exercise the temporary device helper with controlled ip/tcpdump processes."""
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest


HELPER = Path(__file__).with_name("capture-vpn-traffic.bash")


class CaptureVpnTrafficTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}",
                        FAKE_NETWORK_STATE=str(self.root / "polls"),
                        CAPTURE_EXIT_DIR=str(self.root / "exits"))
        self.executable("id", "#!/bin/sh\nprintf '%s\\n' \"${FAKE_UID:-0}\"\n")
        self.executable("ip", """#!/usr/bin/env python3
import os, signal, sys, time
from pathlib import Path
if 'monitor' in sys.argv:
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    while True:
        time.sleep(0.05)
state = Path(os.environ['FAKE_NETWORK_STATE'])
n = int(state.read_text()) if state.exists() else 0
if os.environ.get('FIXED_TUN'):
    n = 0
state.write_text(str(n + 1))
print('2: eth0: <UP> mtu 1500')
if n < 2:
    print('30: tun0: <UP> mtu 1400')
elif n < 4:
    print('30: tun0: <UP> mtu 1400')
    print('31: tun1: <UP> mtu 1400')
else:
    print('31: tun1: <UP> mtu 1400')
    print('32: tun0: <UP> mtu 1400')
""")
        self.executable("tcpdump", """#!/usr/bin/env python3
import os, signal, sys, time
from pathlib import Path
args = sys.argv[1:]
out = Path(args[args.index('-w') + 1])
interface = args[args.index('-i') + 1]
def stop(signum, _):
    with out.open('a') as f:
        f.write('flushed on ' + signal.Signals(signum).name + '\\n')
    Path(os.environ['CAPTURE_EXIT_DIR']).mkdir(exist_ok=True)
    (Path(os.environ['CAPTURE_EXIT_DIR']) / out.name).write_text('exited')
    sys.exit(0)
signal.signal(signal.SIGINT, signal.SIG_IGN if os.environ.get('IGNORE_INT') else stop)
signal.signal(signal.SIGTERM, stop)
if os.environ.get('FAIL_FIRST_TUN') and interface.startswith('tun'):
    failed = out.parent / '.fake-tun-failed'
    if not failed.exists():
        failed.touch()
        sys.exit(7)
attempt = 0
if os.environ.get('FAIL_TUN_RETRY') and interface.startswith('tun'):
    attempts = out.parent / '.fake-tun-attempts'
    attempt = int(attempts.read_text()) + 1 if attempts.exists() else 1
    attempts.write_text(str(attempt))
    if attempt == 2:
        sys.exit(7)
out.write_text(interface + '\\n' + (f'attempt={attempt}\\n' if attempt else ''))
started = time.monotonic()
while True:
    if attempt == 1 and time.monotonic() - started > 0.3:
        sys.exit(7)
    if os.environ.get('FAIL_INTERFACE') == interface and time.monotonic() - started > 0.4:
        sys.exit(7)
    time.sleep(0.02)
""")

    def executable(self, name, content):
        path = self.bin / name
        path.write_text(content)
        path.chmod(0o755)

    def launch(self, output):
        return subprocess.Popen(["sh", str(HELPER), str(output), "eth0"],
                                env=self.env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True)

    def test_refuses_unprivileged_capture_before_creating_output(self):
        output = self.root / "unprivileged"
        env = dict(self.env, FAKE_UID="2000")
        result = subprocess.run(["sh", str(HELPER), str(output), "eth0"],
                                env=env, capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("root", result.stderr)
        self.assertFalse(output.exists())

    def test_fails_if_authoritative_capture_cannot_start(self):
        self.executable("tcpdump", "#!/bin/sh\nprintf 'capture denied\\n' >&2\nexit 1\n")
        output = self.root / "failed"
        proc = self.launch(output)
        try:
            text, _ = proc.communicate(timeout=5)
            self.assertNotEqual(0, proc.returncode, text)
            self.assertIn("capture failed", text)
        finally:
            if proc.poll() is None:
                proc.terminate()
                proc.communicate(timeout=5)

    def test_reports_authoritative_capture_death_after_startup(self):
        for interface in ("any", "eth0"):
            with self.subTest(interface=interface):
                self.env["FAIL_INTERFACE"] = interface
                output = self.root / f"death-{interface}"
                proc = self.launch(output)
                try:
                    try:
                        text, _ = proc.communicate(timeout=4)
                    except subprocess.TimeoutExpired:
                        proc.terminate()
                        proc.communicate(timeout=5)
                        self.fail("helper continued after the authoritative capture died")
                    self.assertNotEqual(0, proc.returncode, text)
                    self.assertIn("capture failed", text)
                    self.assertIn("FAILED", (output / "capture-events.log").read_text())
                finally:
                    if proc.poll() is None:
                        proc.terminate()
                        proc.communicate(timeout=5)
        self.env.pop("FAIL_INTERFACE")

    def test_retries_a_failed_attach_to_the_same_tun_generation(self):
        self.env.update(FAIL_FIRST_TUN="1", FIXED_TUN="1")
        output = self.root / "retry"
        proc = self.launch(output)
        try:
            deadline = time.monotonic() + 3
            captures = []
            while time.monotonic() < deadline:
                captures = [p for p in output.glob("tun-30*.pcap") if p.stat().st_size]
                if captures:
                    break
                time.sleep(0.02)
            self.assertTrue(captures, "failed TUN attachment was never retried")
            (output / "STOP").touch()
            proc.communicate(timeout=5)
            self.assertEqual(0, proc.returncode)
        finally:
            if proc.poll() is None:
                proc.terminate()
                proc.communicate(timeout=5)

    def test_failed_reattachment_preserves_the_original_tun_capture(self):
        self.env.update(FAIL_TUN_RETRY="1", FIXED_TUN="1")
        output = self.root / "reattach"
        proc = self.launch(output)
        try:
            deadline = time.monotonic() + 4
            recovered = False
            while time.monotonic() < deadline:
                recovered = any("attempt=3" in p.read_text()
                                for p in output.glob("tun-30*.pcap"))
                if recovered or proc.poll() is not None:
                    break
                time.sleep(0.02)
            self.assertTrue(recovered, "TUN capture did not recover after a failed retry")
            (output / "STOP").touch()
            proc.communicate(timeout=5)
            self.assertIn("attempt=1", (output / "tun-30.pcap").read_text(),
                          "reattachment overwrote the original capture")
        finally:
            if proc.poll() is None:
                proc.terminate()
                proc.communicate(timeout=5)

    def test_stops_writers_that_ignore_sigint(self):
        self.env.update(IGNORE_INT="1", FIXED_TUN="1")
        output = self.root / "term"
        proc = self.launch(output)
        try:
            deadline = time.monotonic() + 3
            while not (output / "tun-30.pcap").exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue((output / "tun-30.pcap").exists())
            (output / "STOP").touch()
            text, _ = proc.communicate(timeout=5)
            self.assertEqual(0, proc.returncode, text)
            for name in ("any.pcap", "underlay.pcap", "tun-30.pcap"):
                self.assertIn("flushed on SIGTERM", (output / name).read_text())
                self.assertEqual("exited", (self.root / "exits" / name).read_text())
        finally:
            if proc.poll() is None:
                proc.terminate()
                proc.communicate(timeout=5)

    def test_refuses_existing_output_without_overwriting(self):
        output = self.root / "existing"
        output.mkdir()
        sentinel = output / "keep.txt"
        sentinel.write_text("untouched")
        result = subprocess.run(["sh", str(HELPER), str(output), "eth0"],
                                env=self.env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("already exists", result.stderr)
        self.assertEqual("untouched", sentinel.read_text())

    def test_captures_bridge_and_recreated_tun_then_flushes_on_stop(self):
        output = self.root / "capture"
        proc = self.launch(output)
        try:
            expected = ["any.pcap", "underlay.pcap", "tun-30.pcap",
                        "tun-31.pcap", "tun-32.pcap"]
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                if all((output / name).is_file() for name in expected):
                    break
                if proc.poll() is not None:
                    break
                time.sleep(0.02)
            self.assertTrue(all((output / name).is_file() for name in expected),
                            f"missing capture generation; exit={proc.poll()}")
            (output / "STOP").touch()
            text, _ = proc.communicate(timeout=5)
            self.assertEqual(0, proc.returncode, text)
            for name in expected:
                self.assertIn("flushed on SIGINT", (output / name).read_text())
                self.assertEqual("exited", (self.root / "exits" / name).read_text())
            self.assertEqual("tun0", (output / "tun-30.pcap").read_text().splitlines()[0])
            self.assertEqual("tun1", (output / "tun-31.pcap").read_text().splitlines()[0])
            self.assertEqual("tun0", (output / "tun-32.pcap").read_text().splitlines()[0])
            self.assertIn("STOPPED", (output / "capture-events.log").read_text())
        finally:
            if proc.poll() is None:
                proc.terminate()
                proc.communicate(timeout=5)


if __name__ == "__main__":
    unittest.main()
