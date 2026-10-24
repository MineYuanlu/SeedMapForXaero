#!/usr/bin/env bash
# 下载 matrix-test 的 E2E 产物（logs / screenshots / universal-jar），
# 并把不同 MC 版本同一阶段的截图拼成尽量接近正方形的网格图。
#
# 用法:
#   ./scripts/download_e2e.sh [commit/branch/tag]
#
# 默认使用当前分支。产物保存到:
#   ./tmp/ci/<完整 commit>/{logs,screenshots,universal-jar,comb}/
set -euo pipefail

readonly WORKFLOW="matrix-test.yml"

warn() {
    printf '[download_e2e] WARN: %s\n' "$*" >&2
}

die() {
    printf '[download_e2e] ERROR: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
用法: ./scripts/download_e2e.sh [commit/branch/tag]

  commit/branch/tag  要下载的版本，默认当前分支。
                     分支/tag 会解析到其远端最新 commit，
                     然后取该 commit 最新的已完成 matrix-test 运行。

输出:
  ./tmp/ci/<commit>/logs/<mc>/gametest.log
  ./tmp/ci/<commit>/screenshots/<mc>/*.png
  ./tmp/ci/<commit>/universal-jar/*.jar
  ./tmp/ci/<commit>/comb/<stage>.png   不同 MC 版本同一阶段的网格拼图（尽量正方形）
  ./tmp/ci/<commit>/run.json           CI run 元数据
EOF
}

need() {
    command -v "$1" >/dev/null 2>&1 || die "缺少命令: $1"
}

repo_root=$(git rev-parse --show-toplevel 2>/dev/null) || die "当前目录不在 git 仓库中"
cd "$repo_root"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi
if (($# > 1)); then
    usage >&2
    exit 2
fi

need git
need gh
gh auth status >/dev/null 2>&1 || die "gh 未登录，先执行 gh auth login"

repo=$(gh repo view --json nameWithOwner -q .nameWithOwner) || die "无法识别 GitHub 仓库"

ref="${1:-}"
if [[ -z "$ref" ]]; then
    ref=$(git branch --show-current)
    [[ -n "$ref" ]] || ref=$(git rev-parse HEAD)
fi

resolve_commit() {
    local ref="$1" sha=""

    if [[ "$ref" == "HEAD" ]]; then
        git rev-parse --verify "${ref}^{commit}" 2>/dev/null && return 0
    fi

    # 本地已有的 commit / 短 SHA（分支名不优先用本地，避免本地领先但未推送）
    if [[ "$ref" =~ ^[0-9a-fA-F]{7,40}$ ]]; then
        if sha=$(git rev-parse --verify --quiet "${ref}^{commit}" 2>/dev/null); then
            printf '%s\n' "$sha"
            return 0
        fi
    fi

    # 远端分支
    sha=$(git ls-remote origin "refs/heads/${ref}" 2>/dev/null | awk 'NR==1 { print $1 }')
    if [[ -n "$sha" ]]; then
        printf '%s\n' "$sha"
        return 0
    fi

    # 附注 tag -> 解引用到 commit
    sha=$(git ls-remote origin "refs/tags/${ref}^{}" 2>/dev/null | awk 'NR==1 { print $1 }')
    if [[ -n "$sha" ]]; then
        printf '%s\n' "$sha"
        return 0
    fi

    # 轻量 tag
    sha=$(git ls-remote origin "refs/tags/${ref}" 2>/dev/null | awk 'NR==1 { print $1 }')
    if [[ -n "$sha" ]]; then
        printf '%s\n' "$sha"
        return 0
    fi

    # 其它 ref（例如远端默认分支）
    sha=$(git ls-remote origin "${ref}" 2>/dev/null | awk 'NR==1 { print $1 }')
    if [[ -n "$sha" ]]; then
        printf '%s\n' "$sha"
        return 0
    fi

    # 远端存在但本地未 fetch 的 commit
    sha=$(gh api "repos/${repo}/commits/${ref}" -q .sha 2>/dev/null) || sha=""
    if [[ -n "$sha" ]]; then
        printf '%s\n' "$sha"
        return 0
    fi

    # 本地 ref 兜底
    if sha=$(git rev-parse --verify --quiet "${ref}^{commit}" 2>/dev/null); then
        printf '%s\n' "$sha"
        return 0
    fi

    return 1
}

commit=$(resolve_commit "$ref") || die "无法解析 ref: $ref"
base="tmp/ci/${commit}"

# 取该 commit 最新的已完成运行；若没有，退回最新运行（可能仍在进行）。
run_id=$(gh run list --workflow "$WORKFLOW" --commit "$commit" --limit 30 \
    --json databaseId,status \
    -q '[.[] | select(.status == "completed")][0].databaseId // empty')

if [[ -z "$run_id" ]]; then
    run_id=$(gh run list --workflow "$WORKFLOW" --commit "$commit" --limit 1 \
        --json databaseId -q '.[0].databaseId // empty')
    [[ -n "$run_id" ]] || die "未找到 commit ${commit} 的 ${WORKFLOW} 运行"
    warn "没有已完成的运行，使用最新运行 ${run_id}（产物可能不完整）"
fi

run_json=$(gh run view "$run_id" \
    --json databaseId,status,conclusion,url,headBranch,headSha,createdAt,updatedAt)
run_url=$(gh run view "$run_id" --json url -q .url)
run_conclusion=$(gh run view "$run_id" --json conclusion -q '.conclusion // "-"')

mkdir -p "$base"
rm -rf "$base/logs" "$base/screenshots" "$base/universal-jar" "$base/comb"
mkdir -p "$base/logs" "$base/screenshots" "$base/universal-jar" "$base/comb"
printf '%s\n' "$run_json" > "$base/run.json"

artifacts=$(gh api "repos/${repo}/actions/runs/${run_id}/artifacts" --paginate \
    -q '.artifacts[].name')
[[ -n "$artifacts" ]] || die "运行 ${run_id} 没有可下载的 artifact（可能已过期）"

has_artifact() {
    grep -qxF "$1" <<<"$artifacts"
}

download_artifact() {
    local name="$1" dest="$2"
    if ! has_artifact "$name"; then
        warn "缺少 artifact: $name"
        return 0
    fi
    mkdir -p "$dest"
    if ! gh run download "$run_id" -n "$name" -D "$dest"; then
        warn "下载失败: $name"
    fi
}

versions=()
while IFS= read -r v; do
    versions+=("$v")
done < <(
    printf '%s\n' "$artifacts" \
        | sed -nE 's/^client-gametest-(log|screenshots)-(.+)$/\2/p' \
        | sort -u
)

if ((${#versions[@]} == 0)); then
    warn "没有 client-gametest-* artifact，跳过 logs/screenshots/comb"
else
    for v in "${versions[@]}"; do
        download_artifact "client-gametest-log-${v}" "$base/logs/${v}"
        download_artifact "client-gametest-screenshots-${v}" "$base/screenshots/${v}"
    done
fi

download_artifact "universal-jar" "$base/universal-jar"

# 按 MC 版本排序（26.1 < 26.1.1 < 26.1.2 < 26.2 < 26.3）。
sort_versions() {
    if sort -V </dev/null >/dev/null 2>&1; then
        sort -V
    else
        sort
    fi
}

sorted_versions=()
if ((${#versions[@]} > 0)); then
    while IFS= read -r v; do
        sorted_versions+=("$v")
    done < <(printf '%s\n' "${versions[@]}" | sort_versions)
fi

# 收集所有阶段名（文件名去掉数字前缀和 .png）。
stages_tmp=$(mktemp)
sorted_stages=$(mktemp)
cleanup() {
    rm -f "$stages_tmp" "$sorted_stages"
}
trap cleanup EXIT

for v in "${sorted_versions[@]}"; do
    dir="$base/screenshots/${v}"
    [[ -d "$dir" ]] || continue
    for img in "$dir"/*.png; do
        [[ -e "$img" ]] || continue
        name=$(basename "$img")
        stage="${name#*_}"
        stage="${stage%.png}"
        [[ -n "$stage" ]] && printf '%s\n' "$stage" >> "$stages_tmp"
    done
done

if [[ ! -s "$stages_tmp" ]]; then
    warn "没有截图，跳过 comb 拼图"
elif ! command -v montage >/dev/null 2>&1 || ! command -v identify >/dev/null 2>&1; then
    warn "缺少 ImageMagick (montage/identify)，跳过 comb 拼图"
else
    sort -u "$stages_tmp" > "$sorted_stages"
    pointsize=20
    while IFS= read -r stage; do
        args=()
        count=0
        first_img=""
        for v in "${sorted_versions[@]}"; do
            dir="$base/screenshots/${v}"
            [[ -d "$dir" ]] || continue
            for img in "$dir"/*.png; do
                [[ -e "$img" ]] || continue
                name=$(basename "$img")
                candidate="${name#*_}"
                candidate="${candidate%.png}"
                if [[ "$candidate" == "$stage" ]]; then
                    args+=(-label "$v" "$img")
                    count=$((count + 1))
                    [[ -n "$first_img" ]] || first_img="$img"
                    break
                fi
            done
        done
        ((count > 0)) || continue

        # 以单张截图 + 标签高度估算 tile，选择总宽高比最接近 1 的列数。
        dims=$(identify -format '%w %h' "$first_img" 2>/dev/null || true)
        if [[ -n "$dims" ]]; then
            read -r tile_w tile_h <<<"$dims"
        else
            tile_w=854
            tile_h=480
        fi
        tile_h=$((tile_h + pointsize + 4))

        best_cols=1
        best_rows=$count
        best_score=0
        for ((cols=1; cols<=count; cols++)); do
            rows=$(((count + cols - 1) / cols))
            width=$((cols * tile_w))
            height=$((rows * tile_h))
            if ((width >= height)); then
                score=$((width * 1000 / height))
            else
                score=$((height * 1000 / width))
            fi
            if ((best_score == 0 || score < best_score)); then
                best_score=$score
                best_cols=$cols
                best_rows=$rows
            fi
        done

        montage "${args[@]}" \
            -tile "${best_cols}x${best_rows}" -geometry +0+0 \
            -background '#111111' -fill white -pointsize "$pointsize" \
            "$base/comb/${stage}.png"
    done < "$sorted_stages"
fi

printf '\n'
printf '运行:     %s\n' "$run_url"
printf '结论:     %s\n' "$run_conclusion"
printf 'commit:   %s\n' "$commit"
printf '保存到:   %s\n' "$base"
printf '  logs:           %s/logs\n' "$base"
printf '  screenshots:    %s/screenshots\n' "$base"
printf '  universal-jar:  %s/universal-jar\n' "$base"
printf '  comb:           %s/comb\n' "$base"
