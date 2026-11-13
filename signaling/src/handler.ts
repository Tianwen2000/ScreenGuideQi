import type { Env } from "./protocol";
import { resolveTurnPlan } from "./turn";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") {
      const turnEnabled = resolveTurnPlan(env) !== undefined;
      return Response.json({ ok: true, protocol: 1, stage: turnEnabled ? 1 : 0, turnEnabled }, {
        headers: { "Cache-Control": "no-store" },
      });
    }
    if (url.pathname === "/signal" && request.headers.get("Upgrade")?.toLowerCase() === "websocket") {
      const hub = env.ASSIST_HUB.get(env.ASSIST_HUB.idFromName("assist-mvp-v1"));
      return hub.fetch(request);
    }
    return new Response("Not found", { status: 404 });
  },
};
