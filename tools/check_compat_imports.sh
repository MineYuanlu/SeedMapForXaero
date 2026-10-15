#!/usr/bin/env bash
# Guardrail: 版本敏感 MC API 只允许在 client/compat/ 内 import。
#
# universal jar 只编一个版本：搬家类型（blaze3d.textures/systems/pipeline、
# renderpearl、levelgen.synth）的强类型引用会产生跨版本断裂的方法/字段描述符
# （编译期不可见，运行时 NoSuchMethod/FieldError）。这些缝只允许出现在
# client/compat/（经反射封装）。设计见 doc/compat-layer.md。
#
# 用法: tools/check_compat_imports.sh   （在 CI 与本地均可直接运行）
set -euo pipefail

cd "$(dirname "$0")/.."

VIOLATIONS=$(grep -rlnE \
  'import com\.mojang\.blaze3d\.(textures|systems|pipeline)\.|import com\.mojang\.renderpearl\.|import net\.minecraft\.world\.level\.levelgen\.synth\.' \
  src/client/java --include='*.java' | grep -v '/client/compat/' || true)

if [ -n "$VIOLATIONS" ]; then
  echo "::error::版本敏感 API 只允许在 client/compat/ 内 import（见 doc/compat-layer.md）:"
  echo "$VIOLATIONS"
  exit 1
fi

echo "guardrail ok: no version-sensitive imports outside client/compat/"
