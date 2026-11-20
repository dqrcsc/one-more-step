import http from "node:http";
import crypto from "node:crypto";

const WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
const ROOM_ID_RE = /^[A-Za-z0-9_-]{16,128}$/;
const MAX_ROOMS = 64;
const MAX_MESSAGE_BYTES = 18 * 1024 * 1024; // 18 MiB (> 16 MiB OMS frame + AEAD tag)
const MAX_SOCKET_BUFFER_BYTES = 32 * 1024 * 1024; // 32 MiB backpressure cap
const PING_INTERVAL_MS = 20_000;
const IDLE_TIMEOUT_MS = 65_000;

function sha256Buf(input) {
  return crypto.createHash("sha256").update(String(input), "utf8").digest();
}

export function verifyBearerToken(authHeader, expectedToken) {
  if (!expectedToken) return false;
  if (typeof authHeader !== "string" || !authHeader.startsWith("Bearer ")) return false;
  const provided = authHeader.slice(7).trim();
  if (!provided) return false;
  return crypto.timingSafeEqual(sha256Buf(provided), sha256Buf(expectedToken));
}

export class OmsWanRelayCore {
  constructor(authToken) {
    this.authToken = String(authToken || "").trim();
    if (this.authToken.length < 16) {
      throw new Error("oms-wan-relay requires an authToken of at least 16 characters");
    }
    this.startedAtMs = Date.now();
    this.rooms = new Map();
  }

  getHealthSnapshot() {
    let pairedRooms = 0;
    for (const r of this.rooms.values()) {
      if (r.host && r.phone && r.pairId) pairedRooms++;
    }
    return {
      ok: true,
      service: "oms-wan-relay",
      version: "1.0.0",
      activeRooms: this.rooms.size,
      pairedRooms,
      uptimeSec: Math.floor((Date.now() - this.startedAtMs) / 1000),
    };
  }

  validateUpgradeParams(pathname, authHeader, roomId, role) {
    if (pathname !== "/oms-wan/v1" && pathname !== "/v1" && pathname !== "/") {
      return { ok: false, status: 404, message: "Not Found" };
    }
    if (!verifyBearerToken(authHeader, this.authToken)) {
      return { ok: false, status: 401, message: "Unauthorized" };
    }
    if (!ROOM_ID_RE.test(roomId) || (role !== "host" && role !== "phone")) {
      return { ok: false, status: 400, message: "Bad Request" };
    }
    if (!this.rooms.has(roomId) && this.rooms.size >= MAX_ROOMS) {
      return { ok: false, status: 503, message: "Service Unavailable" };
    }
    return { ok: true };
  }

  detachPeer(room, peer, notifyOther = true) {
    if (!room || room[peer.role] !== peer) return;
    const oldPairId = room.pairId;
    room[peer.role] = null;
    room.pairId = null;
    const otherRole = peer.role === "host" ? "phone" : "host";
    const other = room[otherRole];
    if (notifyOther && other && !other.isClosed()) {
      other.sendJsonControl({
        omsRelay: 1,
        event: "peer_left",
        pairId: oldPairId,
        hostOnline: Boolean(room.host),
        phoneOnline: Boolean(room.phone),
      });
    }
    if (!room.host && !room.phone) {
      this.rooms.delete(peer.roomId);
    }
  }

  attachPeer(peer) {
    let room = this.rooms.get(peer.roomId);
    if (!room) {
      room = { host: null, phone: null, pairId: null };
      this.rooms.set(peer.roomId, room);
    }
    const previousSameRole = room[peer.role];
    if (previousSameRole && previousSameRole !== peer) {
      this.detachPeer(room, previousSameRole, true);
      previousSameRole.close(4009, "Replaced by newer session");
      if (!this.rooms.has(peer.roomId)) {
        room = { host: null, phone: null, pairId: null };
        this.rooms.set(peer.roomId, room);
      }
    }
    room[peer.role] = peer;
    if (!peer.isClosed()) {
      if (room.host && room.phone) {
        const pairId = crypto.randomUUID();
        room.pairId = pairId;
        const pairedEvent = {
          omsRelay: 1,
          event: "peer_paired",
          pairId,
          hostOnline: true,
          phoneOnline: true,
        };
        room.host.sendJsonControl(pairedEvent);
        room.phone.sendJsonControl(pairedEvent);
      } else {
        room.pairId = null;
        peer.sendJsonControl({
          omsRelay: 1,
          event: "waiting_for_peer",
          hostOnline: Boolean(room.host),
          phoneOnline: Boolean(room.phone),
        });
      }
    }
  }

