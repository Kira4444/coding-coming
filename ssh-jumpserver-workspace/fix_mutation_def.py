#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修正 redact-key-list 这条变异：原来只删了别名却留下 token，测不出问题。"""
import pathlib

P = pathlib.Path(
    r"C:/Users/50822/Desktop/coding coming/ssh-jumpserver-workspace/mutation_matrix.py"
)
s = P.read_text(encoding="utf-8")

marker = '"redact-key-list",'
i = s.index(marker)
# 这条元组从 marker 往前到它的左括号，往后到右括号+逗号
start = s.rindex("    (\n", 0, i)
end = s.index("\n    ),\n", i) + len("\n    ),\n")

replacement = """    (
        "redact-key-list",
        "SECRET_KEYS 缩回四个原始键，token/secret 等别名全丢",
        "  'passphrase',\\n  'token',\\n  'secret',\\n  'key',\\n  'apiKey',\\n  'credential',\\n  'pass',",
        "  'passphrase',",
    ),
"""

P.write_text(s[:start] + replacement + s[end:], encoding="utf-8")
print("mutation definition fixed")
print(P.read_text(encoding="utf-8")[start : start + 400])
