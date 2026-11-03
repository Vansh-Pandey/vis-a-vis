// Admin two-step login checks against a STAGING-mode instance with one admin and two-step not yet set up.
//   BASE_URL=http://localhost:8081 ADMIN_ID=... ADMIN_PASSWORD=... node scripts/admin-2fa-check.mjs
import { createHmac } from "node:crypto";
const BASE = process.env.BASE_URL ?? "http://localhost:8081";
const ORIGIN = process.env.SITE_ORIGIN ?? "http://localhost:3000";
const A = { id: process.env.ADMIN_ID, password: process.env.ADMIN_PASSWORD };
let failures = 0, passes = 0;
const check = (n, ok, d = "") => { ok ? passes++ : failures++; console.log(`${ok ? "PASS" : "FAIL"}  ${n}${!ok && d ? `  (${d})` : ""}`); };
function totp(secret, step) { // RFC 6238, written independently of the Java code
  const al = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; let bits = "";
  for (const c of secret) bits += al.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const msg = Buffer.alloc(8); msg.writeBigUInt64BE(BigInt(step));
  const h = createHmac("sha1", key).update(msg).digest(); const o = h[19] & 15;
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1e6).padStart(6, "0");
}
const step = () => Math.floor(Date.now() / 30000);
function client() {
  const jar = new Map();
  return async (path, body) => {
    const res = await fetch(BASE + path, { method: body === undefined ? "GET" : "POST", headers: { origin: ORIGIN, "content-type": "application/json", cookie: [...jar].map(([k, v]) => `${k}=${v}`).join("; ") }, body: body === undefined ? undefined : JSON.stringify(body) });
    for (const c of res.headers.getSetCookie()) { const [p] = c.split(";"); const i = p.indexOf("="); p.slice(i + 1) ? jar.set(p.slice(0, i), p.slice(i + 1)) : jar.delete(p.slice(0, i)); }
    const t = await res.text(); let d; try { d = JSON.parse(t); } catch { d = t; } return { status: res.status, data: d };
  };
}
const c = client();
check("wrong password refused", (await c("/api/admin/login", { ...A, password: "Wrong-pass-1234" })).status === 401);
const s1 = await c("/api/admin/login", A);
check("first sign-in asks to set up two-step login", s1.data.next === "totp-setup" && /^[A-Z2-7]{32}$/.test(s1.data.secret), JSON.stringify(s1.data));
check("no admin session before setup is finished", (await c("/api/admin/members")).status === 401);
check("setup refuses a wrong code", (await c("/api/admin/login", { ...A, code: "000000" })).status === 401);
const s2 = await c("/api/admin/login", { ...A, code: totp(s1.data.secret, step()) });
check("setup finished with the first code signs in", s2.status === 200 && s2.data.ok === true, JSON.stringify(s2.data));
check("admin API works", (await c("/api/admin/members")).status === 200);
const me = await c("/api/admin/me");
check("two-step login shown as enabled and required", me.data.totpEnabled === true && me.data.totpRequired === true, JSON.stringify(me.data));
const f = client();
const p1 = await f("/api/admin/login", A);
check("password alone now only asks for the code", p1.data.next === "totp", JSON.stringify(p1.data));
check("no session without the code", (await f("/api/admin/members")).status === 401);
check("code already used is refused (no replay)", (await f("/api/admin/login", { ...A, code: totp(s1.data.secret, step()) })).status === 401);
const ok = await f("/api/admin/login", { ...A, code: totp(s1.data.secret, step() + 1) });
check("password + fresh code signs in", ok.status === 200 && ok.data.ok === true, JSON.stringify(ok.data));
const pw = await c("/api/admin/password", { currentPassword: "Wrong-pass-1234", newPassword: "New-admin-pass-77", code: "" });
check("password change needs the current password", pw.status === 422);
const used = step() + 1; // the last code used above
while (step() < used) await new Promise((r) => setTimeout(r, 1000)); // wait for the next 30-second window
const pw2 = await c("/api/admin/password", { currentPassword: A.password, newPassword: "New-admin-pass-77", code: totp(s1.data.secret, used + 1) });
check("password change with password + code works", pw2.status === 200, JSON.stringify(pw2.data));
check("this session continues after the change", (await c("/api/admin/members")).status === 200);
check("other admin sessions end after the change", (await f("/api/admin/members")).status === 401);
const audit = (await c("/api/admin/audit-log")).data.items.map((a) => a.action);
check("sign-ins, failures and the change are audited", ["管理者ログイン", "管理者ログイン失敗", "2段階認証の設定", "管理者パスワード変更"].every((x) => audit.includes(x)), audit.join(","));
console.log(`\n${passes} passed, ${failures} failed`);
process.exit(failures);
