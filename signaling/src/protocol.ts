export interface Env {
  ASSIST_HUB: DurableObjectNamespace;
  /** Optional stage 1 TURN: comma separated turn:/turns: URIs of the relay. */
  TURN_URIS?: string;
  /** Optional stage 1 TURN REST mode: Coturn use-auth-secret shared secret; must never be shipped in the APK. */
  TURN_SECRET?: string;
  /** Optional stage 1 TURN static mode: hosted provider's fixed username (both required together). */
  TURN_USERNAME?: string;
  /** Optional stage 1 TURN static mode: hosted provider's fixed credential (both required together). */
  TURN_CREDENTIAL?: string;
  /** Optional stage 1 TURN Cloudflare Realtime mode: TURN key id (32 hex chars). */
  TURN_KEY_ID?: string;
  /** Optional stage 1 TURN Cloudflare Realtime mode: TURN key API token; never shipped in the APK. */
  TURN_KEY_TOKEN?: string;
}
export interface Message {
  v: 1;
  id: string;
  type: string;
  roomId?: string;
  sessionId?: string;
  payload: Record<string, unknown>;
}
export class ProtocolError extends Error {
  constructor(public readonly code: string) { super(code); }
}
export function requireCondition(value: unknown, code: string): asserts value {
  if (!value) throw new ProtocolError(code);
}
export function text(value: unknown, max = 256): string {
  requireCondition(typeof value === "string" && value.length > 0 && value.length <= max, "INVALID_MESSAGE");
  return value;
}
export function parseMessage(raw: string | ArrayBuffer): Message {
  requireCondition(typeof raw === "string" && new TextEncoder().encode(raw).length <= 65_536, "INVALID_MESSAGE");
  let value: unknown;
  try { value = JSON.parse(raw); } catch { throw new ProtocolError("INVALID_MESSAGE"); }
  requireCondition(value !== null && typeof value === "object" && !Array.isArray(value), "INVALID_MESSAGE");
  const m = value as Record<string, unknown>;
  requireCondition(m.v === 1, "UNSUPPORTED_VERSION");
  text(m.id, 80); text(m.type, 64);
  requireCondition(m.payload !== null && typeof m.payload === "object" && !Array.isArray(m.payload), "INVALID_MESSAGE");
  return m as unknown as Message;
}
export async function digest(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, "0")).join("");
}
export function randomCode(): string {
  const bytes = new Uint32Array(1);
  do { crypto.getRandomValues(bytes); } while (bytes[0]! >= 4_200_000_000);
  return String(bytes[0]! % 100_000_000).padStart(8, "0");
}
