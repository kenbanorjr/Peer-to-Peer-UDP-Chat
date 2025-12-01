# Distributed UDP Chat Architecture

## Goals
- Every node is a peer: it can send/receive chat data without central servers.
- Communication uses UDP datagrams for all control and chat payloads.
- Nodes keep a consistent ordered log of chat messages and can recover history on join.
- The middleware handles membership, ordering, retransmission, and failure simulation; no third-party messaging frameworks are used.

## High-Level Components
| Component | Responsibility |
|-----------|----------------|
| `ChatNode` (entry point) | Parses CLI flags, coordinates the networking subsystem, user console, and automation features. |
| `NodeConfig` | Immutable configuration derived from CLI (nicknames, ports, peer seeds, drop rates, robot options). |
| `PeerRegistry` | Tracks known peers (address, port, last heartbeat, status) and shares peer knowledge for discovery. |
| `MessageStore` | Maintains the ordered chat log using Lamport clocks `(logicalTime, nodeId)` and deduplicates by message id. |
| `LamportClock` | Provides monotonically increasing logical timestamps for ordering distributed events. |
| `PacketCodec` | Serialises/deserialises control/chat packets into compact pipe-delimited UTF-8 frames that survive UDP datagram boundaries. |
| `DatagramService` | Listens for UDP packets on the node’s port, dispatches them to the `MessageRouter`, and sends packets to peers. |
| `MessageRouter` | Validates incoming packets, applies state changes (join/leave/sync/chat), queues responses, and guards against malformed data. |
| `HistorySyncService` | Produces full snapshots for new peers, chunks oversized histories, and rebuilds local history when requested. |
| `RobotSpeaker` | Optional automation loop that injects synthetic chat payloads at a configurable cadence. |
| `DropSimulator` | Applies probabilistic dropping of inbound/outbound packets to mimic lossy network links. |

## Packet Types
All packets share a simple frame: `TYPE|nodeId|payload...`. Payload elements encode ASCII tokens; user-supplied strings (nicknames, chat text) are Base64 encoded.

| Type | Purpose |
|------|---------|
| `HELLO` | Initial handshake broadcast; carries `nickname`, `listenPort`, `lamport`. |
| `WELCOME` | Acknowledges `HELLO`, returns peer list seed and confirms mutual awareness. |
| `CHAT` | User/robot chat message with `messageId`, Lamport clock, timestamp, nickname, and Base64 text. |
| `HISTORY_REQ` / `HISTORY_RES` | Request/response to rebuild chat history when joining or after local reset. |
| `PEERS` | Optional share of currently known peers for discovery redundancy. |
| `LEAVE` | Explicit notice that a node is disconnecting cleanly. |
| `HEARTBEAT` | Lightweight keep-alive to detect failed nodes. |
| `RESEND_REQ` | On detecting a gap in message IDs, ask peers for specific message ids (supports “missing data” recovery). |
| `FILE_META` / `FILE_CHUNK` | File metadata + chunk payloads (Base64) used for binary transfer and reconstruction. |
| `DISCOVER` / `DISCOVER_RES` | Broadcast discovery beacon + response so nodes can find the network without seed addresses. |

Unknown or malformed packets are logged and ignored to satisfy the “handle malformed data gracefully” requirement.

## Ordering and Uniqueness
- Every node owns a UUID `nodeId`.
- Lamport timestamps are incremented on *send* and raised on *receive*.
- Message ids are `nodeId:lamport`.
- The `MessageStore` keeps a `TreeSet` ordered by `(lamport, nodeId, messageId)` which guarantees a deterministic total order across all nodes, satisfying “complete accurate ordering”.
- `HISTORY_RES` transmits a JSON-less but deterministic list of message frames; the receiver feeds them through the same ingestion logic ensuring dedupe.

## Membership & Discovery
1. User supplies one or more `--peer host:port` seeds. Absent seeds, the node forms a single-node network.
2. On start, the node sends `HELLO` to all seeds and awaits `WELCOME`.
3. `WELCOME` and `PEERS` messages merge peer knowledge, allowing the node to discover additional peers indirectly (requirement “support discovery of nodes from other nodes”).
4. Heartbeats run every 2 seconds; peers missing for configurable timeout (default 10s) are marked offline and removed.
5. A LAN-wide `DiscoveryService` listens on `--discover-port` (default 57500). Nodes that start without seeds broadcast `DISCOVER` probes to `255.255.255.255`; any existing peer replies with `DISCOVER_RES` carrying its listening port so the new node can immediately send a standard `HELLO`.

## History & Recovery
- Joining nodes issue `HISTORY_REQ` to the first responsive peer; peers respond with the entire ordered chat log (chunked if necessary).
- Users can type `/sync` to request history at any time, `/clear` to wipe local logs, followed by automatic `/sync` to rebuild from the network (hits “clear & rebuild” requirement).
- If a `CHAT` is received with an unseen predecessor gap, the node fires `RESEND_REQ` to peers; peers reply with any matching messages, enabling “discover and obtain missing data”.

## Failure Handling
- Drop simulator: users configure `--drop-in=0.1` `--drop-out=0.2` or toggle interactively via `/drop in 0.25`. The `DatagramService` consults the simulator before sending/after receiving.
- Unexpected peer failure: absence of heartbeat triggers a log entry and removal, but the chat log remains intact.
- Graceful exit: `/quit` or Ctrl+C sends `LEAVE` and flushes history.
- Malformed packets: `PacketCodec` throws parsing errors, captured by the router which logs the offending peer and moves on.

## Console Commands
All commands start with `/`.
- `/peers` – list known peers and their status.
- `/history` – dump ordered chat log.
- `/sync` – request full history from peers.
- `/clear` – clear local log then `/sync`.
- `/robot start <seconds>` / `/robot stop` – start/stop automated chatter.
- `/drop in|out <ratio>` – adjust simulated failure rates.
- `/nick <new>` – change nickname (broadcasted).
- `/quit` – graceful shutdown.
Anything else is treated as chat text.

## External Interface Hooks
- `--http-port <n>` spins up a lightweight `HttpServer` that exposes:
  - `POST /chat` to publish chat lines from bots or other services (supports `text` + optional `nick` parameters).
  - `GET /history` streaming the Lamport-ordered chat log as JSON.
  - `GET /health` returning node id, nickname, peer count, and message count for monitoring.
- `--log <path>` still mirrors every chat line to disk for ingestion by other tools.

## Binary File Transfer
- `/sendfile <path>` reads the file, emits a `FILE_META` packet and Base64 `FILE_CHUNK`s (6 KB each) to every peer over UDP.
- Receivers buffer chunks in-memory and flush the reconstructed bytes to the configurable `--downloads` directory, ensuring unique filenames if collisions occur.
- Transfers ride on the existing Lamport/UDP infrastructure, so they benefit from peer-to-peer forwarding and failure simulation too.

## Wow Factor Ideas
- Deterministic ordering with Lamport clocks, not simply local append.
- Automated robot, drop simulator, missing-data replay, and local reset features.
- Pure peer-to-peer UDP middleware without relying on third-party messaging stacks.
