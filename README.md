# CSC4010 Distributed UDP Chat

Peer-to-peer chat nodes implemented in Java. Every instance is a full peer that exchanges membership, chat, history replay, recovery, file transfer, and control messages over UDP. An optional HTTP API and Swing GUI ride on top.

## Features
- Room-scoped messaging (default `lobby`) with Lamport clocks for deterministic ordering.
- Gossip-based membership plus zero-config LAN discovery; peers share their peer lists.
- History sync on join and manual `/sync` + `/clear`; resend missing IDs with `/resend`.
- Optional HTTP API for bots/integrations and a Swing GUI that speaks to it.
- Binary file transfer over UDP with chunking and automatic downloads directory.
- Drop simulator (`--drop-in/--drop-out`), TTL + fan-out limits for scalable relays, and robot chat generator.
- Transcript logging to disk, resilient retries/acks for chats, and room switching without restart.

## Project Layout
```
src/main/java/csc4010/chat/
  ChatNode.java              # main peer, console shell, router
  NodeConfig.java            # CLI parsing + immutable config
  ChatGuiApp.java            # Swing GUI for the HTTP API
  DiscoveryService.java      # LAN probes/answers
  DatagramService.java       # UDP socket wrapper + drop simulation
  Packet / PacketCodec       # UDP packet helpers
  MessageStore / ChatMessage # ordered log + payload
  LamportClock.java          # logical clock
  PeerRegistry.java          # peer tracking by room/id
  FileTransferManager.java   # chunked file send/receive
docs/ARCHITECTURE.md         # protocol/design deep dive
README.md                    # you are here
```

## Requirements
- Java 17+ (tested on OpenJDK 17/21)

## Build
No Maven/Gradle; compile with `javac`:

**PowerShell (Windows):**
```powershell
cd Peer-to-Peer-UDP-Chat
mkdir bin -Force
$sources = Get-ChildItem -Recurse src/main/java -Filter *.java | % { $_.FullName }
javac --release 17 -d bin $sources
```

**Bash (Git Bash/WSL/macOS/Linux):**
```bash
cd Peer-to-Peer-UDP-Chat
mkdir -p bin
find src/main/java -name '*.java' -print0 | xargs -0 javac --release 17 -d bin
```

## Run a Node (Console)
```powershell
java -cp bin csc4010.chat.ChatNode --port 5000 --nick Alice
```

Start a second peer and point it at the first:
```powershell
java -cp bin csc4010.chat.ChatNode --port 5001 --nick Bob --peer localhost:5000
```

Room switch: `--room myroom` on startup or `/room myroom` at runtime (peers only interact within the same room).

### CLI Flags (ChatNode)
- `--nick <name>` nickname (default Peer-XXXX)
- `--port <n|auto|0>` UDP listen port (0/auto = OS chooses)
- `--peer host:port` seed peer (repeatable)
- `--room <name>` chat room (default lobby)
- `--drop-in <0-1>` / `--drop-out <0-1>` simulate inbound/outbound loss
- `--fanout <n>` limit broadcast fan-out per relay (0 = send to all)
- `--ttl <n>` hop limit for relayed chats/history (0 = unlimited)
- `--history-page <n>` page size for history replay (default 64)
- `--log <path>` append ordered transcript to file
- `--robot <seconds>` start robot chatterer on launch
- `--lifetime <seconds>` auto-shutdown after given time
- `--no-discovery` disable LAN discovery (default on)
- `--discover-port <n>` UDP discovery port (default 57500)
- `--http-port <n>` start HTTP API (0 = auto-pick)
- `--downloads <dir>` where incoming files are saved (default `downloads/`)
- `--gui` launch Swing GUI after HTTP starts (auto-picks HTTP port if omitted)
- `--headless` / `--console` force console on/off
- `--help` print usage

### Console Commands (after node starts)
| Command | Description |
|---------|-------------|
| `/help` | List commands |
| `/peers` | Show known peers/rooms |
| `/history` | Print local ordered log |
| `/room <name>` | Switch room (leave/hello + history resync) |
| `/sync` | Request full history |
| `/clear` | Drop local log then resync |
| `/robot start <sec>` / `/robot stop` | Control robot chatter |
| `/drop in|out <rate>` | Adjust simulated loss (0-1) |
| `/nick <name>` | Change nickname and announce |
| `/sendfile <path>` | Send a binary file to peers (saved under downloads) |
| `/discover` | Broadcast discovery probe |
| `/resend <messageId>` | Ask peers for a specific message |
| `/forget <messageId>` | Remove a message locally |
| `/quit` | Gracefully disconnect |

### Scalability Controls
- **Fan-out**: `--fanout N` randomly gossips to N peers per broadcast instead of all.
- **TTL**: `--ttl N` caps relay depth (0 = unlimited).
- **Drops**: `--drop-in/out` simulate lossy networks.
- **History paging**: `--history-page` throttles replay bursts.

### File Transfer
- `/sendfile <path>` or HTTP control `sendfile` streams chunks over UDP.
- Files land in `downloads/` (or `--downloads <dir>`) with collision-safe names.

## HTTP API (enable with `--http-port <port|0>`)
- `POST /chat` body `text=hello&nick=Bot` (form) or raw text. Returns 200 on success.
- `GET /history` JSON array of ordered chat log.
- `GET /health` JSON: node id, nickname, peer count, message count.
- `POST /control` form actions:
  - `room name=<room>`; `sync`; `clear`; `robot_start seconds=<n>`; `robot_stop`
  - `drop_in rate=<0-1>`; `drop_out rate=<0-1>`
  - `nick value=<name>`; `sendfile path=<file>`; `discover`
  - `resend ids=<id1,id2>`; `forget id=<id>`; `quit`

## Swing GUI (ChatGuiApp)
- Auto-launch with node flag: `--gui` (HTTP auto-picks a port if needed).
- Or run separately against a node exposing HTTP:
  ```powershell
  java -cp bin csc4010.chat.ChatNode --port 5000 --nick Alice --http-port 8080
  java -cp bin csc4010.chat.ChatGuiApp --server http://localhost:8080 --nick Alice-GUI --refresh 2
  ```
- GUI polls `/history`, posts via `/chat`, and issues `/control` actions (room switch, drop rates, robot, resend/forget, sendfile, quit).

## Discovery (no seeds required)
- Nodes probe UDP `--discover-port` (default 57500) and answer. Start without `--peer` and they will find each other on the LAN.
- `/discover` forces a new probe. Use `--no-discovery` to isolate.

## Testing Ideas / Demos
- Start 3 peers with `--fanout 2 --ttl 3` to show scalable gossip.
- Toggle `/drop in 0.3` and watch resend/history recovery.
- `/clear` + `/sync` to demonstrate full history rebuild.
- Send a file and verify it appears under `downloads/` on other peers.