  handleBinary(peer, payload) {
    const currentRoom = this.rooms.get(peer.roomId);
    if (!currentRoom || currentRoom[peer.role] !== peer || !currentRoom.pairId) return;
    const target = peer.role === "host" ? currentRoom.phone : currentRoom.host;
    if (target && !target.isClosed()) {
      target.sendBinary(payload);
    }
  }

  handleText(peer, rawText) {
    try {
      const msg = JSON.parse(rawText);
      if (msg && msg.type === "ping") {
        const room = this.rooms.get(peer.roomId);
        peer.sendJsonControl({
          omsRelay: 1,
          event: "pong",
          ts: msg.ts ?? Date.now(),
          hostOnline: Boolean(room?.host),
          phoneOnline: Boolean(room?.phone),
        });
      }
    } catch {
      // Ignore malformed client control JSON
    }
  }

  handleClose(peer) {
    const currentRoom = this.rooms.get(peer.roomId);
    if (currentRoom) {
      this.detachPeer(currentRoom, peer, true);
    }
  }
}

export function encodeWsFrame(opcode, payloadBuf) {
  const payload = Buffer.isBuffer(payloadBuf) ? payloadBuf : Buffer.from(payloadBuf);
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.allocUnsafe(2);
    header[0] = 0x80 | (opcode & 0x0f);
    header[1] = len;
  } else if (len < 65536) {
    header = Buffer.allocUnsafe(4);
    header[0] = 0x80 | (opcode & 0x0f);
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.allocUnsafe(10);
    header[0] = 0x80 | (opcode & 0x0f);
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  return len === 0 ? header : Buffer.concat([header, payload]);
}

function encodeClosePayload(code, reason = "") {
  const reasonBuf = Buffer.from(String(reason).slice(0, 90), "utf8");
  const buf = Buffer.allocUnsafe(2 + reasonBuf.length);
  buf.writeUInt16BE(code, 0);
  reasonBuf.copy(buf, 2);
  return buf;
}

class WsPeerConnection {
  constructor(socket, roomId, role, core) {
    this.socket = socket;
    this.roomId = roomId;
    this.role = role;
    this.core = core;
    this.closed = false;
    this.lastSeenMs = Date.now();
    this.rxBuf = Buffer.alloc(0);
    this.fragOpcode = 0;
    this.fragChunks = [];
    this.fragBytes = 0;

    socket.setNoDelay(true);
    socket.setKeepAlive(true, 15_000);

    socket.on("data", (chunk) => this.onData(chunk));
    socket.on("close", () => this.cleanup());
    socket.on("end", () => this.cleanup());
    socket.on("error", () => this.cleanup());
  }

  isClosed() {
    return this.closed || this.socket.destroyed;
  }

  sendJsonControl(obj) {
    if (this.isClosed()) return false;
    const data = Buffer.from(JSON.stringify(obj), "utf8");
    return this.writeRawFrame(0x1, data);
  }

  sendBinary(payload) {
    if (this.isClosed()) return false;
    if (this.socket.writableLength > MAX_SOCKET_BUFFER_BYTES) {
      this.close(1008, "Backpressure overflow");
      return false;
    }
    return this.writeRawFrame(0x2, payload);
  }

  sendPing() {
    if (this.isClosed()) return;
    const buf = Buffer.allocUnsafe(8);
    buf.writeBigUInt64BE(BigInt(Date.now()), 0);
    this.writeRawFrame(0x9, buf);
  }

  writeRawFrame(opcode, payload) {
    try {
      this.socket.write(encodeWsFrame(opcode, payload));
      return true;
    } catch {
      this.cleanup();
      return false;
    }
  }

  close(code = 1000, reason = "") {
    if (this.closed) return;
    this.closed = true;
    try {
      this.socket.write(encodeWsFrame(0x8, encodeClosePayload(code, reason)));
      this.socket.end();
      setTimeout(() => {
        if (!this.socket.destroyed) this.socket.destroy();
      }, 1500).unref?.();
    } catch {
      try {
        this.socket.destroy();
      } catch {}
    }
    this.core.handleClose(this);
  }

  cleanup() {
    if (this.closed) return;
    this.closed = true;
    this.fragChunks = [];
    this.rxBuf = Buffer.alloc(0);
    try {
      if (!this.socket.destroyed) this.socket.destroy();
    } catch {}
    this.core.handleClose(this);
  }

