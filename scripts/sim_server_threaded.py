#!/usr/bin/env python3
"""
Threaded launcher for the (verbatim-from-v1) sim_server.py -- leaves sim_server.py itself untouched.

sim_server.py uses the single-threaded http.server.HTTPServer. Chrome opens speculative "preconnect" sockets and may
send nothing on them; a single-threaded server then blocks reading that socket and every other request (the viewer
page, /api/runs, frames) hangs. This wrapper swaps in ThreadingHTTPServer before sim_server.py runs, so one idle
connection can no longer stall the rest.

Usage (same arguments as sim_server.py), e.g. from ~/Code:
    python3 SoftBox/scripts/sim_server_threaded.py 8000
"""
import http.server, os, runpy, sys

http.server.HTTPServer = http.server.ThreadingHTTPServer
http.server.ThreadingHTTPServer.daemon_threads = True
target = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'sim_server.py')
sys.argv = [target] + sys.argv[1:]
runpy.run_path(target, run_name='__main__')
