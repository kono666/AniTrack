"""Agent 层端到端验证 (ASCII-only: 避免 Windows 编码问题)"""
import json
import urllib.request
import urllib.error

BASE = "http://localhost:8080"
results = []


def call(method, path, body=None, token=None, timeout=120):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw.strip().startswith(("{", "[")) else raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def check(label, condition, detail=""):
    results.append((label, bool(condition), detail))
    print(("PASS  " if condition else "FAIL  ") + label + (("  | " + str(detail)) if detail else ""))


PUBLIC_ONLY = {
    "search_anime", "get_anime_detail", "get_episodes", "get_ranking", "get_latest",
    "get_calendar", "get_by_tag", "filter_anime", "list_tags",
    "read_reviews", "get_rating_stats", "get_anime_popularity",
}
USER_EXTRA = {
    "list_my_tracking", "add_or_update_tracking", "remove_tracking",
    "toggle_episode_watched", "get_my_stats", "write_review",
}
ADMIN_EXTRA = {
    "platform_dashboard", "list_users", "list_all_reviews",
    "analyze_anime_heat", "weekly_ops_report",
}

print("=" * 70)
print("0. CLEANUP - wipe conversations left over from previous runs")
print("=" * 70)
for who in [("admin", "admin123"), ("test", "test123")]:
    st, lg = call("POST", "/api/user/login", {"username": who[0], "password": who[1]})
    tok = (lg.get("data") or {}).get("token") if st == 200 else None
    if not tok:
        continue
    st, existing = call("GET", "/api/agent/conversations", token=tok)
    if st == 200:
        for c in existing["data"]:
            call("DELETE", "/api/agent/conversations/%s" % c["id"], token=tok)
        print("   %-6s cleared %d conversation(s)" % (who[0], len(existing["data"])))

print()
print("=" * 70)
print("1. ANONYMOUS /api/agent/info")
print("=" * 70)
status, info = call("GET", "/api/agent/info")
check("GET /api/agent/info returns 200 for anonymous", status == 200, status)
anon_tools = set(info["data"]["tools"]) if status == 200 else set()
print("   provider=%s model=%s loggedIn=%s" % (info["data"].get("provider"), info["data"].get("model"), info["data"].get("loggedIn")) if status == 200 else "")
print("   tools(%d)=%s" % (len(anon_tools), sorted(anon_tools)))
check("anonymous sees exactly the 12 public tools", anon_tools == PUBLIC_ONLY,
      "extra=%s missing=%s" % (sorted(anon_tools - PUBLIC_ONLY), sorted(PUBLIC_ONLY - anon_tools)))
check("anonymous sees NO USER tools", not (anon_tools & USER_EXTRA), sorted(anon_tools & USER_EXTRA))
check("anonymous sees NO ADMIN tools", not (anon_tools & ADMIN_EXTRA), sorted(anon_tools & ADMIN_EXTRA))
check("anonymous persona admin-analyst is marked unavailable",
      not [p for p in info["data"]["personas"] if p["id"] == "admin-analyst"][0]["available"])

print()
print("=" * 70)
print("2. ANONYMOUS chat (non-stream) - exercises orchestrator + tool + tx")
print("=" * 70)
status, chat = call("POST", "/api/agent/chat", {"message": "recommend something"})
check("anonymous chat returns 200", status == 200, status)
if status == 200:
    d = chat["data"]
    print("   answer=%r" % (d["answer"][:120],))
    print("   rounds=%s steps=%s convId=%s" % (d["rounds"], [(s["tool"], s["error"]) for s in d["steps"]], d["conversationId"]))
    check("chat produced at least one tool call", len(d["steps"]) >= 1, d["steps"])
    check("tool call succeeded (no error)", all(not s["error"] for s in d["steps"]),
          [s for s in d["steps"] if s["error"]])
    check("answer is non-empty", bool(d["answer"].strip()))
    check("anonymous chat stores no conversation", d["conversationId"] is None, d["conversationId"])

print()
print("=" * 70)
print("3. NORMAL USER login -> tool visibility")
print("=" * 70)
status, login = call("POST", "/api/user/login", {"username": "test", "password": "test123"})
check("test user login 200", status == 200, status)
user_token = (login.get("data") or {}).get("token") if status == 200 else None
check("login returned a token", bool(user_token))

