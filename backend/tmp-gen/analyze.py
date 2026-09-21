import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "tmp-gen/v1.html"
c = open(path, encoding="utf-8").read()

blocks = re.findall(r"<script\b[^>]*>([\s\S]*?)</script>", c, re.I)
print("脚本块数:", len(blocks))

code = blocks[0] if blocks else ""
print("第一块长度:", len(code))
print()

# 正则字面量（粗匹配）：/ ... /
regex_lit = re.compile(r"/(?:\\.|[^/\\\n\[]|\[(?:\\.|[^\]\\])*\])+/[gimsuy]*")
print("=== 含引号字符的正则字面量（字符串剥离逻辑会被带偏）===")
hit = 0
for mm in regex_lit.finditer(code):
    s = mm.group(0)
    if '"' in s or "'" in s:
        line = code[: mm.start()].count("\n") + 1
        print(f"  L{line}: {s}")
        hit += 1
if not hit:
    print("  （无）")
print()

print("=== 反引号模板字符串（前 8 段）===")
for i, mm in enumerate(re.finditer(r"`[^`]*`", code)):
    if i >= 8:
        break
    print("  ", mm.group(0)[:90].replace("\n", " "))
print()

# 简单计数器，复现 validate 的逻辑（不含剥离），便于定位差值来源
print("=== 按'剥离后'计数（模拟 stripStringsAndComments）===")


def strip(code):
    out = []
    i = 0
    n = len(code)
    while i < n:
        c = code[i]
        nxt = code[i + 1] if i + 1 < n else "\0"
        if c == "/" and nxt == "/":
            while i < n and code[i] != "\n":
                i += 1
        elif c == "/" and nxt == "*":
            i += 2
            while i + 1 < n and not (code[i] == "*" and code[i + 1] == "/"):
                i += 1
            i = min(n, i + 2)
        elif c in ("'", '"', "`"):
            q = c
            i += 1
            while i < n:
                if code[i] == "\\":
                    i += 2
                    continue
                if code[i] == q:
                    i += 1
                    break
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


s = strip(code)
print(
    "  小括号差 %d | 花括号差 %d | 方括号差 %d"
    % (
        s.count("(") - s.count(")"),
        s.count("{") - s.count("}"),
        s.count("[") - s.count("]"),
    )
)
print("  剥离后长度:", len(s), "/ 原始", len(code))
