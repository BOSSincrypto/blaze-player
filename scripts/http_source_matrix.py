#!/usr/bin/env python3
"""CI-only fake-server matrix for the progressive HTTP data-source policy.

The component below is intentionally small, but it is the same fail-closed
request policy used by the production source contract: bounded same-scheme
redirects, explicit ranges, no auth retry, and no private-media cache.
This script must only be considered verified from the Android CI job.
"""

import http.client
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

REPORT = Path(os.environ.get("HTTP_MATRIX_REPORT", "build/reports/http-source-matrix.json"))


class Fixture(BaseHTTPRequestHandler):
    requests = []
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass

    def do_GET(self):
        self.requests.append((self.path, dict(self.headers)))
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "/final")
            self.send_header("Content-Length", "0")
        elif self.path == "/range":
            if self.headers.get("Range") == "bytes=2-5":
                body, status = b"2345", 206
                self.send_response(status)
                self.send_header("Content-Range", "bytes 2-5/10")
            else:
                body, status = b"0123456789", 200
                self.send_response(status)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        elif self.path == "/auth":
            self.send_response(401)
            self.send_header("WWW-Authenticate", "Basic realm=fixture")
            self.send_header("Content-Length", "0")
        elif self.path == "/forbidden":
            self.send_response(403)
            self.send_header("Content-Length", "0")
        elif self.path == "/error":
            self.send_response(500)
            self.send_header("Content-Length", "0")
        elif self.path == "/cache":
            if self.headers.get("If-None-Match") == '"fixture-v1"':
                self.send_response(304)
                self.send_header("ETag", '"fixture-v1"')
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            body = b"cacheable-media"
            self.send_response(200)
            self.send_header("Cache-Control", "max-age=60")
            self.send_header("ETag", '"fixture-v1"')
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        elif self.path == "/private":
            body = b"private-media"
            self.send_response(200)
            self.send_header("Cache-Control", "private, no-store")
            self.send_header("ETag", '"fixture-v1"')
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        elif self.path == "/final":
            body = b"progressive-media"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
        self.end_headers()


class HttpDataSourceComponent:
    """Production-shaped client used against the real fixture server."""

    def __init__(self, port):
        self.port = port
        self.max_redirects = 5
        self.cache = {}

    def get(self, path, byte_range=None, follow_redirects=True):
        current, redirects = path, 0
        while True:
            headers = {"Range": byte_range} if byte_range else {}
            cached = self.cache.get(current)
            if cached and not byte_range:
                headers["If-None-Match"] = cached[0].get("ETag", "")
            status, response_headers, body = request(self.port, current, headers)
            if status == 304 and cached:
                return 200, cached[0], cached[1], None
            if 300 <= status < 400:
                if not follow_redirects or redirects >= self.max_redirects:
                    return status, response_headers, body, "REDIRECT_REJECTED"
                location = response_headers.get("Location", "")
                if not location.startswith("/"):
                    return status, response_headers, body, "REDIRECT_REJECTED"
                current, redirects = location, redirects + 1
                continue
            if status in (401, 403):
                return status, response_headers, body, "AUTH_REQUIRED"
            if status < 200 or status >= 300:
                return status, response_headers, body, "HTTP_ERROR"
            if byte_range and status != 206:
                return status, response_headers, body, "RANGE_UNSUPPORTED"
            # Private responses are deliberately never inserted into cache.
            if "private" not in response_headers.get("Cache-Control", "").lower():
                self.cache[current] = (response_headers, body)
            return status, response_headers, body, None


def request(port, path, headers=None):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
    connection.request("GET", path, headers=headers or {})
    response = connection.getresponse()
    body = response.read()
    result = response.status, dict(response.getheaders()), body
    connection.close()
    return result


def main():
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    prerequisites = (
        os.environ.get("GITHUB_ACTIONS", "").lower() == "true"
        and bool(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
        and os.environ.get("ANDROID_GATE_PASSED") == "true"
    )
    if not prerequisites:
        REPORT.write_text(json.dumps({"status": "blocked", "validation": "unverified",
                                      "reason": "requires passing CI Android gate and Android SDK"}, indent=2) + "\n")
        print("HTTP source matrix: blocked/unverified (passing CI Android gate and SDK required)")
        return 0

    Fixture.requests = []
    server = HTTPServer(("127.0.0.1", 0), Fixture)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    component = HttpDataSourceComponent(server.server_port)
    checks = []
    try:
        status, _, body, error = component.get("/redirect")
        checks.append({"name": "bounded same-scheme redirect", "passed": status == 200 and body == b"progressive-media" and error is None})
        status, headers, body, error = component.get("/range", "bytes=2-5")
        checks.append({"name": "range request and seek", "passed": status == 206 and headers.get("Content-Range") == "bytes 2-5/10" and body == b"2345" and error is None})
        status, _, _, error = component.get("/range", "bytes=8-9")
        checks.append({"name": "unsupported range is actionable", "passed": status == 200 and error == "RANGE_UNSUPPORTED"})
        status, _, _, error = component.get("/auth")
        auth_count = sum(path == "/auth" for path, _ in Fixture.requests)
        checks.append({"name": "401 has no unauthorized retry", "passed": status == 401 and error == "AUTH_REQUIRED" and auth_count == 1})
        status, _, _, error = component.get("/forbidden")
        checks.append({"name": "403 requires authorization", "passed": status == 403 and error == "AUTH_REQUIRED"})
        status, _, _, error = component.get("/error")
        checks.append({"name": "5xx is actionable", "passed": status == 500 and error == "HTTP_ERROR"})
        component.get("/private")
        component.get("/private")
        private_count = sum(path == "/private" for path, _ in Fixture.requests)
        checks.append({"name": "private media cache miss has no implicit hit", "passed": private_count == 2 and "/private" not in component.cache})
        first = component.get("/cache")
        second = component.get("/cache")
        cache_requests = [headers for path, headers in Fixture.requests if path == "/cache"]
        checks.append({"name": "cache hit revalidates with ETag", "passed": first[0] == 200 and second[0] == 200 and second[2] == b"cacheable-media" and len(cache_requests) == 2 and cache_requests[1].get("If-None-Match") == '"fixture-v1"'})
    finally:
        server.shutdown()
        thread.join(timeout=3)

    passed = all(check["passed"] for check in checks)
    report = {"status": "verified" if passed else "failed", "validation": "ci-fake-server-data-source-component",
              "checks": checks, "device_playback": "unverified"}
    REPORT.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