  onData(chunk) {
    if (this.closed) return;
    this.lastSeenMs = Date.now();
    this.rxBuf = this.rxBuf.length === 0 ? chunk : Buffer.concat([this.rxBuf, chunk]);

    while (!this.closed && this.rxBuf.length >= 2) {
      const b0 = this.rxBuf[0];
      const b1 = this.rxBuf[1];
      const fin = (b0 & 0x80) !== 0;
      const rsv = b0 & 0x70;
      const opcode = b0 & 0x0f;
      const masked = (b1 & 0x80) !== 0;
      let payloadLen = b1 & 0x7f;
      let offset = 2;

      if (rsv !== 0) {
        this.close(1002, "RSV bits not supported");
        return;
      }

      if (payloadLen === 126) {
        if (this.rxBuf.length < 4) return;
        payloadLen = this.rxBuf.readUInt16BE(2);
        offset = 4;
      } else if (payloadLen === 127) {
        if (this.rxBuf.length < 10) return;
        const bigLen = this.rxBuf.readBigUInt64BE(2);
        if (bigLen > BigInt(MAX_MESSAGE_BYTES)) {
          this.close(1009, "Frame exceeds maximum size");
          return;
        }
        payloadLen = Number(bigLen);
        offset = 10;
      }

      if (payloadLen > MAX_MESSAGE_BYTES) {
        this.close(1009, "Frame exceeds maximum size");
        return;
      }

      const maskBytes = masked ? 4 : 0;
      const totalFrameBytes = offset + maskBytes + payloadLen;
      if (this.rxBuf.length < totalFrameBytes) {
        return;
      }

      let payload = Buffer.from(this.rxBuf.subarray(offset + maskBytes, totalFrameBytes));
      if (masked) {
        const maskKey = this.rxBuf.subarray(offset, offset + 4);
        for (let i = 0; i < payload.length; i++) {
          payload[i] ^= maskKey[i & 3];
        }
      }
      this.rxBuf =
        this.rxBuf.length === totalFrameBytes
          ? Buffer.alloc(0)
          : Buffer.from(this.rxBuf.subarray(totalFrameBytes));

      if (opcode >= 0x8) {
        if (!fin || payload.length > 125) {
          this.close(1002, "Invalid control frame");
          return;
        }
        if (opcode === 0x8) {
          this.close(1000, "Normal closure");
          return;
        } else if (opcode === 0x9) {
          this.writeRawFrame(0x0a, payload);
        } else if (opcode === 0x0a) {
          // Pong handled via lastSeenMs
        } else {
          this.close(1002, "Unknown control opcode");
          return;
        }
        continue;
      }

      if (opcode === 0x0) {
        if (this.fragOpcode === 0) {
          this.close(1002, "Unexpected continuation frame");
          return;
        }
        this.fragBytes += payload.length;
        if (this.fragBytes > MAX_MESSAGE_BYTES) {
          this.close(1009, "Fragmented message exceeds maximum size");
          return;
        }
        this.fragChunks.push(payload);
        if (fin) {
          const fullMsg = Buffer.concat(this.fragChunks, this.fragBytes);
          const finishedOpcode = this.fragOpcode;
          this.fragOpcode = 0;
          this.fragChunks = [];
          this.fragBytes = 0;
          this.dispatchMessage(finishedOpcode, fullMsg);
        }
      } else if (opcode === 0x1 || opcode === 0x2) {
        if (this.fragOpcode !== 0) {
          this.close(1002, "Interleaved data frame during fragmentation");
          return;
        }
        if (fin) {
          this.dispatchMessage(opcode, payload);
        } else {
          this.fragOpcode = opcode;
          this.fragChunks = [payload];
          this.fragBytes = payload.length;
        }
      } else {
        this.close(1003, "Unsupported opcode");
        return;
      }
    }
  }

  dispatchMessage(opcode, payload) {
    if (opcode === 0x2) {
      this.core.handleBinary(this, payload);
    } else if (opcode === 0x1) {
      this.core.handleText(this, payload.toString("utf8"));
    }
  }
}

