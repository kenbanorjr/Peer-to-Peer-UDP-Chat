# CSC4010 Distributed UDP Chat

Peer-to-peer chat node implemented in Java for the CSC4010 assessment. Each running instance is a fully-fledged peer that uses UDP for all coordination: membership, messaging, history replay, and recovery.

## Features
- Room-scoped chat: every message, history sync, heartbeat, and peer list is tagged with a room so multiple rooms can coexist; switch via `/room <name>` or `--room <name>` (default `lobby`).
- Pure UDP middleware: every control + chat payload is a UDP datagram (no MQ/RMI/etc).
- Friendly nicknames plus UUID-backed unique node identifiers.
- Lamport logical clocks ensure a deterministic ordering of chat items across peers.
- New peers discover existing members via handshakes, peer-list gossip, and optional directory sharing.
- Automatic history synchronisation for joining peers and manual `/sync` + `/clear` commands.
- Robot chatter generator (`/robot start <seconds>`) for demo data.
- Malformed packet handling and a configurable drop simulator (`/drop in|out <rate>`) to mimic unreliable networks.
- Missing-data recovery with `/forget` + `/resend <messageId>`.
- Transcript export to an external file (optional `--log path` flag) for integrations.
- Zero-config LAN discovery: nodes broadcast/answer discovery beacons so you can join without pre-configured peers.
- Optional HTTP API for posting chat messages from external services and pulling history/health data.
- Binary file transfer support with chunked UDP packets saved automatically to a downloads directory.

## Project Layout
```
src/main/java/csc4010/chat/
  ChatNode.java              # main peer process + console + router
  NodeConfig.java            # CLI parsing + configuration
  LamportClock.java          # logical clock helper
  ChatMessage.java           # immutable chat payload
  MessageStore.java          # ordered chat log
  PeerInfo.java / PeerRegistry.java
  MessageType.java / Packet.java / PacketCodec.java
  DropSimulator.java / DatagramService.java
docs/ARCHITECTURE.md         # deep dive on the protocol and middleware design
README.md                    # this file
```

## Requirements
- **Java 17+** (tested with latest GA OpenJDK)

## Building

### Manual Build
Use `javac` with Java 21 (no external dependencies):

**Bash (Git Bash/WSL/Linux/macOS):**
```bash
cd CSC4010-Distributed-Computing
mkdir -p bin
find src/main/java -name '*.java' -print0 | xargs -0 javac --release 17 -d bin
```

**PowerShell:**
```powershell
cd CSC4010-Distributed-Computing
mkdir bin -Force
$sources = Get-ChildItem -Recurse src/main/java -Filter *.java | % { $_.FullName }
javac --release 17 -d bin $sources
```

This produces class files in `bin`. Run the node with:

```powershell
cd CSC4010-Distributed-Computing
java -cp bin csc4010.chat.ChatNode --port 5000 --nick Alice
```

There is no Maven/Gradle build file in this repo—compile with the `javac` command above whenever you change the sources (or script it however you prefer).

## Running Multiple Peers
1. Start the first node (no peers):
   ```powershell
   java -cp bin csc4010.chat.ChatNode --port 5000 --nick Alice
   ```
2. Start a second node and point it at Alice:
   ```powershell
   java -cp bin csc4010.chat.ChatNode --port 5001 --nick Bob --peer localhost:5000
   ```
3. Additional peers can list multiple `--peer host:port` seeds. Each peer shares its known peers via `PEERS` packets, so one seed is enough after the first hop.
4. To join a different room: add `--room myroom` on startup or type `/room myroom` after launch (peers only exchange data within the same room).

