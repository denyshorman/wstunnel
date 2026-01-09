# WSTunnel
A tool for TCP tunneling over WebSockets with integrated in-memory SSH and bidirectional port forwarding.

## Overview
WSTunnel provides a robust way to tunnel TCP services through restricted network environments using the WebSocket protocol.

- **Network Reach-through**: Project local assets through public WebSocket gateways, bypassing NAT and complex firewall topologies.
- **Secure Command Execution**: Execute authenticated remote operations over WebSocket transport via integrated SSHD.
- **Bidirectional Parity**: High-performance port forwarding for both inbound and outbound traffic.
- **Efficient Concurrency**: Built with Kotlin Coroutines for maximum throughput with minimal resource overhead.

## Architecture
```
┌─────────────┐         WebSocket          ┌─────────────┐
│   Client    │◄──────────────────────────►│   Server    │
│  (Behind    │   Tunnel Connection        │  (Public    │
│  Firewall)  │                            │   Access)   │
└─────────────┘                            └─────────────┘
      │                                            │
      │ Forward                              Listen│
      ▼                                            ▼
┌─────────────┐                            ┌─────────────┐
│   Local     │                            │   Remote    │
│   Service   │                            │   Client    │
└─────────────┘                            └─────────────┘
```

## Technical Specifications
- **WebSocket Transport**: Native bypass of HTTP proxies and stateful firewalls.
- **Integrated SSH Server**: Apache SSHD implementation supporting Interactive Shell, SFTP, SCP, and all standard SSH port forwarding modes.
- **Multiplexed Tunnels**: Handle unlimited concurrent connections over a single stream.
- **Resilient Connectivity**: Automatic reconnection logic for unstable network conditions.
- **Docker Ready**: Optimized for rapid deployment in containerized environments with non-root security defaults.

## Deployment

### Binary Compilation
Execute the build process to generate the application artifact.
```bash
git clone <repository-url>
cd wstunnel
./gradlew buildFatJar
```
The executable payload is located at: `build/libs/wstunnel.jar`.

### Container Orchestration
```bash
# Image Initialization
docker build -t wstunnel .

# Gateway Deployment
docker run -p 8080:8080 wstunnel server -p 8080

# Node Execution
docker run wstunnel client -S ws://your-server.com -l 8090 -f 3000
```

## Operations

### Server Mode: Gateway Initialization
Establish a public WebSocket gateway for incoming tunnel requests.
```bash
java -jar wstunnel.jar server [OPTIONS]
```
- `-p, --port`: Interface port (Default: 8080, Env: `WSTUN_SERVER_PORT`).
- `-a, --host`: Binding address (Default: 0.0.0.0, Env: `WSTUN_SERVER_HOST`).

### Client Mode: Tunnel Establishment
Initialize the tunnel and establish connectivity to the gateway.
```bash
java -jar wstunnel.jar client -S <SERVER_URL> [TUNNEL_OPTIONS]
```
**Required Directive:**
- `-S, --serverUrl`: Gateway URL (ws:// or wss://).

**Tunnel Configuration:**
- `-l, --listen`: Listen mode `port[;host[;id]]`.
- `-f, --forward`: Forward mode `port[;host[;id]]`.
- `--lsshd`: Initialize SSH server `[port[;host[;id]]]`.

---

## Usage Scenarios

### 1. Service Projection (Web Service)
Project an internal web service (port 3000) through a public gateway.
```bash
# Gateway Node
java -jar wstunnel.jar server -p 8080

# Internal Node
java -jar wstunnel.jar client -S ws://gateway.com:8080 -l 8090 -f 3000
```

### 2. Secure Remote Access (SSH)
Establish an authenticated entry point behind a restrictive firewall.
```bash
java -jar wstunnel.jar client \
  -S ws://gateway.com \
  --lsshd 2222 \
  --sshd-login admin \
  --sshd-password secret
```

---

## Integrated SSH Server Features

Standard SSH tunneling is often restricted by network security policies or requires complex infrastructure. WSTunnel's **Integrated SSHD** flips the status:
1. **Client-Side Execution**: The SSH server runs within your protected environment. No public SSH daemon required.
2. **Tunnel Encapsulation**: SSH traffic is transparently carried over WebSocket.
3. **Runtime Credentials**: Define access via CLI flags. Eliminate `authorized_keys` management.
4. **Dynamic Multiplexing**: Use native SSH capabilities (`-D`, `-L`, `-R`) over the WebSocket stream.

### Case Study: Dynamic Network Proxy
```bash
# Target Node (Internal Network)
java -jar wstunnel.jar client -S ws://gateway.com --lsshd "0;127.0.0.1;target-id"

# Operator Node
java -jar wstunnel.jar client -S ws://gateway.com -f "4444;127.0.0.1;target-id"

# Establish SOCKS5 Proxy
ssh -D 1080 -N user@localhost -p 4444
```
You now have a dynamic SOCKS5 gateway into the target network.

---

## Operational Parameters

### SSH Authentication Matrix
| Credential Configuration | Behavioral Outcome |
|:---|:---|
| None Provided | Unrestricted access (Open) |
| Login Only | Username verification; any password accepted |
| Password Only | Password verification; any username accepted |
| Full Set | Strict Username and Password verification |

### Environment Variables
| Variable | Description |
|:---|:---|
| `WSTUN_SERVER_PORT` | Gateway Port (Default: 8080) |
| `WSTUN_SERVER_HOST` | Gateway Host (Default: 0.0.0.0) |
| `WSTUN_SERVER_URL` | Target Server URL |
| `WSTUN_LISTEN_SSHD` | SSHD Configuration (port[;host[;id]]) |
| `WSTUN_SSHD_LOGIN` | SSHD Username |
| `WSTUN_SSHD_PASSWORD` | SSHD Password |

---

## Security Protocol & Known Constraints

### ⚠️ Critical Deployment Notice
WSTunnel is in active development. Observe the following operational boundaries:

1. **Authentication Gap**: The gateway server currently lacks a native auth layer. Access is open to any node with the URL.
2. **Ephemeral Host Keys**: SSH host keys regenerate on restart. This triggers host identification warnings.
3. **Transport Protocol**: Always utilize `wss://` for production environments to ensure TLS encryption.
4. **External Rate Limiting**: Internal throttling is not yet implemented. Use external load balancers for protection.

---

## Development & Licensing
- **Build Strategy**: `./gradlew buildFatJar`
- **Verification**: `./gradlew test`
- **License**: MIT