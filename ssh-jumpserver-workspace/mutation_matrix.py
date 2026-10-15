#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""变异矩阵：逐个改坏 ssh-status 的修复点，看测试会不会报警。

每条变异只动一处；跑完立刻还原。输出 caught / NOT_CAUGHT。
"""
import json
import pathlib
import subprocess
import sys

MOD = pathlib.Path(
    r"C:/Users/50822/.claude/dev-mods/f51a9127-8860-48cf-be1c-a93d7b8eb66d/ssh-status"
)
SRC = MOD / "hooks" / "register.tsx"
PLUGIN_DIR = str(MOD)

# (id, 描述, 要替换掉的原文, 换成什么)
MUTATIONS = [
    (
        "scrub-userinfo",
        "scrub 退回旧正则，密码带 / 或 @ 时漏出",
        "if (at > 0 && colon > 0 && colon < at) {\n    return noScheme.slice(noScheme.lastIndexOf('@') + 1)\n  }\n  return noScheme",
        "return noScheme.replace(/^[^@/:]+:[^@/]*@/, '')",
    ),
    (
        "redact-short-secret",
        "redact 对 1-3 位口令不再脱敏",
        "if (secret.length >= 4) {\n      out = out.split(secret).join('***')\n    } else {\n      const escaped = secret.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&')\n      out = out.replace(new RegExp(`(?<![A-Za-z0-9])${escaped}(?![A-Za-z0-9])`, 'g'), '***')\n    }",
        "if (secret.length >= 4) {\n      out = out.split(secret).join('***')\n    }",
    ),
    (
        "redact-key-list",
        "SECRET_KEYS 缩回四个原始键，token/secret 等别名全丢",
        "  'passphrase',\n  'token',\n  'secret',\n  'key',\n  'apiKey',\n  'credential',\n  'pass',",
        "  'passphrase',",
    ),
    (
        "redact-userinfo-sweep",
        "redact 不再扫结果文本里的 user:pass@",
        "return out.replace(/\\b[^\\s:@]+:[^\\s]*@[^\\s]*/g, m => `***@${m.slice(m.lastIndexOf('@') + 1)}`)",
        "return out",
    ),
    (
        "resulttext-string-fallthrough",
        "resultText 不再认 result 是字符串的情况",
        "if (typeof payload === 'string') return payload",
        "if (false) return String(payload)",
    ),
    (
        "resulttext-text-field",
        "resultText 不再优先读 ran.text",
        "if (typeof outer.text === 'string' && outer.text.length > 0) return outer.text",
        "if (false) return String(outer.text)",
    ),
    (
        "disconnect-failed-gate",
        "ssh_disconnect 不再挡失败结果",
        "if (isFailed(ran)) return ran\n\n      const closed = closedIds(ran)",
        "const closed = closedIds(ran)",
    ),
    (
        "reconcile-skip-connecting",
        "reconcile 会误杀飞行中的 connecting 行",
        "row.state === 'connected' && !ids.includes(row.id)",
        "(row.state === 'connected' || row.state === 'connecting') && !ids.includes(row.id)",
    ),
    (
        "band-vanish-when-empty",
        "状态条在没事可报时不再让渡（会画出 SSH ○0）",
        "if (live.length + connecting.length + failed.length === 0) return next(e)",
        "if (false) return next(e)",
    ),
    (
        "hide-not-restored-by-command",
        "/ssh-status 不再把状态条唤回来",
        "await update($, isHidden, () => false)\n\n    try {\n      const opened = await $.ui.open(",
        "try {\n      const opened = await $.ui.open(",
    ),
    (
        "hide-not-restored-by-connect",
        "新连接不再唤回状态条",
        "await update($, isHidden, () => false)\n      await update($, sessions, list =>\n        prune([{ id: callId",
        "await update($, sessions, list =>\n        prune([{ id: callId",
    ),
    (
        "connectid-json-first",
        "extractConnectionId 不再先试 JSON，只走会截断的正则",
        "const parsed = jsonOf(text) as { connectionId?: unknown } | undefined\n  const fromJson = parsed?.connectionId\n  if (typeof fromJson === 'string' && fromJson !== '') return fromJson",
        "const fromJson = undefined",
    ),
    (
        "disconnect-no-id-closes-all",
        "省略 connectionId 时不再全关",
        ": explicit !== ''\n                  ? row.id === explicit\n                  : true)",
        ": explicit !== ''\n                  ? row.id === explicit\n                  : false)",
    ),
    (
        "prune-cap-order",
        "prune 超限时丢活连接、留死行",
        "rank[a.state] - rank[b.state]",
        "rank[b.state] - rank[a.state]",
    ),
    (
        "age-label",
        "ageOf 永远显示 0s",
        "if (s < 60) return s + 's'",
        "if (true) return '0s'",
    ),
]


def run_tests() -> tuple[bool, str]:
    out = subprocess.run(
        ["claude", "plugin", "test", PLUGIN_DIR],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=300,
    )
    text = (out.stdout or "") + (out.stderr or "")
    ok = ("0 fail" in text) and ("fail" not in text.split("Ran")[0].lower())
    # 更稳的判据：最后的计数行
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.endswith("fail") and stripped.split()[0].isdigit():
            ok = stripped.split()[0] == "0"
    return ok, text


def main() -> int:
    original = SRC.read_text(encoding="utf-8")
    results = []

    # 基线
    ok, text = run_tests()
    if not ok:
        print("BASELINE NOT GREEN — refusing to mutate")
        print(text[-2000:])
        return 1
    print("baseline green")

    for mut_id, desc, old, new in MUTATIONS:
        if old not in original:
            results.append({"id": mut_id, "desc": desc, "status": "SKIP_NO_MATCH", "detail": old[:60]})
            print(f"[SKIP] {mut_id}: pattern not found")
            continue

        mutated = original.replace(old, new, 1)
        if mutated == original:
            results.append({"id": mut_id, "desc": desc, "status": "SKIP_NO_EFFECT"})
            print(f"[SKIP] {mut_id}: no-op")
            continue

        SRC.write_text(mutated, encoding="utf-8")
        try:
            ok, text = run_tests()
        except Exception as exc:  # 变异导致编译/加载炸了，也算被抓到
            ok, text = False, f"{type(exc).__name__}: {exc}"
        finally:
            SRC.write_text(original, encoding="utf-8")

        status = "caught" if not ok else "NOT_CAUGHT"
        results.append({"id": mut_id, "desc": desc, "status": status})
        print(f"[{status}] {mut_id} — {desc}")

    SRC.write_text(original, encoding="utf-8")
    print()
    print(json.dumps(results, ensure_ascii=False, indent=2))

    missed = [r for r in results if r["status"] == "NOT_CAUGHT"]
    print(f"\n{len(results) - len(missed)}/{len(results)} mutations caught; {len(missed)} slipped through")
    for r in missed:
        print("  MISSED:", r["id"], "-", r["desc"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
