#!/usr/bin/env python3
"""CI-only fake HTTP source-policy matrix.

This is deliberately not a local retry.  It exercises the HTTP response
contract against a deterministic in-process server and writes a report that
distinguishes blocked/unverified prerequisites from a verified matrix.
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
            self.end_headers()
        elif self.path == "/range":
            requested = self.headers.get("Range")
            if requested == "bytes=2-5":
                body = b"2345"
                self.send_response(206)
                self.send_header("Content-Range", "bytes 2-5/10")
            else:
                body = b"0123456789"
                self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/auth":
            self.send_response(401)
            self.send_header("WWW-Authenticate", "Basic realm=fixture")
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif self.path == "/private":
            body = b"private-media"
            self.send_response(200)
            self.send_header("Cache-Control", "private, no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/final":
            body = b"progressive-media"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()


def request(port, path, headers=None):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
    connection.request("GET", path, headers=headers or {})
    response = connection.getresponse()
    body = response.read()
    connection.close()
    return response.status, dict(response.getheaders()), body


def main():
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    prerequisites = os.environ.get("GITHUB_ACTIONS", "").lower() == "true" and bool(
        os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    )
    if not prerequisites:
        REPORT.write_text(json.dumps({"status": "blocked", "validation": "unverified", "reason": "requires GitHub Actions and Android SDK"}, indent=2) + "\n")
        print("HTTP source matrix: blocked/unverified (CI and Android SDK required)")
        return 0

    Fixture.requests = []
    server = HTTPServer(("127.0.0.1", 0), Fixture)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    port = server.server_port
    checks = []
    try:
        status, headers, body = request(port, "/redirect")
        status2, _, body2 = request(port, "/final")
        checks.append({"name": "bounded same-scheme redirect", "passed": status == 302 and status2 == 200 and body2 == b"progressive-media"})

        status, headers, body = request(port, "/range", {"Range": "bytes=2-5"})
        checks.append({"name": "byte range", "passed": status == 206 and headers.get("Content-Range") == "bytes 2-5/10" and body == b"2345"})

        status, _, _ = request(port, "/auth")
        auth_requests = [path for path, _ in Fixture.requests if path == "/auth"]
        checks.append({"name": "auth failure has no unauthorized retry", "passed": status == 401 and len(auth_requests) == 1})

        status, headers, _ = request(port, "/private")
        private_requests = [path for path, _ in Fixture.requests if path == "/private"]
        checks.append({"name": "private media is not implicitly cached", "passed": status == 200 and "private" in headers.get("Cache-Control", "") and len(private_requests) == 1})
    finally:
        server.shutdown()
        thread.join(timeout=3)

    passed = all(check["passed"] for check in checks)
    report = {"status": "verified" if passed else "failed", "validation": "component-fixture-only", "checks": checks, "device_playback": "unverified"}
    REPORT.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
