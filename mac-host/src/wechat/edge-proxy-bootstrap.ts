import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import type {
  EdgeClawBotAuthPayload,
  EdgeConfigSyncParams,
  EdgeProviderPayload,
  EdgeProxyNodePayload,
} from "@oms/shared-protocol";

const SUPPORTED_SS_CIPHERS = new Set([
  "2022-blake3-aes-128-gcm",
  "2022-blake3-aes-256-gcm",
  "2022-blake3-chacha20-poly1305",
  "aes-128-gcm",
  "aes-256-gcm",
  "chacha20-ietf-poly1305",
]);

export function parseDotEnvSafe(filePath: string): Record<string, string> {
  if (!fs.existsSync(filePath)) return {};
  try {
    const content = fs.readFileSync(filePath, "utf8").replace(/^\uFEFF/, "");
    const out: Record<string, string> = {};
    for (const rawLine of content.split(/\r?\n/)) {
      let line = rawLine.trim();
      if (!line || line.startsWith("#")) continue;
      if (line.startsWith("export ")) line = line.slice(7).trim();
      const eq = line.indexOf("=");
      if (eq <= 0) continue;
      const key = line.slice(0, eq).trim();
      let val = line.slice(eq + 1).trim();
      if (
        val.length >= 2 &&
        ((val.startsWith('"') && val.endsWith('"')) ||
          (val.startsWith("'") && val.endsWith("'")))
      ) {
        val = val.slice(1, -1);
      } else if (val.includes(" #")) {
        val = val.split(" #", 1)[0].trim();
      }
      if (key) out[key] = val;
    }
    return out;
  } catch {
    return {};
  }
}

function decodeBase64Loose(raw: string): string {
  const cleaned = raw.trim().replace(/\s+/g, "").replace(/-/g, "+").replace(/_/g, "/");
  if (!cleaned) return "";
  const pad = cleaned.length % 4 === 2 ? "==" : cleaned.length % 4 === 3 ? "=" : "";
  return Buffer.from(cleaned + pad, "base64").toString("utf8");
}

function splitFlowEntries(inner: string): string[] {
  const out: string[] = [];
  let cur = "";
  let quote: string | null = null;
  for (let i = 0; i < inner.length; i++) {
    const ch = inner[i];
    if (quote) {
      if (ch === "\\" && i + 1 < inner.length) {
        cur += inner[++i];
        continue;
      }
      if (ch === quote) quote = null;
      else cur += ch;
    } else if (ch === '"' || ch === "'") {
      quote = ch;
    } else if (ch === ",") {
      out.push(cur);
      cur = "";
    } else {
      cur += ch;
    }
  }
  if (cur) out.push(cur);
  return out;
}

