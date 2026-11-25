# Generic TN5250 Gateway

[![License: MIT](https://shields.io)](https://opensource.org)

**Modern Authentication • Real-Time Monitoring • Auditing and Compliance**

A generic, lightweight gateway for IBM i that enables TN5250 connections with custom authentication, session management, SIEM, and audit integrations without modifying your native AS/400 configuration.

* **Flexible Authentication:** Enforce custom conditional access workflows, MFA, OIDC, and IdP (Okta, Duo, PingID, Entra ID, Keycloak, and more).
* **Real-Time Monitoring:** Stream authentication and session events directly to your SIEM.
* **Auditing and Compliance:** Record session screenshots directly to your own storage to meet strict regulatory standards.

---

## Features

1. **TN5250 Proxy:** Accept TN5250 connections and proxy them seamlessly to IBM i.
2. **TLS Support:** Enable encrypted connections for both the client-facing gateway and upstream TN5250 sessions.
3. **Session Lifecycle:** Track connection, sign-in state, and captured screens with your favorite third-party tools.
4. **Plain JSON I/O:** Exchange messages over `stdin` and `stdout`. Test the gateway without an integration program, and test the integration program without the gateway.

---

## Architecture

Drop the gateway between existing TN5250 clients and your IBM i host to enforce external verification logic without modifying legacy AS/400 configuration.

---

## Quickstart

### 1. Download or Build from Sources

Clone the repository and build the JAR using Maven:

```bash
git clone https://github.com/io-org/tn5250_gateway.git
cd tn5250_gateway
mvn package
```

*(Alternatively, download the latest release directly from the [GitHub repository](https://github.com/io-org/tn5250_gateway).)*

---

### 2. Start the Gateway Without an Integration Program (Manual Control)

Run the gateway directly and interact via JSON over `stdin`/`stdout`:

```bash
java -jar tn5250_gateway-1.0-SNAPSHOT.jar
```

#### Step 1: Initial Setup
![Setup Screen](docs/pub400.com_0.png)

**REQUEST:**
```json
{
  "type": "setup"
}
```

**RESPONSE:**
```json
{
  "proxy_port": 2323,
  "proxy_tls": false,
  "as400_host": "pub400.com",
  "as400_port": 992,
  "as400_tls": true,
  "max_verification_timeout_minutes": 5,
  "sign_in_screen": [
    { "value": "Your user name:", "row": 5, "col": 1, "name": null },
    { "value": "Password (max. 128):", "row": 6, "col": 1, "name": null },
    { "value": null, "row": 5, "col": 25, "name": "username" }
  ],
  "screens_dir": "/tmp/screens",
  "access_log_file": "/tmp/logs/mylog.ndjson"
}
```
> *Note: `screens_dir` and `access_log_file` are optional.*

![Setup Screen](pub400.com_0.png)

---

#### Step 2: Client Connection
**REQUEST:**
```json
{
  "connection_id": "7494af8a-6f8d-4bbc-a719-51482f25aec9",
  "type": "connect",
  "tn5250_ip": "127.0.0.1"
}
```

**RESPONSE:**
```json
{
  "connection_id": "7494af8a-6f8d-4bbc-a719-51482f25aec9",
  "type": "connect",
  "allow": true
}
```

![Connect Screen](pub400.com_1.png)

---

#### Step 3: User Sign-In
**REQUEST:**
```json
{
  "connection_id": "7494af8a-6f8d-4bbc-a719-51482f25aec9",
  "session_id": "3d44e9ed-4d0a-4f2e-88b7-2f0155a9db48",
  "type": "sign_in",
  "tn5250_ip": "127.0.0.1",
  "fields": { "username": "JOHN" }
}
```

**RESPONSE:**
```json
{
  "connection_id": "7494af8a-6f8d-4bbc-a719-51482f25aec9",
  "session_id": "3d44e9ed-4d0a-4f2e-88b7-2f0155a9db48",
  "type": "sign_in",
  "allow": true,
  "max_session_timeout_minutes": 30,
  "max_idle_timeout_minutes": 15
}
```
> *Note: `max_session_timeout_minutes` and `max_idle_timeout_minutes` are optional.*

![Sign-In Screen](pub400.com_2.png)

---

### 3. Run the Gateway Managed by a Custom Integration Program

To connect the gateway to a custom integration script (such as [`demo.py`](demo.py)) using a named pipe:

```bash
rm -f /tmp/loop_pipe && mkfifo /tmp/loop_pipe
java -jar tn5250_gateway-1.0-SNAPSHOT.jar < /tmp/loop_pipe | demo.py > /tmp/loop_pipe
rm /tmp/loop_pipe
```

---

## License

This project is licensed under the **MIT License**.

