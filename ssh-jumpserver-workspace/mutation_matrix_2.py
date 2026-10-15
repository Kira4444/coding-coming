#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第二轮变异矩阵：用独立狩猎挖出的「改坏」方式，验新补的测试有没有牙齿。"""
import json
import pathlib
import subprocess

SRC = pathlib.Path(
    r"C:/Users/50822/.claude/dev-mods/f51a9127-8860-48cf-be1c-a93d7b8eb66d/ssh-status/hooks/register.tsx"
)
PLUGIN = r"C:/Users/50822/.claude/dev-mods/f51a9127-8860-48cf-be1c-a93d7b8eb66d/ssh-status"

MUTATIONS = [
    ("isFailed-nested-isError", "isFailed 不再看 result.isError",
     "|| o.result?.isError === true", ""),
    ("isFailed-deny-weakened", "isFailed 把 deny 判成 === true（deny 实际是原因字符串）",
     "o.deny !== undefined", "o.deny === true"),
    ("connectionIds-empty-text", "「No active connections」不再当成空列表",
     "if (text.trim().startsWith('No active connections')) return []",
     "if (text.trim().startsWith('No active connections')) return undefined"),
    ("disconnect-explicit-wrong-field", "按 connectionId 断开时误比 name",
     ": explicit !== ''\n                  ? row.id === explicit", ": explicit !== ''\n                  ? row.name === explicit"),
    ("disconnect-skips-connecting", "断开时漏掉在建连接",
     "(row.state === 'connected' || row.state === 'connecting') &&\n              (closed !== undefined",
     "(row.state === 'connected') &&\n              (closed !== undefined"),
    ("merge-leaves-placeholder", "重连后占位行没删，留下幽灵",
     "markRows(\n              list.filter(r => r.id !== callId),", "markRows(\n              list,"),
    ("failed-ttl-invert", "failed 的 TTL 判反",
     "out = out.filter(s => s.state !== 'failed' || now - s.since < FAILED_TTL_MS)",
     "out = out.filter(s => s.state !== 'failed' || now - s.since >= FAILED_TTL_MS)"),
    ("keep-oldest-disconnected", "断开历史留最旧而不是最新",
     ".sort((a, b) => b.since - a.since)\n        .slice(0, KEEP_DISCONNECTED)",
     ".sort((a, b) => a.since - b.since)\n        .slice(0, KEEP_DISCONNECTED)"),
    ("cap-age-tiebreak", "同档内超限时丢最新的而不是最旧的",
     ".sort((a, b) => rank[a.state] - rank[b.state] || b.since - a.since)",
     ".sort((a, b) => rank[a.state] - rank[b.state] || a.since - b.since)"),
    ("redact-first-only", "redact 只替换第一处",
     "out = out.split(secret).join('***')", "out = out.replace(secret, '***')"),
    ("scrub-first-at", "scrub 取第一个 @ 而不是最后一个",
     "return noScheme.slice(noScheme.lastIndexOf('@') + 1)",
     "return noScheme.slice(noScheme.indexOf('@') + 1)"),
    ("username-unscrubbed", "username 不再脱敏",
     "const username = scrub(arg(e, 'username', 'user'))",
     "const username = arg(e, 'username', 'user')"),
    ("clear-tautology", "/ssh-status clear 的判断写成恒真",
     "list.filter(s => s.state !== 'failed' && s.state !== 'disconnected')",
     "list.filter(s => s.state !== 'failed' || s.state !== 'disconnected')"),
    (
        'connecting-evicted-first',
        'connecting 排在 connected 后面，撞上限先被挤掉',
        '      connected: 0,\n      connecting: 0,\n      failed: 1,\n      disconnected: 2,',
        '      connected: 0,\n      connecting: 1,\n      failed: 2,\n      disconnected: 3,',
    ),
    ("connect-reject-mislabel", "连接被拒时记成 disconnected 而不是 failed",
     "markRows(list, r => r.id === callId, {\n            state: 'failed',\n            lastError: 'call did not return',",
     "markRows(list, r => r.id === callId, {\n            state: 'disconnected',\n            lastError: 'call did not return',"),
]


def run_tests():
    out = subprocess.run(
        ["claude", "plugin", "test", PLUGIN],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=300,
    )
    text = (out.stdout or "") + (out.stderr or "")
    failed = None
    for line in text.splitlines():
        st = line.strip()
        if st.endswith("fail") and st.split() and st.split()[0].isdigit():
            failed = int(st.split()[0])
    return (failed == 0 if failed is not None else False), failed, text


def main():
    original = SRC.read_text(encoding="utf-8")
    ok, n, _ = run_tests()
    if not ok:
        print(f"BASELINE NOT GREEN (fail={n}) — refusing to mutate")
        return 1
    print("baseline green")

    results = []
    for mid, desc, old, new in MUTATIONS:
        if old not in original:
            results.append({"id": mid, "status": "SKIP_NO_MATCH"})
            print(f"[SKIP] {mid}: pattern not found")
            continue
        mutated = original.replace(old, new, 1)
        SRC.write_text(mutated, encoding="utf-8")
        try:
            ok, n, text = run_tests()
        except Exception as exc:
            ok, n, text = False, -1, f"{type(exc).__name__}: {exc}"
        finally:
            SRC.write_text(original, encoding="utf-8")
        status = "caught" if not ok else "NOT_CAUGHT"
        results.append({"id": mid, "desc": desc, "status": status, "fails": n})
        print(f"[{status}] {mid} — {desc}  (fail={n})")

    SRC.write_text(original, encoding="utf-8")
    print()
    missed = [r for r in results if r["status"] == "NOT_CAUGHT"]
    print(f"{len(results) - len(missed)}/{len(results)} caught; {len(missed)} slipped")
    for r in missed:
        print("  MISSED:", r["id"], "-", r["desc"])
    print(json.dumps(results, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