export function startBunWanRelayServer(options) {
  const core = new OmsWanRelayCore(options?.authToken);
  const bunServer = globalThis.Bun.serve({
    hostname: options?.host || "127.0.0.1",
    port: options?.port ?? 0,
    fetch(req, srv) {
      const url = new URL(req.url);
      if (req.method === "GET" && (url.pathname === "/oms-wan/health" || url.pathname === "/health")) {
        return new Response(JSON.stringify(core.getHealthSnapshot()), {
          status: 200,
          headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
        });
      }
      if ((req.headers.get("upgrade") || "").toLowerCase() === "websocket") {
        const roomId = (url.searchParams.get("room") || "").trim();
        const role = (url.searchParams.get("role") || "").trim();
        const check = core.validateUpgradeParams(url.pathname, req.headers.get("authorization"), roomId, role);
        if (!check.ok) {
          return new Response(check.message, { status: check.status });
        }
        if (srv.upgrade(req, { data: { roomId, role, peer: null } })) {
          return undefined;
        }
        return new Response("Upgrade failed", { status: 400 });
      }
      return new Response(JSON.stringify({ ok: false, error: "Not Found" }), { status: 404 });
    },
    websocket: {
      open(ws) {
        let closed = false;
        const peer = {
          roomId: ws.data.roomId,
          role: ws.data.role,
          isClosed: () => closed,
          sendJsonControl: (obj) => {
            if (closed) return false;
            ws.send(JSON.stringify(obj));
            return true;
          },
          sendBinary: (payload) => {
            if (closed) return false;
            ws.send(payload instanceof Uint8Array ? payload : new Uint8Array(payload));
            return true;
          },
          close: (code = 1000, reason = "") => {
            if (closed) return;
            closed = true;
            try {
              ws.close(code, reason);
            } catch {}
            core.handleClose(peer);
          },
        };
        ws.data.peer = peer;
        core.attachPeer(peer);
      },
      message(ws, msg) {
        const peer = ws.data?.peer;
        if (!peer) return;
        if (typeof msg === "string") {
          core.handleText(peer, msg);
        } else {
          core.handleBinary(peer, msg instanceof Uint8Array ? msg : new Uint8Array(msg));
        }
      },
      close(ws) {
        const peer = ws.data?.peer;
        if (peer && !peer.isClosed()) {
          peer.close(1000, "Closed");
        }
      },
    },
  });
  return { server: bunServer, core, port: bunServer.port, stop: () => bunServer.stop(true) };
}

export function createOmsWanRelayServer(options) {
  const core = new OmsWanRelayCore(options?.authToken);
  const rooms = core.rooms;

  const server = http.createServer((req, res) => {
    const url = new URL(req.url || "/", "http://localhost");
    if (req.method === "GET" && (url.pathname === "/oms-wan/health" || url.pathname === "/health")) {
      const body = JSON.stringify(core.getHealthSnapshot());
      res.writeHead(200, {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
      });
      res.end(body);
      return;
    }

    res.writeHead(404, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ ok: false, error: "Not Found" }));
  });

  server.on("upgrade", (req, socket, head) => {
    try {
      const url = new URL(req.url || "/", "http://localhost");
      const roomId = (url.searchParams.get("room") || "").trim();
      const role = (url.searchParams.get("role") || "").trim();
      const check = core.validateUpgradeParams(url.pathname, req.headers["authorization"], roomId, role);
      if (!check.ok) {
        socket.write(`HTTP/1.1 ${check.status} ${check.message}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n`);
        socket.destroy();
        return;
      }

      const wsKey = req.headers["sec-websocket-key"];
      const upgradeHeader = String(req.headers["upgrade"] || "").toLowerCase();
      if (typeof wsKey !== "string" || !wsKey.trim() || upgradeHeader !== "websocket") {
        socket.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        socket.destroy();
        return;
      }

      const acceptKey = crypto
        .createHash("sha1")
        .update(wsKey.trim() + WS_GUID, "utf8")
        .digest("base64");

      socket.write(
        "HTTP/1.1 101 Switching Protocols\r\n" +
          "Upgrade: websocket\r\n" +
          "Connection: Upgrade\r\n" +
          `Sec-WebSocket-Accept: ${acceptKey}\r\n\r\n`
      );

      const peer = new WsPeerConnection(socket, roomId, role, core);
      core.attachPeer(peer);
      if (head && head.length > 0) {
        peer.onData(head);
      }
    } catch {
      try {
        socket.destroy();
      } catch {}
    }
  });

  const sweepTimer = setInterval(() => {
    const now = Date.now();
    for (const room of rooms.values()) {
      for (const peer of [room.host, room.phone]) {
        if (!peer || peer.isClosed()) continue;
        if (now - peer.lastSeenMs > IDLE_TIMEOUT_MS) {
          peer.close(4008, "Heartbeat timeout");
        } else {
          peer.sendPing();
        }
      }
    }
  }, PING_INTERVAL_MS);
  sweepTimer.unref?.();

  server.on("close", () => {
    clearInterval(sweepTimer);
    for (const room of rooms.values()) {
      room.host?.close(1001, "Server shutting down");
      room.phone?.close(1001, "Server shutting down");
    }
    rooms.clear();
  });

  return { server, rooms, core };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.OMS_RELAY_PORT || process.env.PORT || 8788);
  const host = process.env.OMS_RELAY_HOST || "0.0.0.0";
  const authToken = process.env.OMS_RELAY_AUTH_TOKEN || "";
  const { server } = createOmsWanRelayServer({ authToken });
  server.listen(port, host, () => {
    console.log(`[oms-wan-relay] listening on ${host}:${port}`);
  });
}
