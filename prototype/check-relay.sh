#!/bin/sh
# 语法体检：把 relay.html 里真正的 <script> 块抽出来交给 node --check。
#
# 为什么不能只写一句 sed：这个文件里 CSS 注释、注解文案都可能出现 "<script>" 字样
# （不是标签，只是文字），朴素的 `<script[^>]*>(.*?)</script>` 会从那个字面量开始切，
# 抽出半截 CSS 去给 node 解析，报一个跟真实问题无关的错。
# 这里改用「最后一个不带 src 的 <script> 块」，并且只认行首就是 <script> 的那一行。
set -e
cd "$(dirname "$0")"
python3 - <<'PY'
import re
src = open('relay.html', encoding='utf-8').read()
# 只认整行就是 <script> / </script> 的标记（本文件的写法），避免被文案里的字面量骗到
blocks = re.findall(r'^<script>\n(.*?)^</script>$', src, re.S | re.M)
assert blocks, "没找到 <script> 块"
js = "\n".join(blocks)
open('/tmp/relay-check.js', 'w', encoding='utf-8').write(js)
print("抽出 %d 个 script 块，%d 字符" % (len(blocks), len(js)))
PY
node --check /tmp/relay-check.js
echo "relay.html 语法 OK"
