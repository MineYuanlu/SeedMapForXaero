---
description: 生成最新的发布记录 /release-notes vX.X.X(旧版) vX.X.X(新版)
agent: plan
---

# 任务: 生成发布说明

你正在为 Minecraft Fabric 模组 “Seed Map For Xaero” 编写/修改 GitHub 发布说明。

仓库工作树已签出至标签 $2 —— 文件内容反映的是该版本状态:
!`git checkout "$2" &>/dev/null && echo "HEAD detached at $2"`

你的任务：为 $2 撰写发布说明，即标签 $1 与标签 $2 之间的增量变更。

这是一个顺序的多版本发布流程。此前各版本（因此 $2 是**增量**更新）：
!`curl -s "https://api.github.com/repos/MineYuanlu/SeedMapForXaero/releases?per_page=20&page=1" | jq -r '.[].name'`

$2 中的提交（数据来自于 `git log --oneline --no-merges $1..$2`）：
```
!`git log --oneline --no-merges $1..$2`
```

## 方法：
1. 对每个提交执行 `git show <commit> --stat`，然后**阅读**工作树中变更的文件（状态即 $2）。
2. 准确弄清楚玩家能感知到的功能/改变是什么, 从代码中验证。
3. 将每项变更分类为**玩家可见** 与 **开发者可见**。

输出格式——精简、双语、**中文优先**、**不使用表情符号**、**最后包含“Full Changelog”行**。中文列表 + 英文列表，**不要**写成“中文 / English”在同一行：

```markdown
# $2 · 中文标题 / English Title

**面向玩家 / For Players**

- 中文要点1
- 中文要点2

- English bullet 1
- English bullet 2

<details>
<summary>开发细节 / Developer Details</summary>

- 中文要点

- English bullets

</details>

**Full Changelog**: https://github.com/MineYuanlu/SeedMapForXaero/compare/$1...$2
```

## 指南：
- 以中文为主；每个部分 = 一个中文项目符号列表，然后一个空行，再一个英文项目符号列表。
- 将相关提交合并为有意义的条目；保持要点简洁。
- 只有确实为空时才省略某个部分。
- 标题保持简短的双语一行。
- 确保准确对应实际代码——阅读源码，不要猜测。尤其要验证确切的玩家能感知到的部分（HUD元素、操作变化、内容变化等）。

直接输出最终的 Markdown 发布说明标题和正文作为你的最终消息。


## 历史参考：
仅作为格式上和风格上的参考, 以下是 $1 的 Release Notes 内容：

```markdown
!`curl -s "https://api.github.com/repos/MineYuanlu/SeedMapForXaero/releases/tags/$1" | jq -r '"# " + .name + "\n\n" + .body'`
```

## 用户附加信息:
以下是用户提供的额外信息，如果有，你应该优先遵守：
```
$ARGUMENTS
```