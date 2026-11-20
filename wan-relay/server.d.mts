import type http from "node:http";

export interface WanRelayServerOptions {
  authToken: string;
  host?: string;
  port?: number;
}

export function startBunWanRelayServer(options: WanRelayServerOptions): {
  server: unknown;
  core: unknown;
  port: number;
  stop: () => void;
};

export function createOmsWanRelayServer(options: WanRelayServerOptions): {
  server: http.Server;
  rooms: Map<string, unknown>;
  core: unknown;
};
