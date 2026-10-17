"""
FLASH PULSE - Python Companion Server
Optional lightweight server runner if running without Java WebServer.
"""
import http.server
import socketserver
import json
import os
import sys

PORT = 8080
DIRECTORY = os.path.join(os.path.dirname(__file__), "public")

class FlashPulseHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)

    def do_GET(self):
        if self.path == "/api/status":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            data = {
                "status": "ONLINE",
                "engine": "Python Companion Runner",
                "mysqlActive": False,
                "uptimeSeconds": 10,
                "totalProducts": 3,
                "totalPurchases": 0,
                "version": "3.13"
            }
            self.wfile.write(json.dumps(data).encode("utf-8"))
        else:
            super().do_GET()

def main():
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    print("=================================================")
    print("   FLASH PULSE - PYTHON RUNNER")
    print("=================================================")
    print(f" UI URL: http://localhost:{PORT}")
    print("=================================================")
    with socketserver.TCPServer(("", PORT), FlashPulseHandler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down server.")

if __name__ == "__main__":
    main()
