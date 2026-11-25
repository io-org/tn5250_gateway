#!/usr/bin/env python3

import asyncio
import json
import sys

# Hardcoded time delays (in seconds) to emulate busy work
SETUP_DELAY = 0.5
CONNECT_DELAY = 1.0
SIGN_IN_DELAY = 2.0

# Hardcoded verification data
ALLOWED_IPS = ["127.0.0.1"]
ALLOWED_USERS = ["BOB", "ALICE", "MARY"]

# Hardcoded setup response payload
SETUP_RESPONSE = {
    "proxy_port": 2323,
    "proxy_tls": False,
    "as400_host": "pub400.com",
    "as400_port": 992,
    "as400_tls": True,
    "max_verification_timeout_minutes": 5,
    "sign_in_screen": [
        {"value": "Your user name:", "row": 5, "col": 1, "name": None},
        {"value": "Password (max. 128):", "row": 6, "col": 1, "name": None},
        {"value": None, "row": 5, "col": 25, "name": "username"},
    ],
    "screens_dir": "/tmp/screens",
    "access_log_file": "/tmp/logs/mylog.ndjson",
}

write_lock = asyncio.Lock()


async def send_response(data: dict) -> None:
    """Safely writes a single-line JSON response to stdout and flushes immediately."""
    line = json.dumps(data) + "\n"
    async with write_lock:
        sys.stdout.write(line)
        sys.stdout.flush()


async def handle_request(request: dict) -> None:
    """Processes an individual request asynchronously based on its type."""
    req_type = request.get("type")

    if req_type == "setup":
        await asyncio.sleep(SETUP_DELAY)
        await send_response(SETUP_RESPONSE)

    elif req_type == "connect":
        await asyncio.sleep(CONNECT_DELAY)
        is_allowed = request.get("tn5250_ip") in ALLOWED_IPS
        response = {
            "connection_id": request.get("connection_id"),
            "type": "connect",
            "allow": is_allowed,
        }
        await send_response(response)

    elif req_type == "sign_in":
        await asyncio.sleep(SIGN_IN_DELAY)
        fields = request.get("fields") or {}
        username = fields.get("username", "").upper()
        is_allowed = username in ALLOWED_USERS
        response = {
            "connection_id": request.get("connection_id"),
            "session_id": request.get("session_id"),
            "type": "sign_in",
            "allow": is_allowed,
        }
        await send_response(response)


async def read_stdin_forever() -> None:
    """Continuously reads single-line JSON requests from stdin without blocking."""
    loop = asyncio.get_running_loop()
    reader = asyncio.StreamReader()
    protocol = asyncio.StreamReaderProtocol(reader)
    await loop.connect_read_pipe(lambda: protocol, sys.stdin)

    while True:
        line = await reader.readline()
        if not line:
            # If stdin hits EOF, sleep briefly to keep running continuously as requested
            await asyncio.sleep(0.1)
            continue

        raw_text = line.decode("utf-8").strip()
        if not raw_text:
            continue

        try:
            request_data = json.loads(raw_text)
            asyncio.create_task(handle_request(request_data))
        except json.JSONDecodeError:
            pass


if __name__ == "__main__":
    try:
        asyncio.run(read_stdin_forever())
    except KeyboardInterrupt:
        pass

