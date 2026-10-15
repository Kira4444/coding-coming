#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修好 mutation_matrix_2.py 里被拆断的那条变异定义。"""
import pathlib
import re

P = pathlib.Path(
    r"C:/Users/50822/Desktop/coding coming/ssh-jumpserver-workspace/mutation_matrix_2.py"
)
s = P.read_text(encoding="utf-8")

# 把从 (一直到下一 条 "connect-reject-mislabel" 之前的内容整段换掉
start = s.index('    ("connecting-evicted-first"')
end = s.index('    ("connect-reject-mislabel"')

replacement = (
    "    (\n"
    "        'connecting-evicted-first',\n"
    "        'connecting 排在 connected 后面，撞上限先被挤掉',\n"
    "        '      connected: 0,\\n      connecting: 0,\\n      failed: 1,\\n      disconnected: 2,',\n"
    "        '      connected: 0,\\n      connecting: 1,\\n      failed: 2,\\n      disconnected: 3,',\n"
    "    ),\n"
)

P.write_text(s[:start] + replacement + s[end:], encoding="utf-8")

# 校验能编译
import py_compile

py_compile.compile(str(P), doraise=True)
print("mutation_matrix_2.py repaired and compiles")
