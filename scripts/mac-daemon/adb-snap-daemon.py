#!/usr/bin/env python3
"""
adb-snap-daemon.py — Mac companion for ADB wireless auto-connect.
No external dependencies. Requires: python3 (stdlib), adb in PATH, dns-sd (macOS builtin).

Usage: python3 adb-snap-daemon.py
Endpoints:
  GET  /health   -> {"status": "ok", "service": "<name>"}
  POST /connect  <- {"phone_ip": "192.168.x.x"}
               -> {"status": "ok"|"error", "message": "..."}
"""

import http.server
import json
import logging
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import threading

PORT = 7777
SERVICE_NAME = f"ADB-Snap-{socket.gethostname()}"
SERVICE_TYPE = "_adb-snap._tcp"

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("adb-snap")

_ADB_FALLBACK_PATHS = [
    "/usr/local/bin/adb",
    "/opt/homebrew/bin/adb",
    os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
    "/Users/Shared/Library/Android/sdk/platform-tools/adb",
]


# ── mDNS ────────────────────────────────────────────────────────────────────

def _start_mdns_proc():
    cmd = ["dns-sd", "-R", SERVICE_NAME, SERVICE_TYPE, "local", str(PORT)]
    log.info("Registering mDNS: %s", " ".join(cmd))
    return subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def _mdns_watchdog(stop_event):
    proc = _start_mdns_proc()
    while not stop_event.is_set():
        try:
            proc.wait(timeout=5)
            log.warning("dns-sd exited — restarting")
            proc = _start_mdns_proc()
        except subprocess.TimeoutExpired:
            pass
    proc.terminate()


# ── ADB ─────────────────────────────────────────────────────────────────────

def _find_adb():
    adb = shutil.which("adb")
    if adb:
        return adb
    for path in _ADB_FALLBACK_PATHS:
        if os.path.isfile(path) and os.access(path, os.X_OK):
            return path
    return None


def _find_adb_port_via_mdns(adb_bin: str, phone_ip: str):
    """Use 'adb mdns services' to find the TLS port for the given phone IP."""
    try:
        result = subprocess.run(
            [adb_bin, "mdns", "services"],
            capture_output=True, text=True, timeout=10
        )
        # Output format: "<service-name>\t_adb-tls-connect._tcp\t<ip>:<port>"
        for line in result.stdout.splitlines():
            if "_adb-tls-connect._tcp" in line and phone_ip in line:
                m = re.search(rf"{re.escape(phone_ip)}:(\d+)", line)
                if m:
                    port = int(m.group(1))
                    log.info("Found ADB TLS port via mdns services: %s:%d", phone_ip, port)
                    return port
    except Exception as e:
        log.warning("adb mdns services error: %s", e)
    return None


def run_adb_connect(phone_ip: str):
    """Discover phone's ADB TLS port via 'adb mdns services', then adb connect. Returns (status, message)."""
    adb_bin = _find_adb()
    if not adb_bin:
        return "error", "adb not found in PATH or common install locations"

    adb_port = _find_adb_port_via_mdns(adb_bin, phone_ip)
    if adb_port is None:
        return "error", (
            f"Device {phone_ip} not found in 'adb mdns services'. "
            "Make sure Wireless Debugging is enabled on the phone and the device has been paired with this Mac."
        )

    target = f"{phone_ip}:{adb_port}"

    try:
        check = subprocess.run([adb_bin, "devices"], capture_output=True, text=True, timeout=10)
        if target in check.stdout:
            log.info("Already connected to %s", target)
            return "ok", f"already connected to {target}"
    except subprocess.TimeoutExpired:
        pass

    try:
        result = subprocess.run(
            [adb_bin, "connect", target],
            capture_output=True, text=True, timeout=15
        )
        output = (result.stdout + result.stderr).strip()
        if result.returncode == 0 and "connected" in output.lower():
            log.info("Connected: %s | %s", target, output)
            return "ok", f"connected to {target}"
        log.error("adb connect failed: %s | %s", target, output)
        # Detect unpaired device — TLS handshake rejected
        if "failed to connect" in output.lower() or "connection refused" in output.lower():
            pairing_port = _find_pairing_port_via_mdns(adb_bin, phone_ip)
            hint = (
                f"This Mac has not been paired with the device yet.\n\n"
                f"On the phone: Developer Options → Wireless Debugging → Pair device with pairing code\n"
            )
            if pairing_port:
                hint += f"Then on Mac run:\n  adb pair {phone_ip}:{pairing_port}\nand enter the 6-digit code shown on the phone."
            else:
                hint += f"Then on Mac run:\n  adb pair {phone_ip}:<pairing-port>\nusing the port shown on the phone screen."
            return "needs_pairing", hint
        return "error", output
    except subprocess.TimeoutExpired:
        return "error", f"adb connect timed out for {target}"


def _find_pairing_port_via_mdns(adb_bin: str, phone_ip: str):
    """Look for _adb-tls-pairing._tcp service to get the pairing port."""
    try:
        result = subprocess.run(
            [adb_bin, "mdns", "services"],
            capture_output=True, text=True, timeout=10
        )
        for line in result.stdout.splitlines():
            if "_adb-tls-pairing._tcp" in line and phone_ip in line:
                m = re.search(rf"{re.escape(phone_ip)}:(\d+)", line)
                if m:
                    return int(m.group(1))
    except Exception as e:
        log.warning("pairing port lookup error: %s", e)
    return None


# ── HTTP ─────────────────────────────────────────────────────────────────────

class _Handler(http.server.BaseHTTPRequestHandler):

    def log_message(self, fmt, *args):
        log.info("HTTP " + fmt, *args)

    def do_GET(self):
        if self.path == "/health":
            self._json({"status": "ok", "service": SERVICE_NAME})
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path != "/connect":
            self.send_response(404)
            self.end_headers()
            return

        length = int(self.headers.get("Content-Length", 0))
        if not length:
            self._json({"status": "error", "message": "empty body"})
            return

        try:
            body = json.loads(self.rfile.read(length))
            phone_ip = body["phone_ip"]
        except (json.JSONDecodeError, KeyError, ValueError) as e:
            self._json({"status": "error", "message": f"bad request: {e}"})
            return

        log.info("Received connect request for phone_ip=%s", phone_ip)
        status, message = run_adb_connect(phone_ip)
        self._json({"status": status, "message": message})

    def _json(self, data: dict):
        body = json.dumps(data).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


# ── Entry point ──────────────────────────────────────────────────────────────

def main():
    stop_event = threading.Event()

    mdns_thread = threading.Thread(target=_mdns_watchdog, args=(stop_event,), daemon=True)
    mdns_thread.start()

    def _shutdown(signum, frame):
        log.info("Shutting down")
        stop_event.set()
        sys.exit(0)

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    log.info("Listening on 0.0.0.0:%d  service=%s", PORT, SERVICE_NAME)
    server = http.server.HTTPServer(("0.0.0.0", PORT), _Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