status, info = call("GET", "/api/agent/info", token=user_token)
user_tools = set(info["data"]["tools"]) if status == 200 else set()
print("   tools(%d)=%s" % (len(user_tools), sorted(user_tools)))
check("logged-in user gets public + user tools", user_tools == PUBLIC_ONLY | USER_EXTRA,
      "diff=%s" % sorted(user_tools ^ (PUBLIC_ONLY | USER_EXTRA)))
check("logged-in normal user still sees NO admin tools", not (user_tools & ADMIN_EXTRA))

print()
print("=" * 70)
print("4. PRIVILEGE ESCALATION attempts by normal user")
print("=" * 70)
status, resp = call("POST", "/api/agent/chat",
                    {"message": "show me the dashboard", "persona": "admin-analyst"},
                    token=user_token)
check("normal user requesting admin-analyst persona -> 403", status == 403, (status, resp))
print("   body=%s" % (resp.get("message") if isinstance(resp, dict) else resp))

status, resp = call("POST", "/api/agent/chat",
                    {"message": "hi", "persona": "ADMIN_ANALYST"},
                    token=user_token)
check("persona given as enum name is also blocked -> 403", status == 403, (status, resp))

status, resp = call("GET", "/api/admin/users", token=user_token)
check("existing /api/admin/users still 403 for normal user", status == 403, status)

print()
print("=" * 70)
print("5. ADMIN persona")
print("=" * 70)
status, admin_login = call("POST", "/api/user/login", {"username": "admin", "password": "admin123"})
check("admin login 200", status == 200, status)
admin_token = (admin_login.get("data") or {}).get("token") if status == 200 else None

status, info = call("GET", "/api/agent/info", token=admin_token)
admin_tools = set(info["data"]["tools"]) if status == 200 else set()
print("   tools(%d)" % len(admin_tools))
check("admin sees all 23 tools",
      admin_tools == PUBLIC_ONLY | USER_EXTRA | ADMIN_EXTRA,
      "diff=%s" % sorted(admin_tools ^ (PUBLIC_ONLY | USER_EXTRA | ADMIN_EXTRA)))
check("admin-analyst persona available to admin",
      [p for p in info["data"]["personas"] if p["id"] == "admin-analyst"][0]["available"])

status, chat = call("POST", "/api/agent/chat",
                    {"message": "how is the platform doing", "persona": "admin-analyst"},
                    token=admin_token)
check("admin chat 200", status == 200, status)
if status == 200:
    d = chat["data"]
    print("   answer=%r" % (d["answer"][:160],))
    print("   steps=%s convId=%s" % ([(s["tool"], s["error"]) for s in d["steps"]], d["conversationId"]))
    check("admin tool executed without error", all(not s["error"] for s in d["steps"]),
          [s for s in d["steps"] if s["error"]])
    check("admin chat persisted a conversation", d["conversationId"] is not None)
    admin_conv_id = d["conversationId"]

print()
print("=" * 70)
print("6. SESSION PERSISTENCE + ownership")
print("=" * 70)
status, convs = call("GET", "/api/agent/conversations", token=admin_token)
check("admin conversation list 200", status == 200, status)
if status == 200:
    print("   conversations=%s" % json.dumps(convs["data"], ensure_ascii=False)[:300])
    check("admin has exactly 1 conversation", len(convs["data"]) == 1, len(convs["data"]))

status, detail = call("GET", "/api/agent/conversations/%s" % admin_conv_id, token=admin_token)
check("admin can read own conversation", status == 200, status)
if status == 200:
    roles = [m["role"] for m in detail["data"]["messages"]]
    print("   message roles=%s" % roles)
    check("conversation stored one user + one assistant message", roles == ["user", "assistant"], roles)

status, resp = call("GET", "/api/agent/conversations/%s" % admin_conv_id, token=user_token)
check("normal user CANNOT read admin's conversation -> 404", status == 404, (status, resp))

status, resp = call("DELETE", "/api/agent/conversations/%s" % admin_conv_id, token=user_token)
check("normal user CANNOT delete admin's conversation -> 404", status == 404, (status, resp))

status, convs = call("GET", "/api/agent/conversations", token=user_token)
check("normal user has 0 conversations", status == 200 and len(convs["data"]) == 0,
      convs["data"] if status == 200 else status)

