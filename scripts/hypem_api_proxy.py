#!/usr/bin/env python3
from __future__ import annotations

import http.server
import socketserver
import urllib.error
import urllib.parse
import urllib.request

UPSTREAM = "https://api.hypem.com"
HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",
    "host",
}


class ProxyHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:
        self._forward()

    def do_POST(self) -> None:
        self._forward()

    def do_PUT(self) -> None:
        self._forward()

    def do_PATCH(self) -> None:
        self._forward()

    def do_DELETE(self) -> None:
        self._forward()

    def _forward(self) -> None:
        target = urllib.parse.urljoin(UPSTREAM, self.path)
        body = None
        if self.command in {"POST", "PUT", "PATCH"}:
            length = int(self.headers.get("Content-Length", "0") or "0")
            body = self.rfile.read(length) if length else None

        request = urllib.request.Request(target, data=body, method=self.command)
        for name, value in self.headers.items():
            if name.lower() not in HOP_BY_HOP:
                request.add_header(name, value)
        request.add_header("Host", "api.hypem.com")

        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = response.read()
                self.send_response(response.status)
                for name, value in response.headers.items():
                    if name.lower() not in HOP_BY_HOP:
                        self.send_header(name, value)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
        except urllib.error.HTTPError as error:
            payload = error.read()
            self.send_response(error.code)
            for name, value in error.headers.items():
                if name.lower() not in HOP_BY_HOP:
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"{self.command} {self.path} - " + (fmt % args))


def main() -> None:
    with socketserver.ThreadingTCPServer(("0.0.0.0", 8787), ProxyHandler) as server:
        server.daemon_threads = True
        print("Hype API proxy listening on http://0.0.0.0:8787")
        server.serve_forever()


if __name__ == "__main__":
    main()
