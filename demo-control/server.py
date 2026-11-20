#!/usr/bin/env python3
"""Opt-in demo control for the web UI (Compose profile "demo").

Lets the UI run the failure and integrity demos with real Docker operations: stop and start a
worker, corrupt one stored chunk, and read where a chunk's file is and which log lines name it.
Nothing else is possible: every target is checked against a fixed allowlist, the port is published
on 127.0.0.1 only, and every request must come from the UI's origin and carry a token that only
that origin can read, so another web page cannot drive it.

It mounts the Docker socket, which is equivalent to root on the host, so it never runs by default.
"""

import json
import os
import re
import secrets
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(os.environ.get("PORT", "8081"))
# The UI may be opened as localhost or 127.0.0.1; both are this machine.
ALLOWED_ORIGINS = tuple(
    os.environ.get("ALLOWED_ORIGINS", "http://localhost:8080,http://127.0.0.1:8080").split(",")
)
WORKERS = ("worker1", "worker2", "worker3", "worker4")
LOG_CONTAINERS = ("controller",) + WORKERS
CHUNK_ID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9]{6,10}$")
# Generated per start; only a page served from an allowed origin can read it (CORS).
TOKEN = secrets.token_hex(16)

# Flips a bit of the first byte of the chunk's servable copy, as scripts/corrupt-chunk.sh does.
CORRUPT = r"""
path=$(find /data/chunks -name "$1.*" ! -name '*.corrupt' ! -name '*.tmp' | head -n 1)
[ -n "$path" ] || exit 3
byte=$(head -c 1 "$path" | od -An -tu1 | tr -d ' ')
printf "$(printf '\\%03o' $((byte ^ 1)))" | dd of="$path" bs=1 count=1 conv=notrunc 2>/dev/null
echo "$path"
"""

FILE = r"""
path=$(find /data/chunks -name "$1.*" ! -name '*.tmp' | head -n 1)
[ -n "$path" ] || exit 3
echo "$path $(stat -c %s "$path")"
"""


def docker(*args, timeout=40):
    return subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout)


def running(container):
    result = docker("inspect", "-f", "{{.State.Running}}", container, timeout=10)
    return result.returncode == 0 and result.stdout.strip() == "true"


class Handler(BaseHTTPRequestHandler):
    server_version = "demo-control"

    def send_json(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        origin = self.headers.get("Origin")
        self.send_header("Access-Control-Allow-Origin", origin if origin in ALLOWED_ORIGINS else ALLOWED_ORIGINS[0])
        self.send_header("Vary", "Origin")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def params(self):
        return {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}

    def allowed(self, params):
        # A browser always sends Origin on a cross-origin request; curl from the host may omit it.
        origin = self.headers.get("Origin")
        if origin is not None and origin not in ALLOWED_ORIGINS:
            self.send_json(403, {"error": "forbidden", "message": "Requests are accepted from the UI only"})
            return False
        if not secrets.compare_digest(params.get("token", ""), TOKEN):
            self.send_json(403, {"error": "forbidden", "message": "Missing or wrong token"})
            return False
        return True

    def worker(self, params, allowed=WORKERS):
        name = params.get("worker", "")
        if name not in allowed:
            self.send_json(400, {"error": "invalid_request", "message": f"worker must be one of {', '.join(allowed)}"})
            return None
        return name

    def chunk(self, params):
        chunk_id = params.get("chunk", "")
        if not CHUNK_ID.match(chunk_id):
            self.send_json(400, {"error": "invalid_request", "message": "chunk must be a chunk ID"})
            return None
        return chunk_id

    def do_GET(self):
        path = urlparse(self.path).path
        params = self.params()
        if path == "/status":
            # The token is safe to return here: CORS lets only the UI's origin read this response.
            return self.send_json(200, {"token": TOKEN, "workers": {w: running(w) for w in WORKERS}})
        if not self.allowed(params):
            return None
        if path == "/file":
            worker, chunk_id = self.worker(params), self.chunk(params)
            if worker and chunk_id:
                result = docker("exec", worker, "sh", "-c", FILE, "sh", chunk_id, timeout=15)
                if result.returncode != 0:
                    return self.send_json(404, {"error": "not_found", "message": f"{worker} has no file for {chunk_id}"})
                file_path, size = result.stdout.split()
                return self.send_json(200, {"worker": worker, "path": file_path, "size": int(size)})
            return None
        if path == "/logs":
            container, chunk_id = self.worker(params, LOG_CONTAINERS), self.chunk(params)
            if container and chunk_id:
                result = docker("logs", "--since", "15m", container, timeout=15)
                lines = [line for line in (result.stdout + result.stderr).splitlines() if chunk_id in line]
                return self.send_json(200, {"container": container, "lines": lines[-20:]})
            return None
        return self.send_json(404, {"error": "not_found", "message": "No such endpoint"})

    def do_POST(self):
        path = urlparse(self.path).path
        params = self.params()
        if not self.allowed(params):
            return None
        worker = self.worker(params)
        if not worker:
            return None
        if path in ("/stop", "/start"):
            result = docker(path[1:], worker)
            if result.returncode != 0:
                return self.send_json(500, {"error": "docker_failed", "message": result.stderr.strip()[:300]})
            return self.send_json(200, {"worker": worker, "running": running(worker)})
        if path == "/corrupt":
            chunk_id = self.chunk(params)
            if not chunk_id:
                return None
            result = docker("exec", worker, "sh", "-c", CORRUPT, "sh", chunk_id, timeout=15)
            if result.returncode != 0:
                return self.send_json(404, {"error": "not_found", "message": f"{worker} has no servable copy of {chunk_id}"})
            return self.send_json(200, {"worker": worker, "chunk": chunk_id, "path": result.stdout.strip()})
        return self.send_json(404, {"error": "not_found", "message": "No such endpoint"})

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} {fmt % args}", flush=True)


if __name__ == "__main__":
    print(f"demo-control listening on :{PORT}, accepting requests from {', '.join(ALLOWED_ORIGINS)}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
