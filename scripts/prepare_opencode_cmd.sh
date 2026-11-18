#!/usr/bin/env bash
# 仿照 opencode 命令模板展开逻辑（opencode packages/core/src/config/plugin/command.ts 的
# evaluateTemplate），供 `opencode run` 路径复用 .opencode/commands/*.md 模板——
# run 不展开命令模板（已实测），本脚本在 CI 里完成等价展开。
#
# 用法: scripts/prepare_opencode_cmd.sh <input> <output> [args...]
#
# 处理顺序与源码一致:
#   1. 剥离 frontmatter（仅当文件以 --- 开头），正文 trim
#   2. 参数替换（perl 单 pass，不回扫，语义对齐源码 argumentRegex）:
#      - $ARGUMENTS → 全量参数原文（[args...] 以空格连接）
#      - $N → 第 N 个参数；模板中最大编号的 N 吞尾（args.slice(N-1) 至末尾，opencode 语义）
#      - 模板无任何占位符且参数非空时，参数原文附加到模板尾部（源码同款）
#   3. shell 注入: !`cmd` → 在 repo root 执行（子 shell 隔离，每次执行前重置 cwd，
#      不被上一条命令的 cd 带偏），stdout+stderr 合并注入；非零退出码 → 报错退出
#      （源码报 Shell interpolation failed）
#
# 与源码的已知差异:
#   - 参数分词由调用方 shell 完成（源码用 argsRegex 支持引号包裹与 [Image N]，CLI 传参场景无需）
#   - 注入文本去尾部换行（bash $(...) 行为；源码保留原始 buffer）
set -euo pipefail

usage() {
  echo "usage: $0 <input> <output> [args...]" >&2
  exit 2
}

[ $# -ge 2 ] || usage
INPUT=$1
OUTPUT=$2
shift 2
ARGS=("$@")
INPUT_TEXT="$*" # $ARGUMENTS 语义: 原始全量参数

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

trim() {
  local s=$1
  s="${s#"${s%%[![:space:]]*}"}"
  s="${s%"${s##*[![:space:]]}"}"
  printf '%s' "$s"
}

# --- 1. 读入 + 剥 frontmatter ---
content=$(awk '
  NR==1 && $0=="---" { infm=1; next }
  infm { if ($0=="---") infm=0; next }
  { print }
' "$INPUT")
content=$(trim "$content")

# --- 2. 参数替换 ---
# 参数经环境变量传入 perl，避免 shell 引号转义破坏参数原文
export OPENCMD_NARGS="${#ARGS[@]}"
export OPENCMD_INPUT="$INPUT_TEXT"
i=0
for a in "${ARGS[@]}"; do
  export "OPENCMD_ARG_$i=$a"
  i=$((i + 1))
done
content=$(
  perl -e '
    my $n     = $ENV{OPENCMD_NARGS} // 0;
    my @args  = map { $ENV{"OPENCMD_ARG_" . $_} // "" } 0 .. $n - 1;
    my $input = $ENV{OPENCMD_INPUT} // "";
    my $c = do { local $/; <STDIN> };
    # placeholderRegex /\$([1-9]\d*)/g: 占位符最大编号（$ARGUMENTS 不参与）
    my @ph = $c =~ /\$([1-9]\d*)/g;
    my $has_ph        = @ph ? 1 : 0;
    my $last          = $has_ph ? (sort { $b <=> $a } @ph)[0] : 0;
    my $has_arguments = index($c, "\$ARGUMENTS") >= 0 ? 1 : 0;
    # argumentRegex /\$(?:ARGUMENTS|([1-9]\d*))/g 单 pass 替换
    $c =~ s{\$(?:ARGUMENTS|([1-9]\d*))}{
      my $m = $&;
      if ($m eq "\$ARGUMENTS") { $input }
      else {
        my $pos = $1;
        my $idx = $pos - 1;
        if    ($idx >= @args)    { "" }
        elsif ($pos == $last)    { join(" ", @args[$idx .. $#args]) }
        else                     { $args[$idx] }
      }
    }ge;
    # 无占位符且参数非空 → 参数原文附加到模板尾部（源码同款）
    if (!$has_ph && !$has_arguments && $input =~ /\S/) {
      $c = $c . "\n\n" . $input;
    }
    $c =~ s/\A\s+//s;
    $c =~ s/\s+\z//s;
    print $c;
  ' <<<"$content"
)

# --- 3. shell 注入 ---
# 源码 shellRegex /!`([^`]+)`/g；stdout+stderr 合并（源码 combineOutput: true）；
# 子 shell 内 cd 到 repo root，每条命令独立，互不污染
inject_shell() {
  local text=$1 out="" cmd result
  while [[ $text == *'!`'* ]]; do
    out+="${text%%'!`'*}"
    text="${text#*'!`'}"
    cmd="${text%%'`'*}"
    text="${text#*'`'}"
    result=$(cd "$REPO_ROOT" && eval "$cmd" 2>&1) || {
      code=$?
      echo "error: shell template failed (exit $code): $cmd" >&2
      exit 1
    }
    out+="$result"
  done
  printf '%s%s' "$out" "$text"
}
content=$(inject_shell "$content")

printf '%s' "$content" > "$OUTPUT"