Optional flags:
- `--drop-in 0.2 --drop-out 0.1` simulate lossy inbound/outbound links.
- `--robot 5` start the robot chatterer upon launch (message every 5 seconds).
- `--log logs/transcript.txt` append the ordered chat log to a file for external integrations.
- `--http-port 8080` expose an HTTP API (`POST /chat`, `GET /history`, `GET /health`).
- `--port auto` (or `--port 0`) lets the OS pick a free UDP port automatically—handy on lab machines where 5000 is busy. The node logs the actual port it binds to.
- `--discover-port 57500` adjust the UDP discovery beacon port (use `--no-discovery` to disable). If a port is taken the node will automatically try the next few values and warn you.
- `--downloads path\to\folder` change where incoming binary files are saved (default `downloads/`).

## Console Commands
| Command | Description |
|---------|-------------|
| `/help` | List commands |
| `/peers` | Show known peers, IDs, and status |
| `/history` | Print local ordered log |
| `/room <name>` | Switch active room (sends leave/hello, resyncs history) |
| `/sync` | Request full history from a peer |
| `/clear` | Drop local log then `/sync` automatically |
| `/robot start <sec>` / `/robot stop` | Control the robot chat generator |
| `/drop in|out <rate>` | Adjust simulated loss probabilities (0-1) |
| `/nick <name>` | Change nickname and broadcast to peers |
| `/sendfile <path>` | Send a binary file to all peers (saved under `downloads/` on receipt) |
| `/discover` | Broadcast a new discovery probe (useful when joining with zero configuration) |
| `/resend <messageId>` | Ask peers to resend a specific chat item |
| `/forget <messageId>` | Remove a message locally (to simulate missing data) |
| `/quit` | Gracefully disconnect (broadcasts `LEAVE`) |

## Simulating Failures
- Use `/drop in 0.25` or CLI `--drop-in/--drop-out` to probabilistically discard packets.
- `/forget <messageId>` removes a message locally and `/resend` pulls it back via `RESEND_REQ/RESEND_RES`.
- `/clear` wipes the local log to demonstrate full history rebuilds.
- `/sendfile` chunks a local binary (default 6 KB per packet) and streams it to peers. Received files land in the `downloads/` directory with automatic name deconfliction.

## HTTP External Interface
- Enable with `--http-port <port>`.
- `POST /chat`  
  - Body: either raw text or `text=hello&nick=Bot`.  
  - Optional `nick` query/body parameter overrides the displayed nickname.  
  - Successful posts return `200 OK`.
- `GET /history` returns a JSON array of the current ordered chat log.
- `GET /health` returns a JSON object with node id, nickname, peer count, and message count.
This allows bots, scripts, or other applications to publish chat lines or poll node status without a console.

### Swing GUI (Wow-factor add-on)
If you want a lightweight GUI on top of the HTTP API, launch a node with the HTTP endpoint enabled and start the `ChatGuiApp`:

```powershell
java -cp bin csc4010.chat.ChatNode --port 5000 --nick Alice --http-port 8080
java -cp bin csc4010.chat.ChatGuiApp --server http://localhost:8080 --nick Alice-GUI
```

The GUI polls `/history` every couple of seconds, streams all Lamport-ordered chat into a scrolling window, and lets you post chat lines (optionally under a custom nickname) via `POST /chat`. Use `--refresh 1` to poll more aggressively or point `--server` at any other peer exposing the HTTP API.

## Discovery Without Seeds
- Nodes listen on UDP discovery port `57500` (change with `--discover-port`).  
- When started without `--peer`, they automatically broadcast probes and connect to any responders.  
- Use `/discover` to manually re-run the probe; disable with `--no-discovery` if you need to isolate a node.
- If the discovery port is already in use on your machine, the node will log
  `Discovery disabled: failed to bind...`. Free the conflicting process (or pick a new port via `--discover-port <n>`) so you can still demonstrate auto-discovery for assessment credit.

## Notes for the Report
- `docs/ARCHITECTURE.md` documents the middleware protocol, message types, and wow-factor elements.
- Highlight Lamport ordering, room-scoped messaging/history, peer discovery, loss simulation, and transcript export under “design” and “wow” sections.
