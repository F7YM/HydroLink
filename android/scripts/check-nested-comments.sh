#!/usr/bin/env bash
# 检查 Kotlin/Java 注释里是否出现了会开启「嵌套块注释」的 "斜杠+星号"。
# Kotlin 的 /* */ 可嵌套：注释里写到 fetch('/api/hydro/worker/*') 这类文本，
# 会把后面整段代码注释掉，报错却是 "Unclosed comment"，很难一眼看出。
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
bad=0
while IFS= read -r f; do
  # 只看注释开头的行，且行内（非行首）出现 /*
  while IFS=: read -r ln text; do
    if printf '%s' "$text" | grep -qE '^[[:space:]]*(//|\*|/\*\*).*[^/*]/\*'; then
      echo "$f:$ln: $text"
      bad=$((bad+1))
    fi
  done < <(grep -anE '^[[:space:]]*(//|\*|/\*\*)' "$f" || true)
done < <(find "$root/app/src" -name '*.kt' -o -name '*.java')
if [ "$bad" -gt 0 ]; then
  echo "发现 $bad 处注释内含 /*，会把后续代码注释掉"
  exit 1
fi
echo "注释检查通过（无嵌套块注释陷阱）"
