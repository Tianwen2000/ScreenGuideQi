import type { Env } from "./protocol";

/**
 * Stage 1 TURN: optional relay with short-lived credentials. Three mutually
 * exclusive modes, chosen by configuration (first match wins):
 *
 * - Cloudflare Realtime TURN: TURN_KEY_ID + TURN_KEY_TOKEN. The key id and its
 *   API token stay in Worker secrets; the worker asks rtc.live.cloudflare.com
 *   for per-session iceServers, so nothing long-lived reaches the APK.
 * - Static (hosted providers like Metered): TURN_URIS + TURN_USERNAME +
 *   TURN_CREDENTIAL passthrough per accepted session.
 * - REST (self-hosted Coturn): TURN_URIS + TURN_SECRET; each session gets a
 *   HMAC username/credential pair that a Coturn with use-auth-secret accepts.
 *
 * When nothing (or something invalid) is configured the hub behaves exactly
 * like stage 0: no ice payload is sent and pairing never fails because of TURN.
 */
export interface TurnPlan { mode: "cloudflare" | "rest" | "static"; uris?: string[] }
export interface TurnGrant { uris: string[]; username: string; credential: string }

const MAX_URIS = 8;
const MAX_URI_LENGTH = 255;
const CLOUDFLARE_TURN_API = "https://rtc.live.cloudflare.com/v1/turn/keys";

export function resolveTurnPlan(env: Env): TurnPlan | undefined {
  const keyId = (env.TURN_KEY_ID ?? "").trim();
  const keyToken = (env.TURN_KEY_TOKEN ?? "").trim();
  if (keyId || keyToken) {
    const valid = /^[0-9a-f]{32}$/i.test(keyId) && keyToken.length >= 16 && keyToken.length <= 512;
    return valid ? { mode: "cloudflare" } : undefined;
  }
  const raw = (env.TURN_URIS ?? "").trim();
  if (!raw) return undefined;
  const uris = raw.split(",").map(uri => uri.trim()).filter(uri => uri.length > 0);
  const urisValid = uris.length > 0 && uris.length <= MAX_URIS
    && uris.every(uri => (uri.startsWith("turn:") || uri.startsWith("turns:")) && uri.length <= MAX_URI_LENGTH);
  if (!urisValid) return undefined;
  const username = (env.TURN_USERNAME ?? "").trim();
  const credential = (env.TURN_CREDENTIAL ?? "").trim();
  if (username && credential) {
    if (username.length > MAX_URI_LENGTH || credential.length > MAX_URI_LENGTH) return undefined;
    return { mode: "static", uris };
  }
  if (username || credential) return undefined;
  const secret = env.TURN_SECRET ?? "";
  return secret.length >= 16 ? { mode: "rest", uris } : undefined;
}

/** Returns undefined when credentials could not be minted; callers then omit ice and keep direct-only. */
export async function turnGrant(plan: TurnPlan, env: Env, lifetimeSeconds: number): Promise<TurnGrant | undefined> {
  try {
    if (plan.mode === "cloudflare") return await cloudflareGrant(env, lifetimeSeconds);
    if (plan.mode === "static") {
      return { uris: plan.uris!, username: env.TURN_USERNAME!.trim(), credential: env.TURN_CREDENTIAL!.trim() };
    }
    const username = String(Math.floor(Date.now() / 1000) + lifetimeSeconds);
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.TURN_SECRET!), { name: "HMAC", hash: "SHA-1" }, false, ["sign"]);
    const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(username));
    const credential = btoa(String.fromCharCode(...new Uint8Array(mac)));
    return { uris: plan.uris!, username, credential };
  } catch {
    return undefined;
  }
}

async function cloudflareGrant(env: Env, lifetimeSeconds: number): Promise<TurnGrant | undefined> {
  const keyId = env.TURN_KEY_ID!.trim();
  const response = await fetch(`${CLOUDFLARE_TURN_API}/${encodeURIComponent(keyId)}/credentials/generate`, {
    method: "POST",
    headers: { Authorization: `Bearer ${env.TURN_KEY_TOKEN!.trim()}`, "Content-Type": "application/json" },
    body: JSON.stringify({ ttl: Math.min(lifetimeSeconds, 86_400) }),
    signal: AbortSignal.timeout(4_000),
  });
  if (!response.ok) return undefined;
  const data = await response.json() as { iceServers?: { urls?: string | string[]; username?: string; credential?: string } };
  const ice = data.iceServers;
  if (!ice || !ice.username || !ice.credential) return undefined;
  const uris = (Array.isArray(ice.urls) ? ice.urls : [ice.urls ?? ""])
    .filter(uri => (uri.startsWith("turn:") || uri.startsWith("turns:")) && uri.length <= MAX_URI_LENGTH)
    .slice(0, MAX_URIS);
  if (uris.length === 0 || ice.username.length > MAX_URI_LENGTH || ice.credential.length > MAX_URI_LENGTH) return undefined;
  return { uris, username: ice.username, credential: ice.credential };
}
