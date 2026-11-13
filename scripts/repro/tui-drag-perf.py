#!/usr/bin/env python3
"""Measure synthetic TUI drag backlog through a drained private PTY (Linux).

Usage:
  python3 scripts/repro/tui-drag-perf.py --binary yca/target/yca-bin.graal.bin --paced
  python3 scripts/repro/tui-drag-perf.py --jar yca/target/yca-bin-1.0.0-SNAPSHOT-fatjar.jar --width 240 --height 70 --output /tmp/drag-results

Every trial uses a generated transcript and isolated home/config/storage/workdir.
A local HTTP tripwire must receive zero requests. No Enter or release is sent:
no prompt submission, clipboard copy, or real session access. Artifacts are NOT
rebuilt; rebuild first when comparing changes. Output includes burst-to-fence
latency and paced residual latency after the last report, not terminal-emulator
paint latency or physical mouse performance. Production frames are not counted;
frame-count regressions belong to the headless tests. --output retains raw ANSI
and JSON in a new subdirectory; otherwise temporary files are removed on exit.
"""

import argparse
import contextlib
import fcntl
import json
import math
import os
import pty
import select
import shutil
import statistics
import struct
import subprocess
import sys
import tempfile
import termios
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Tripwire(BaseHTTPRequestHandler):
    requests = []

    def do_GET(self):
        self.reject()

    def do_POST(self):
        self.reject()

    def reject(self):
        self.requests.append({"method": self.command, "path": self.path})
        self.send_response(503)
        self.end_headers()

    def log_message(self, *args):
        pass


class Terminal:
    def __init__(self, command, env, workdir, width, height, raw):
        self.raw = raw
        self.tail = b""
        master, slave = pty.openpty()
        self.master = master
        try:
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", height, width, 0, 0))
            os.set_blocking(master, False)
            # A tiny exec child avoids preexec_fn in this multithreaded harness.
            trampoline = ("import fcntl,os,sys,termios; "
                          "fcntl.ioctl(0,termios.TIOCSCTTY,0); "
                          "os.execvpe(sys.argv[1],sys.argv[1:],os.environ)")
            self.process = subprocess.Popen(
                [sys.executable, "-c", trampoline, *command], stdin=slave,
                stdout=slave, stderr=slave, env=env, cwd=workdir, start_new_session=True)
        except BaseException:
            os.close(master)
            raise
        finally:
            os.close(slave)

    def pump(self, timeout=0):
        readable, _, _ = select.select([self.master], [], [], timeout)
        if readable:
            data = os.read(self.master, 65536)
            if not data:
                raise RuntimeError("child closed the PTY")
            self.raw.write(data)
            self.tail = (self.tail + data)[-65536:]
        if self.process.poll() is not None:
            raise RuntimeError(f"child exited: {self.process.returncode}")

    def settle(self, seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            self.pump(min(0.02, max(0, deadline - time.monotonic())))

    def send(self, data):
        remaining = memoryview(data)
        deadline = time.monotonic() + 30
        while remaining:
            if time.monotonic() >= deadline:
                raise TimeoutError("PTY write timed out")
            self.pump()
            _, writable, _ = select.select([], [self.master], [], 0.01)
            if writable:
                try:
                    remaining = remaining[os.write(self.master, remaining):]
                except BlockingIOError:
                    pass

    def wait_for(self, marker, timeout=30):
        deadline = time.monotonic() + timeout
        while marker not in self.tail:
            if time.monotonic() >= deadline:
                raise TimeoutError(f"PTY fence {marker!r} timed out; see raw ANSI log")
            self.pump(0.02)

    def close(self):
        try:
            if self.process.poll() is None:
                self.process.terminate()
                try:
                    self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=5)
        finally:
            os.close(self.master)