export function parseBootstrapSubscriptionText(
  text: string,
  source = ""
): EdgeProxyNodePayload[] {
  const stripped = text.trim().replace(/^\uFEFF/, "");
  if (!stripped) return [];
  const nodes: EdgeProxyNodePayload[] = [];

  if (stripped.includes("proxies:")) {
    let section = "";
    let current: Record<string, any> | null = null;
    const flush = (d: Record<string, any> | null) => {
      if (!d) return;
      const ptype = String(d.type || "").toLowerCase().trim();
      const server = String(d.server || "").trim();
      const port = Number(d.port || 0);
      if (!server || port <= 0 || port > 65535) return;
      if (ptype === "ss") {
        const cipher = String(d.cipher || "").toLowerCase().trim();
        if (!SUPPORTED_SS_CIPHERS.has(cipher)) return;
        let plugin = d.plugin ? String(d.plugin).trim() : null;
        if (plugin === "obfs-local" || plugin === "simple-obfs") plugin = "obfs";
        if (plugin && plugin !== "obfs") return;
        nodes.push({
          name: String(d.name || "ss-node"),
          protocol: "ss",
          server,
          port,
          cipher,
          password: String(d.password || ""),
          plugin,
          pluginOpts: d["plugin-opts"] || {},
          udp: Boolean(d.udp),
          source,
        });
      } else if (ptype === "trojan") {
        if (d.network && String(d.network) !== "tcp") return;
        nodes.push({
          name: String(d.name || "trojan-node"),
          protocol: "trojan",
          server,
          port,
          password: String(d.password || ""),
          sni: String(d.sni || d.servername || server),
          skipCertVerify: Boolean(d["skip-cert-verify"]),
          udp: Boolean(d.udp),
          source,
        });
      }
    };

    for (const rawLine of stripped.split(/\r?\n/)) {
      const s = rawLine.trim();
      if (!s || s.startsWith("#")) continue;
      const indent = rawLine.search(/\S/);
      if (indent === 0 && s.endsWith(":")) {
        flush(current);
        current = null;
        section = s.slice(0, -1).trim();
        continue;
      }
      if (section === "proxies") {
        if (s.startsWith("- {") && s.endsWith("}")) {
          flush(current);
          current = null;
          const obj: Record<string, any> = {};
          for (const part of splitFlowEntries(s.slice(3, -1))) {
            const c = part.indexOf(":");
            if (c > 0) {
              const k = part.slice(0, c).trim().replace(/^['"]|['"]$/g, "");
              const v = part.slice(c + 1).trim().replace(/^['"]|['"]$/g, "");
              obj[k] = v === "true" ? true : v === "false" ? false : obj[k] ?? v;
            }
          }
          flush(obj);
        } else if (s.startsWith("- ")) {
          flush(current);
          current = {};
          const rest = s.slice(2).trim();
          const c = rest.indexOf(":");
          if (c > 0) {
            current[rest.slice(0, c).trim()] = rest.slice(c + 1).trim().replace(/^['"]|['"]$/g, "");
          }
        } else if (current && s.includes(":")) {
          const c = s.indexOf(":");
          const k = s.slice(0, c).trim();
          const v = s.slice(c + 1).trim().replace(/^['"]|['"]$/g, "");
          if (k === "plugin-opts" && !v) {
            current["plugin-opts"] = {};
          } else if (indent >= 6 && current["plugin-opts"]) {
            current["plugin-opts"][k] = v;
          } else {
            current[k] = v === "true" ? true : v === "false" ? false : v;
          }
        }
      }
    }
    flush(current);
    return nodes;
  }

  const decoded = stripped.slice(0, 64).includes("://")
    ? stripped
    : decodeBase64Loose(stripped) || stripped;

  for (const rawUri of decoded.split(/\r?\n/)) {
    const uri = rawUri.trim();
    if (uri.startsWith("ss://")) {
      let body = uri.slice(5);
      let name = "ss-node";
      const fIdx = body.indexOf("#");
      if (fIdx >= 0) {
        try { name = decodeURIComponent(body.slice(fIdx + 1)) || name; } catch {}
        body = body.slice(0, fIdx);
      }
      const qIdx = body.indexOf("?");
      let queryStr = "";
      if (qIdx >= 0) {
        queryStr = body.slice(qIdx + 1);
        body = body.slice(0, qIdx);
      }
      body = body.replace(/\/+$/, "");
      let userinfo = "";
      let hostPort = "";
      const atIdx = body.lastIndexOf("@");
      if (atIdx >= 0) {
        const rawU = decodeURIComponent(body.slice(0, atIdx));
        userinfo = rawU.includes(":") ? rawU : decodeBase64Loose(rawU);
        hostPort = body.slice(atIdx + 1);
      } else {
        const dec = decodeBase64Loose(body);
        const dAt = dec.lastIndexOf("@");
        if (dAt < 0) continue;
        userinfo = dec.slice(0, dAt);
        hostPort = dec.slice(dAt + 1);
      }
      const uColon = userinfo.indexOf(":");
      const hColon = hostPort.lastIndexOf(":");
      if (uColon <= 0 || hColon <= 0) continue;
      const cipher = userinfo.slice(0, uColon).toLowerCase().trim();
      if (!SUPPORTED_SS_CIPHERS.has(cipher)) continue;
      const password = userinfo.slice(uColon + 1);
      const server = hostPort.slice(0, hColon).replace(/^\[|\]$/g, "");
      const port = Number(hostPort.slice(hColon + 1));
      if (!server || !Number.isFinite(port) || port <= 0) continue;

      let plugin: string | null = null;
      const pluginOpts: Record<string, string> = {};
      if (queryStr) {
        const params = new URLSearchParams(queryStr);
        const plg = params.get("plugin");
        if (plg) {
          const parts = plg.split(";");
          if (["obfs-local", "simple-obfs", "obfs"].includes(parts[0])) {
            plugin = "obfs";
            for (const opt of parts.slice(1)) {
              const eq = opt.indexOf("=");
              if (eq > 0) {
                const ok = opt.slice(0, eq);
                const ov = opt.slice(eq + 1);
                if (ok === "obfs" || ok === "mode") pluginOpts.mode = ov;
                if (ok === "obfs-host" || ok === "host") pluginOpts.host = ov;
                if (ok === "obfs-uri" || ok === "path") pluginOpts.path = ov;
              }
            }
          } else continue;
        }
      }
      nodes.push({ name, protocol: "ss", server, port, cipher, password, plugin, pluginOpts, source });
    } else if (uri.startsWith("trojan://")) {
      try {
        const parsed = new URL(uri);
        const port = Number(parsed.port || 443);
        const typeParam = parsed.searchParams.get("type") || "tcp";
        if (typeParam !== "tcp") continue;
        nodes.push({
          name: decodeURIComponent(parsed.hash.replace(/^#/, "")) || "trojan-node",
          protocol: "trojan",
          server: parsed.hostname.replace(/^\[|\]$/g, ""),
          port,
          password: decodeURIComponent(parsed.username),
          sni: parsed.searchParams.get("sni") || parsed.searchParams.get("peer") || parsed.hostname,
          skipCertVerify: ["1", "true"].includes((parsed.searchParams.get("allowInsecure") || "").toLowerCase()),
          source,
        });
      } catch {}
    }
  }
  return nodes;
}

export async function buildEdgeSyncPayloadFromHost(opts: {
  omsHomeDir: string;
  wechatHomeDir: string;
  repoRootDir: string;
  wechatWorkerSnapshot?: Record<string, any> | null;
  allowExternalUserEnv?: boolean;
}): Promise<EdgeConfigSyncParams> {
  const defaultOmsHome = path.join(os.homedir(), ".one_more_step");
  const allowUserEnv =
    opts.allowExternalUserEnv ??
    (path.resolve(opts.omsHomeDir) === path.resolve(defaultOmsHome) &&
      process.env.OMS_TEST_ISOLATION !== "1");

  const envFiles = [
    ...(allowUserEnv
      ? [
          path.join(os.homedir(), ".env"),
          path.join(path.dirname(opts.repoRootDir), "vpn", ".env"),
        ]
      : []),
    path.join(opts.omsHomeDir, ".env"),
    path.join(opts.wechatHomeDir, ".env"),
  ];

  const mergedEnv: Record<string, string> = {};
  for (const f of envFiles) {
    Object.assign(mergedEnv, parseDotEnvSafe(f));
  }

  // 1. Collect SUB_VPN_* subscription URLs
  const subUrls: string[] = [];
  for (const k of Object.keys(mergedEnv).sort()) {
    if (k.startsWith("SUB_VPN_") && mergedEnv[k].trim()) {
      for (const u of mergedEnv[k].split(",")) {
        if (u.trim()) subUrls.push(u.trim());
      }
    }
  }
  if (subUrls.length === 0 && mergedEnv.VPN_SUB_URL?.trim()) {
    for (const u of mergedEnv.VPN_SUB_URL.split(",")) {
      if (u.trim()) subUrls.push(u.trim());
    }
  }

  const preferRegions = mergedEnv.VPN_PREFER_REGIONS?.trim() || "美国|新加坡";
  const maxPreferredLatencyMs = Number(mergedEnv.VPN_MAX_PREFER_LATENCY_MS) || 4000;

  // 2. Load or refresh bootstrapNodes snapshot (cached 30 min in ~/.one_more_step/cache/edge_proxy_nodes.json)
  const cachePath = path.join(opts.omsHomeDir, "cache", "edge_proxy_nodes.json");
  let bootstrapNodes: EdgeProxyNodePayload[] = [];
  if (subUrls.length > 0) {
    let loadedCached = false;
    try {
      if (fs.existsSync(cachePath)) {
        const cached = JSON.parse(fs.readFileSync(cachePath, "utf8"));
        if (
          Array.isArray(cached?.nodes) &&
          cached.nodes.length > 0 &&
          Date.now() - Number(cached.fetchedAtMs || 0) < 30 * 60 * 1000
        ) {
          bootstrapNodes = cached.nodes;
          loadedCached = true;
        }
      }
    } catch {}

    if (!loadedCached) {
      for (let i = 0; i < subUrls.length; i++) {
        try {
          const resp = await fetch(subUrls[i], {
            headers: { "User-Agent": "clash.meta/1.18.0" },
            signal: AbortSignal.timeout(6000),
          });
          if (resp.ok) {
            const parsed = parseBootstrapSubscriptionText(await resp.text(), `SUB_${i + 1}`);
            bootstrapNodes.push(...parsed);
          }
        } catch {}
      }
      if (bootstrapNodes.length > 0) {
        bootstrapNodes = bootstrapNodes.slice(0, 60);
        try {
          fs.mkdirSync(path.dirname(cachePath), { recursive: true, mode: 0o700 });
          fs.writeFileSync(
            cachePath,
            JSON.stringify({ fetchedAtMs: Date.now(), nodes: bootstrapNodes }),
            { mode: 0o600 }
          );
        } catch {}
      }
    }
  }

  // 3. Resolve OpenAI (GPT-6.2), Gemini (4 Argon / 3.8 Flash), and Domestic fallback keys
  const workerLlm = opts.wechatWorkerSnapshot?.llmConfig;
  const workerProviders: Array<{ id: string; baseUrl: string; apiKey: string; models: string[] }> =
    Array.isArray(workerLlm?.providers) ? workerLlm.providers : [];

  const findWorkerProvider = (id: string) =>
    workerProviders.find((p) => p.id === id && p.apiKey?.trim());

  const oaiWorker = findWorkerProvider("openai");
  const openaiKey = oaiWorker?.apiKey || mergedEnv.OPENAI_API_KEY?.trim() || "";
  const openai: EdgeProviderPayload | undefined = openaiKey
    ? {
        providerId: "openai",
        baseUrl: oaiWorker?.baseUrl || mergedEnv.OPENAI_BASE_URL?.trim() || "https://api.openai.com/v1",
        apiKey: openaiKey,
        fastModel: mergedEnv.OPENAI_FAST_MODEL?.trim() || "gpt-6.2-sol",
        flagshipModel: mergedEnv.OPENAI_PRO_MODEL?.trim() || "gpt-6.2-sol",
        reasoningModel: mergedEnv.OPENAI_REASONING_MODEL?.trim() || "gpt-6.2-astra",
        liveModel: "gpt-realtime-2.1",
        transcribeModel: "gpt-live-transcribe",
      }
    : undefined;

  const gemWorker = findWorkerProvider("gemini");
  const geminiKey =
    gemWorker?.apiKey ||
    mergedEnv.GEMINI_API_KEY?.trim() ||
    mergedEnv.GOOGLE_API_KEY?.trim() ||
    (String(workerLlm?.baseUrl || "").includes("generativelanguage.googleapis.com")
      ? String(workerLlm?.apiKey || "").trim()
      : "");
  const gemini: EdgeProviderPayload | undefined = geminiKey
    ? {
        providerId: "gemini",
        baseUrl: "https://generativelanguage.googleapis.com/v1beta",
        apiKey: geminiKey,
        fastModel: mergedEnv.GEMINI_FAST_MODEL?.trim() || "gemini-3.8-flash",
        flagshipModel: mergedEnv.GEMINI_PRO_MODEL?.trim() || "gemini-4-argon",
        reasoningModel: "gemini-4-argon",
        liveModel: "gemini-3.8-live",
      }
    : undefined;

  const dsKey = findWorkerProvider("deepseek")?.apiKey || mergedEnv.DEEPSEEK_API_KEY?.trim() || "";
  const ocKey = findWorkerProvider("opencode")?.apiKey || mergedEnv.OPENCODE_API_KEY?.trim() || "";
  const domesticFallback: EdgeProviderPayload | undefined = dsKey
    ? {
        providerId: "fallback",
        baseUrl: "https://api.deepseek.com/v1",
        apiKey: dsKey,
        fastModel: "deepseek-chat",
        flagshipModel: "deepseek-chat",
        reasoningModel: "deepseek-reasoner",
      }
    : ocKey
      ? {
          providerId: "fallback",
          baseUrl: "https://opencode.ai/zen/v1",
          apiKey: ocKey,
          fastModel: "glm-5.1",
          flagshipModel: "claude-sonnet-4-5",
        }
      : undefined;

  const clawbotAuth = opts.wechatWorkerSnapshot?.clawbotAuth as EdgeClawBotAuthPayload | undefined;

  return {
    subscriptionUrls: subUrls,
    bootstrapNodes,
    preferRegions,
    maxPreferredLatencyMs,
    openai,
    gemini,
    domesticFallback,
    clawbotAuth,
    syncedAtMs: Date.now(),
  };
}
