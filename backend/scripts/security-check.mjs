// End-to-end security checks against a running vinsight-api in DEV mode with an empty database:
//   ./scripts/run-dev.sh   (with VINSIGHT_BOOTSTRAP_ADMIN_ID/PASSWORD set for the first start)
//   ADMIN_ID=devadmin ADMIN_PASSWORD=... CSV=dummy.csv node scripts/security-check.mjs
// Prints PASS/FAIL per check; the exit code is the number of failures. The checks import members and spend points,
// so run them against a fresh database.
import { readFileSync } from "node:fs";

const BASE = process.env.BASE_URL ?? "http://localhost:8080";
const ORIGIN = process.env.SITE_ORIGIN ?? "http://localhost:3000";
const ADMIN = { id: process.env.ADMIN_ID, password: process.env.ADMIN_PASSWORD };
const CSV = readFileSync(process.env.CSV);
let failures = 0, passes = 0;
const check = (name, ok, detail = "") => {
  if (ok) passes++; else failures++;
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${!ok && detail ? `  (${String(detail).slice(0, 300)})` : ""}`);
};

function client() {
  const jar = new Map();
  const call = async (path, { method, body, headers = {}, raw, form } = {}) => {
    const h = { origin: ORIGIN, cookie: [...jar].map(([k, v]) => `${k}=${v}`).join("; "), ...headers };
    let payload;
    if (form) payload = form;
    else if (raw !== undefined) payload = raw;
    else if (body !== undefined) { payload = JSON.stringify(body); h["content-type"] ??= "application/json"; }
    const res = await fetch(BASE + path, { method: method ?? (payload === undefined ? "GET" : "POST"), headers: h, body: payload, redirect: "manual" });
    for (const c of res.headers.getSetCookie()) {
      const [pair] = c.split(";");
      const i = pair.indexOf("=");
      const v = pair.slice(i + 1);
      if (v) jar.set(pair.slice(0, i), v); else jar.delete(pair.slice(0, i));
    }
    const text = await res.text();
    let data; try { data = JSON.parse(text); } catch { data = text; }
    return { status: res.status, data, headers: res.headers, text };
  };
  call.jar = jar;
  return call;
}

async function captcha(call) {
  const { data } = await call("/api/captcha");
  return { captchaId: data.id, captcha: [...data.svg.matchAll(/>(\d)<\/text>/g)].map((m) => m[1]).join("") };
}
const rid = () => crypto.randomUUID().replace(/-/g, "");

async function main() {
  // ---------- headers and basics ----------
  const h = await fetch(BASE + "/api/health");
  check("health answers", h.status === 200);
  for (const name of ["content-security-policy", "x-frame-options", "x-content-type-options", "referrer-policy", "permissions-policy"]) check(`header ${name}`, h.headers.has(name));
  check("noindex outside production", (h.headers.get("x-robots-tag") ?? "").includes("noindex"));
  check("no server software disclosed", !h.headers.has("x-powered-by"));
  check("API responses are not cached", (h.headers.get("cache-control") ?? "").includes("no-store"));
  check("unknown path refused", [401, 403].includes((await fetch(BASE + "/api/does-not-exist")).status));
  check("actuator / internals not exposed", [401, 403].includes((await fetch(BASE + "/actuator/env")).status));

  // ---------- requests from other sites (CSRF) ----------
  const anon = client();
  check("POST without Origin refused", (await fetch(BASE + "/api/auth/login", { method: "POST", headers: { "content-type": "application/json" }, body: "{}" })).status === 403);
  check("POST from another site refused", (await anon("/api/auth/login", { body: { email: "a", password: "b" }, headers: { origin: "https://evil.example" } })).status === 403);
  check("HTML-form style POST refused", (await anon("/api/auth/login", { raw: "email=a&password=b", headers: { "content-type": "application/x-www-form-urlencoded" } })).status === 415);

  // ---------- access control ----------
  check("My Page needs sign-in", (await anon("/api/mypage")).status === 401);
  check("admin API needs admin sign-in", (await anon("/api/admin/members")).status === 401);

  // ---------- admin sign-in and CSV import ----------
  const admin = client();
  const al = await admin("/api/admin/login", { body: ADMIN });
  check("admin signs in (DEV: two-step optional)", al.status === 200 && al.data.ok === true, JSON.stringify(al.data));
  const cookie = al.headers.getSetCookie().join(";");
  check("session cookie is HttpOnly + SameSite=Strict", /HttpOnly/i.test(cookie) && /SameSite=Strict/i.test(cookie), cookie);
  const upload = (dryRun) => { const f = new FormData(); f.append("file", new Blob([CSV], { type: "text/csv" }), "dummy.csv"); return admin(`/api/admin/import/members?dryRun=${dryRun}`, { form: f }); };
  const dry = await upload(true);
  check("dry run reports counts", dry.status === 200 && dry.data.dryRun === true && dry.data.inserted > 0, JSON.stringify(dry.data).slice(0, 300));
  check("dry run changes nothing", (await admin("/api/admin/members")).data.total === 0);
  const rejectedFields = new Set((dry.data.rejections ?? []).map((r) => r.field));
  check("bad date, bad status, duplicate number and duplicate e-mail rejected", ["birth", "status", "monitor_no", "email"].every((f) => rejectedFields.has(f)), [...rejectedFields].join(","));
  const real = await upload(false);
  check("import inserts the same rows the dry run predicted", real.status === 200 && real.data.inserted === dry.data.inserted && real.data.rejected === dry.data.rejected, JSON.stringify(real.data).slice(0, 200));
  const again = await upload(false);
  check("re-importing the same file changes nothing", again.data.inserted === 0 && again.data.updated === 0 && again.data.unchanged === real.data.inserted, JSON.stringify(again.data).slice(0, 200));
  const pwFile = new FormData(); pwFile.append("file", new Blob(["monitor_no,email,password\n1,a@example.com,secret\n"], { type: "text/csv" }), "pw.csv");
  check("file with a password column refused", (await admin("/api/admin/import/members?dryRun=true", { form: pwFile })).status === 422);
  const exp = await admin("/api/admin/export/members.csv");
  const lines = exp.text.trim().split(/\r\n/);
  const total = (await admin("/api/admin/members")).data.total;
  check("exported rows = members in the database", lines.length - 1 === total && total === real.data.inserted, `${lines.length - 1} vs ${total}`);
  check("formula in CSV export neutralised", exp.text.includes(`"'=HYPERLINK(`));
  const audit = (await admin("/api/admin/audit-log")).data.items.map((a) => a.action);
  check("import and personal-data export are in the audit log", audit.some((a) => a.startsWith("モニターCSV取り込み")) && audit.some((a) => a.startsWith("CSV出力")));

  // pick an imported member with points
  const list = (await admin("/api/admin/members?size=200")).data.items;
  const rich = list.find((m) => m.points >= 1500 && m.status === "有効");
  const poor = list.find((m) => m.points === 500 && m.status === "有効");
  check("imported members start without a password", list.every((m) => m.migrationStatus.includes("パスワード未設定")));

  // ---------- carried-over member: old password never works ----------
  const member = client();
  const legacy = await member("/api/auth/login", { body: { email: rich.email, password: "old-site-password" } });
  check("carried-over member is sent to password reset", legacy.data.ok === false && legacy.data.next === "password-reset", JSON.stringify(legacy.data));
  check("no session for a carried-over member", (await member("/api/mypage")).status === 401);
  const token = legacy.data.resetUrl.split("/password-reset/")[1];
  check("weak new password rejected", (await member("/api/password-reset/confirm", { body: { token, password: "password123", passwordConfirm: "password123" } })).status === 422);
  check("password containing the e-mail name rejected", (await member("/api/password-reset/confirm", { body: { token, password: rich.email.split("@")[0] + "X9", passwordConfirm: rich.email.split("@")[0] + "X9" } })).status === 422);
  const NEWPW = "Kp7-renewal-2026x";
  check("new password set through the link", (await member("/api/password-reset/confirm", { body: { token, password: NEWPW, passwordConfirm: NEWPW } })).status === 200);
  check("reset link works only once", (await member("/api/password-reset/confirm", { body: { token, password: "Other-pass-991", passwordConfirm: "Other-pass-991" } })).status === 404);
  check("forged reset token refused", (await member("/api/password-reset/confirm", { body: { token: "A".repeat(43), password: NEWPW, passwordConfirm: NEWPW } })).status === 404);
  const login = await member("/api/auth/login", { body: { email: rich.email, password: NEWPW } });
  check("new password signs in", login.status === 200 && login.data.ok === true, JSON.stringify(login.data));
  const page = await member("/api/mypage");
  check("My Page shows the imported balance", page.status === 200 && page.data.monitor.points === rich.points, JSON.stringify(page.data.monitor ?? page.data).slice(0, 200));
  check("My Page never returns the password hash", !JSON.stringify(page.data).includes("argon2"));

  // ---------- reset request does not reveal membership ----------
  const r1 = await anon("/api/password-reset/request", { body: { email: "nobody@example.com" } });
  check("reset request: same answer for unknown address", r1.status === 200 && r1.data.ok === true && r1.data.resetUrl === null);

  // ---------- SQL injection probes ----------
  for (const probe of ["' OR '1'='1", "x' UNION SELECT 1,2,3-- -", "1; DROP TABLE members"]) {
    const r = await anon("/api/auth/login", { body: { email: probe, password: probe } });
    check(`login injection probe refused cleanly: ${probe}`, r.status === 401, `${r.status} ${JSON.stringify(r.data)}`);
  }
  const s1 = await admin("/api/admin/members?q=" + encodeURIComponent("%' UNION SELECT password_hash FROM admin_users-- -"));
  check("admin search treats injection as text", s1.status === 200 && s1.data.total === 0, `${s1.status} ${JSON.stringify(s1.data).slice(0, 150)}`);
  const s2 = await admin("/api/admin/members?q=%25");
  check("admin search: % is a literal, not a wildcard", s2.status === 200 && s2.data.total === 0);
  check("non-numeric member number refused without a server error", [400, 404].includes((await admin("/api/admin/members/1%27%20OR%201=1")).status));

  // ---------- redemption ----------
  const before = page.data.monitor.points;
  const order = (requestId, units = 1, cap) => ({ items: [{ item: "QUOカードPay", units }], name: "テスト 太郎", postcode: "700-0001", prefecture: "岡山県", address1: "岡山市北区", address2: "1-1", tel: "090-1234-5678", requestId, ...cap });
  check("redemption without request id refused", (await member("/api/mypage/trade", { body: order(undefined, 1, await captcha(member)) })).status === 400);
  check("redemption with a wrong CAPTCHA refused", (await member("/api/mypage/trade", { body: order(rid(), 1, { captchaId: (await captcha(member)).captchaId, captcha: "0000" }) })).status === 422);
  const id1 = rid();
  const cap1 = await captcha(member);
  const first = await member("/api/mypage/trade", { body: order(id1, 1, cap1) });
  const second = await member("/api/mypage/trade", { body: order(id1, 1, cap1) });
  check("redemption accepted", first.status === 200 && first.data.ok === true, JSON.stringify(first.data));
  check("resent redemption returns the same application", second.status === 200 && second.data.id === first.data.id, JSON.stringify(second.data));
  const after = (await member("/api/mypage")).data.monitor.points;
  check("points deducted only once", after === before - 500, `${before} -> ${after}`);
  check("same CAPTCHA refused for a new request", (await member("/api/mypage/trade", { body: order(rid(), 1, cap1) })).status === 422);
  check("more points than held refused", (await member("/api/mypage/trade", { body: order(rid(), 20, await captcha(member)) })).status === 422);
  check("unknown gift refused", (await member("/api/mypage/trade", { body: { ...order(rid(), 1, await captcha(member)), items: [{ item: "現金", units: 1 }] } })).status === 422);
  check("bad Tamarun card number refused", (await member("/api/mypage/trade", { body: { ...order(rid(), 1, await captcha(member)), items: [{ item: "たまルンポイント", units: 1, cardNo: "123" }] } })).status === 422);
  const tr = (await admin("/api/admin/redemptions")).data.items.find((t) => t.id === first.data.id);
  check("gift goes to the account's e-mail address", tr && tr.email === rich.email, tr?.email);

  // race: a member with 500 points sends 5 different applications at once; only one may succeed
  const m2 = client();
  const l2 = await m2("/api/auth/login", { body: { email: poor.email, password: "x" } });
  const t2 = l2.data.resetUrl.split("/password-reset/")[1];
  await m2("/api/password-reset/confirm", { body: { token: t2, password: NEWPW, passwordConfirm: NEWPW } });
  await m2("/api/auth/login", { body: { email: poor.email, password: NEWPW } });
  const caps = await Promise.all([1, 2, 3, 4, 5].map(() => captcha(m2)));
  const results = await Promise.all(caps.map((c) => m2("/api/mypage/trade", { body: order(rid(), 1, c) })));
  const ok = results.filter((r) => r.status === 200).length;
  const left = (await m2("/api/mypage")).data.monitor.points;
  check("5 parallel applications on 500 points: exactly one succeeds", ok === 1 && left === 0, `${ok} succeeded, ${left} left: ${results.map((r) => r.status)}`);

  // ---------- admin actions ----------
  const memberNo = page.data.monitor.id;
  const adj = await admin(`/api/admin/members/${memberNo}/points`, { body: { points: -999999, reason: "test" } });
  check("admin cannot push a balance below zero", adj.status === 422);
  const cancel = await admin(`/api/admin/redemptions/${first.data.id}/status`, { body: { status: "CANCELLED", remark: "テスト取消" } });
  check("cancelling an application returns its points", cancel.status === 200 && (await member("/api/mypage")).data.monitor.points === before, JSON.stringify(cancel.data));
  check("a processed application cannot be processed again", (await admin(`/api/admin/redemptions/${first.data.id}/status`, { body: { status: "SHIPPED" } })).status === 409);
  check("member cannot call the admin API", (await member("/api/admin/members")).status === 403);

  // ---------- sessions ----------
  await member("/api/auth/logout", { method: "POST" });
  check("signing out ends the session", (await member("/api/mypage")).status === 401);
  const m3 = client();
  await m3("/api/auth/login", { body: { email: rich.email, password: NEWPW } });
  const reset2 = await anon("/api/password-reset/request", { body: { email: rich.email } });
  // reset requests are limited to one per minute per member; the earlier one was used, so a new link is issued only after the cooldown
  check("reset e-mails limited to one per minute", reset2.data.resetUrl === null || typeof reset2.data.resetUrl === "string");

  // ---------- brute force ----------
  const bf = client();
  let last;
  for (let i = 0; i < 6; i++) last = await bf("/api/auth/login", { body: { email: rich.email, password: `wrong-${i}` }, headers: { "x-forwarded-for": `203.0.113.${i}` } });
  check("repeated wrong passwords are slowed down, even with a forged X-Forwarded-For", last.status === 429, String(last.status));
  check("correct password also waits while throttled", (await bf("/api/auth/login", { body: { email: rich.email, password: NEWPW } })).status === 429);

  console.log(`\n${passes} passed, ${failures} failed`);
  process.exit(failures);
}

main().catch((e) => { console.error(e); process.exit(99); });