def transcript(height):
    block = ('## Synthetic block\n\nDeterministic prose for drag profiling.\n\n```java\n' +
             ''.join(f'final var value{i} = compute("benchmark", {i});\n' for i in range(15)) +
             '```\n\n')
    return (block * (73000 // len(block) + 1) + '\n```text\n' +
            ''.join(f'MEASURE_ROW_{i} abcdefghijklmnopqrstuvwxyz 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ\n'
                    for i in range(height)) + '```\nEND_PERF_TRANSCRIPT\n')


def trial(args, artifact, root, index, port):
    work = root / f"trial-{index}"
    for name in ("home", "config", "cache", "data", "storage/sessions/s-perf"):
        (work / name).mkdir(parents=True)
    text = transcript(args.height)
    session = work / "storage/sessions/s-perf/s-perf.jsonl"
    session.write_text('\n'.join(json.dumps({"at": int(time.time() * 1000) + i,
                                           "role": role, "content": content})
                                 for i, (role, content) in enumerate([
                                     ("user", "Show the synthetic benchmark transcript."),
                                     ("assistant", text)])) + '\n', encoding="utf-8")
    config = work / "config.json"
    config.write_text(json.dumps({
        "storageHome": str(work / "storage"), "workingDir": str(work),
        "baseUrl": f"http://127.0.0.1:{port}", "apiKey": "fake", "model": "offline-perf",
        "sessions": {"startupCleanup": False}, "notifications": {"enabled": False}}), encoding="utf-8")
    props = [f"-Duser.home={work / 'home'}", f"-Dyca.configuration.json={config}",
             f"-Dyca.storageHome={work / 'storage'}", f"-Dyca.workingDir={work}",
             "-Dyca.configuration.project.enabled=false"]
    command = ([str(artifact), *props] if args.binary else
               [shutil.which("java"), *props, "--enable-native-access=ALL-UNNAMED", "-jar", str(artifact)])
    command += ["tui", "--session", "s-perf"]
    env = {"PATH": os.environ.get("PATH", ""), "TERM": "xterm-256color", "LANG": "C.UTF-8",
           "HOME": str(work / "home"), "XDG_CONFIG_HOME": str(work / "config"),
           "XDG_CACHE_HOME": str(work / "cache"), "XDG_DATA_HOME": str(work / "data")}
    with (work / "raw.ansi").open("wb") as raw:
        terminal = Terminal(command, env, work, args.width, args.height, raw)
        try:
            terminal.wait_for(b"END_PERF_TRANSCRIPT", timeout=60)
            terminal.settle(0.3)
            measurements = [("burst", count, 0) for count in (1, 100, 500, 2000)]
            if args.paced:
                measurements += [("paced", max(1, int(rate * args.paced_secs)), rate)
                                 for rate in (125, 500, 1000)]
            results = []
            for mode, count, rate in measurements:
                terminal.send(b"\x1b[<0;6;4M")  # press, never release
                terminal.settle(0.02)
                marker = f"F{index}_{mode}_{count}X".encode()
                fence = b"\x1b[200~" + marker + b"\x1b[201~"
                reports = [f"\x1b[<32;{21 + i % 50};11M".encode() for i in range(count)]
                start = time.monotonic()
                if rate:
                    for i, report in enumerate(reports):
                        deadline = start + i / rate
                        while time.monotonic() < deadline:
                            terminal.pump(min(0.01, max(0, deadline - time.monotonic())))
                        if time.monotonic() - start > args.paced_secs + 30:
                            raise TimeoutError("paced write exceeded overall deadline")
                        terminal.send(report)
                    last_report = time.monotonic()
                    terminal.send(fence)
                else:
                    terminal.send(b''.join(reports) + fence)
                    last_report = time.monotonic()
                terminal.wait_for(marker)
                end = time.monotonic()
                result = {"trial": index, "mode": mode, "count": count, "rate": rate,
                          "total_to_fence_ms": round((end - start) * 1000, 3)}
                if rate:
                    result.update(send_elapsed_ms=round((last_report - start) * 1000, 3),
                                  residual_ms=round((end - last_report) * 1000, 3))
                if Tripwire.requests:
                    raise RuntimeError(f"unexpected HTTP calls: {Tripwire.requests}")
                results.append(result)
                print(json.dumps(result), flush=True)
                terminal.send(b"\x15")  # Ctrl-U, never Enter
                # Keep repeated presses outside SelectionState's 500 ms multi-click window.
                terminal.settle(0.55)
            return results
        finally:
            terminal.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    artifact = parser.add_mutually_exclusive_group(required=True)
    artifact.add_argument("--binary", type=Path)
    artifact.add_argument("--jar", type=Path)
    parser.add_argument("--width", type=int, default=120)
    parser.add_argument("--height", type=int, default=40)
    parser.add_argument("--trials", type=int, default=3)
    parser.add_argument("--paced", action="store_true")
    parser.add_argument("--paced-secs", type=float, default=1)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    path = (args.binary or args.jar).resolve()
    if not path.is_file() or (args.binary and not os.access(path, os.X_OK)):
        parser.error("artifact must exist (and be executable for --binary)")
    if args.jar and not shutil.which("java"):
        parser.error("java must be on PATH for --jar")
    if (args.width < 90 or args.height < 20 or args.trials < 1 or
            not math.isfinite(args.paced_secs) or args.paced_secs <= 0):
        parser.error("width >= 90, height >= 20, trials >= 1 and finite positive paced-secs required")
    if args.output:
        args.output.mkdir(parents=True, exist_ok=True)
        directory = contextlib.nullcontext(tempfile.mkdtemp(prefix="run-", dir=args.output.resolve()))
    else:
        directory = tempfile.TemporaryDirectory(prefix="yca-drag-perf-")
    with directory as name, ThreadingHTTPServer(("127.0.0.1", 0), Tripwire) as server:
        root = Path(name)
        print(f"Results: {root}", flush=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            results = []
            for index in range(1, args.trials + 1):
                results.extend(trial(args, path, root, index, server.server_port))
                (root / "trials.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
            if Tripwire.requests:
                raise RuntimeError(f"unexpected HTTP calls: {Tripwire.requests}")
            summary = {"artifact": str(path), "width": args.width, "height": args.height,
                       "http_requests": Tripwire.requests, "measurements": []}
            for mode, count, rate in sorted({(r["mode"], r["count"], r["rate"]) for r in results}):
                values = [r["residual_ms" if rate else "total_to_fence_ms"] for r in results
                          if (r["mode"], r["count"], r["rate"]) == (mode, count, rate)]
                summary["measurements"].append({"mode": mode, "count": count, "rate": rate,
                                                 "median_ms": statistics.median(values),
                                                 "min_ms": min(values), "max_ms": max(values)})
            (root / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
            print(json.dumps(summary, indent=2))
        finally:
            (root / "http-requests.json").write_text(json.dumps(Tripwire.requests), encoding="utf-8")
            server.shutdown()
            thread.join(timeout=5)


if __name__ == "__main__":
    main()