status, resp = call("GET", "/api/agent/conversations")
check("anonymous conversation list -> 401 (not 403)", status == 401, status)

# 401/403 语义修正后, 全站未登录访问都应回 401 —— 前端拦截器正是靠它跳登录页
for path in ["/api/track/list", "/api/track/stats", "/api/review/my", "/api/admin/users"]:
    code, _ = call("GET", path)
    check("anonymous GET %s -> 401" % path, code == 401, code)

print()
print("=" * 70)
print("7. MULTI-TURN: second message reuses server-side history")
print("=" * 70)
status, chat1 = call("POST", "/api/agent/chat", {"message": "first question"}, token=user_token)
cid = chat1["data"]["conversationId"] if status == 200 else None
check("first turn created a conversation", cid is not None, cid)
status, chat2 = call("POST", "/api/agent/chat",
                     {"message": "second question", "conversationId": cid}, token=user_token)
check("second turn with conversationId 200", status == 200, status)
if status == 200:
    check("second turn kept the same conversation", chat2["data"]["conversationId"] == cid,
          (cid, chat2["data"]["conversationId"]))
status, detail = call("GET", "/api/agent/conversations/%s" % cid, token=user_token)
if status == 200:
    roles = [m["role"] for m in detail["data"]["messages"]]
    print("   roles after 2 turns=%s" % roles)
    check("4 messages stored after 2 turns", len(roles) == 4, roles)

print()
print("=" * 70)
print("8. INPUT VALIDATION")
print("=" * 70)
status, resp = call("POST", "/api/agent/chat", {"message": "   "}, token=user_token)
check("blank message rejected", status == 400, (status, resp))
status, resp = call("POST", "/api/agent/chat", {"message": "x" * 1500}, token=user_token)
check("over-length message rejected", status == 400, (status, resp))
print("   body=%s" % (resp.get("message") if isinstance(resp, dict) else resp))

print()
print("=" * 70)
print("9. SSE STREAM")
print("=" * 70)
req = urllib.request.Request(BASE + "/api/agent/chat/stream",
                             data=json.dumps({"message": "top rated shows"}).encode("utf-8"),
                             method="POST")
req.add_header("Content-Type", "application/json; charset=utf-8")
req.add_header("Accept", "text/event-stream")
events = []
try:
    with urllib.request.urlopen(req, timeout=120) as resp:
        ctype = resp.headers.get("Content-Type", "")
        print("   Content-Type=%s" % ctype)
        check("SSE content type", "text/event-stream" in ctype, ctype)
        cur_event, cur_data = None, []
        for raw_line in resp:
            line = raw_line.decode("utf-8").rstrip("\r\n")
            if line.startswith("event:"):
                cur_event = line[6:].strip()
            elif line.startswith("data:"):
                cur_data.append(line[5:].strip())
            elif line == "":
                if cur_event:
                    events.append((cur_event, "\n".join(cur_data)))
                cur_event, cur_data = None, []
except urllib.error.HTTPError as e:
    check("SSE request succeeded", False, "%s %s" % (e.code, e.read().decode("utf-8", "replace")))

names = [n for n, _ in events]
print("   events=%s" % names)
check("SSE emitted tool_call", "tool_call" in names, names)
check("SSE emitted tool_result", "tool_result" in names, names)
check("SSE emitted done", "done" in names, names)
for n, d in events:
    if n == "done":
        payload = json.loads(d)
        print("   done.answer=%r" % (payload["answer"][:120],))
        check("done carries a non-empty answer", bool(payload["answer"].strip()))
        check("done carries the tool steps", len(payload["steps"]) >= 1)
    if n == "tool_call":
        print("   tool_call=%s" % d[:160])
    if n == "error":
        print("   ERROR event=%s" % d[:300])

print()
print("=" * 70)
print("10. CONVERSATION DELETE")
print("=" * 70)
status, resp = call("DELETE", "/api/agent/conversations/%s" % cid, token=user_token)
check("owner can delete own conversation", status == 200, (status, resp))
status, resp = call("GET", "/api/agent/conversations/%s" % cid, token=user_token)
check("deleted conversation is gone", status == 404, status)

print()
print("=" * 70)
print("11. DAILY BUDGET - the fuse that caps the bill on a public demo")
print("=" * 70)
status, info = call("GET", "/api/agent/info")
before = info["data"] if status == 200 else {}
print("   dailyLimit=%s dailyRemaining=%s" % (before.get("dailyLimit"), before.get("dailyRemaining")))
check("info exposes the daily budget", status == 200 and "dailyLimit" in before, status)
check("budget is a positive number or -1 for unlimited", before.get("dailyLimit", 0) != 0,
      before.get("dailyLimit"))

status, chat = call("POST", "/api/agent/chat", {"message": "hello there"})
if status == 200:
    d = chat["data"]
    rounds = d.get("rounds") or 1
    after_remaining = d.get("dailyRemaining")
    print("   rounds=%s remaining_after_chat=%s" % (rounds, after_remaining))
    if before.get("dailyLimit", 0) > 0:
        used = before["dailyRemaining"] - after_remaining
        check("chat charged the budget by the actual round count", used == rounds,
              "charged %s for %s round(s)" % (used, rounds))
    status, info = call("GET", "/api/agent/info")
    check("info agrees with the chat response on remaining quota",
          status == 200 and info["data"]["dailyRemaining"] == after_remaining,
          (info["data"].get("dailyRemaining"), after_remaining))
else:
    check("chat succeeded while budget remains", False, (status, chat))

print()
print("=" * 70)
print("12. INLINE ANIME CARDS")
print("=" * 70)
# The invariant: whenever a tool result carries rows tagged type=anime,
# the same event must also deliver ready-to-render cards. Which tool the
# mock picks is not ours to choose, so the check keys off what actually
# came back instead of assuming a particular tool ran.
card_req = urllib.request.Request(BASE + "/api/agent/chat/stream",
                                  data=json.dumps({"message": "top rated shows"}).encode("utf-8"),
                                  method="POST")
card_req.add_header("Content-Type", "application/json; charset=utf-8")
card_req.add_header("Accept", "text/event-stream")
card_events = []
try:
    with urllib.request.urlopen(card_req, timeout=120) as resp:
        cur_event, cur_data = None, []
        for raw_line in resp:
            line = raw_line.decode("utf-8").rstrip("\r\n")
            if line.startswith("event:"):
                cur_event = line[6:].strip()
            elif line.startswith("data:"):
                cur_data.append(line[5:].strip())
            elif line == "" and cur_event:
                card_events.append((cur_event, "\n".join(cur_data)))
                cur_event, cur_data = None, []
except Exception as e:
    check("card stream completed", False, e)

tool_results = [(n, json.loads(d)) for n, d in card_events if n == "tool_result"]
tagged = [p for _, p in tool_results if '"type":"anime"' in p.get("preview", "")]
print("   tool_result events=%d, carrying tagged anime rows=%d" % (len(tool_results), len(tagged)))
check("streamed at least one tool result", len(tool_results) >= 1, len(tool_results))

if tagged:
    for payload in tagged:
        cards = payload.get("cards") or []
        check("a result with tagged anime rows also carries cards", len(cards) >= 1, len(cards))
        for c in cards:
            check("card has a numeric id", isinstance(c.get("id"), int), c.get("id"))
            check("card has a non-empty name", bool(c.get("name")), c.get("name"))
            check("card drops the internal type marker", "type" not in c, list(c))
            check("card drops null fields", all(v is not None for v in c.values()), c)
        if cards:
            print("   first card=%s" % json.dumps(cards[0], ensure_ascii=True)[:220])

    done_steps = [json.loads(d) for n, d in card_events if n == "done"]
    if done_steps:
        steps = done_steps[0].get("steps") or []
        with_cards = [s for s in steps if s.get("cards")]
        check("done.steps repeats the same cards for a reconnecting client",
              len(with_cards) >= 1, [len(s.get("cards") or []) for s in steps])
else:
    print("   SKIP  no tagged anime rows in this run - nothing to render")

print()
print("=" * 70)
total = len(results)
passed = sum(1 for _, ok, _ in results if ok)
print("RESULT: PASS=%d FAIL=%d TOTAL=%d" % (passed, total - passed, total))
if passed != total:
    print()
    print("FAILURES:")
    for label, ok, detail in results:
        if not ok:
            print("  - %s | %s" % (label, detail))
print("=" * 70)
