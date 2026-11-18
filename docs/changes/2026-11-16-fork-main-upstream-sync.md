# Fork main 备份与上游对齐

## 授权与目标

2026-11-16，用户要求先备份现有提交历史，再将 `Dark-Li/cctr-exporter` 的 `main` 对齐 `magic-cucumber/cctr-exporter` 的 `main`。

## 提交与备份

| 项目 | 引用 | 提交 SHA |
| --- | --- | --- |
| 对齐前 fork | `Dark-Li/cctr-exporter:main` | `851f2083329b46e4a500da5271ba699511fc5b92` |
| GitHub 备份分支 | `Dark-Li/cctr-exporter:codex/backup-main-before-upstream-sync-20261116-851f208` | `851f2083329b46e4a500da5271ba699511fc5b92` |
| 本地备份分支 | `codex/backup-main-before-upstream-sync-20261116-851f208` | `851f2083329b46e4a500da5271ba699511fc5b92` |
| 上游 main | `magic-cucumber/cctr-exporter:main` | `7d00086e4c61d292951d2b1cdb7887aec5dad89d` |
| 对齐后 fork 与本地 main | `main` | `7d00086e4c61d292951d2b1cdb7887aec5dad89d` |

[GitHub 备份分支](https://github.com/Dark-Li/cctr-exporter/tree/codex/backup-main-before-upstream-sync-20261116-851f208)

## 执行与验证

- 先创建 GitHub 备份分支，并通过 `git ls-remote` 独立核验它指向原 fork main。
- GitHub 分支更新使用 `expected_sha=851f2083329b46e4a500da5271ba699511fc5b92` 和 `force=true`，保护并发更新。
- 更新后 `git ls-remote` 确认 fork main 指向 `7d00086e4c61d292951d2b1cdb7887aec5dad89d`，备份分支仍指向原提交。
- GitHub compare 返回 `status=identical`、`ahead_by=0`、`behind_by=0`、`total_commits=0`。
- 两个提交的 Git tree 均为 `bd16060d38452367208b5d1c09d7089ce6e718c3`。本地创建备份后，以预期旧 SHA 为条件更新 `refs/heads/main` 和 `refs/remotes/origin/main`。工作树和暂存区内容保留。
- 原 GitHub 匿名 REST 读取触发限流，后续使用已连接的 GitHub 工具及 Git 远端读取完成验证。SSH 远端读取临时禁用有故障的代理命令，保留严格主机校验。

## 本地未提交内容

同步前后，两份文档的状态和 SHA-256 完全一致。

| 文件 | 状态 | SHA-256 |
| --- | --- | --- |
| `docs/releases/1.1.3.md` | 已跟踪文件的未提交修改 | `1A360E1585A0632B4B4C6D4BE12E2FD834EAA56FCC8F5ADEA521CFFB0A161D53` |
| `docs/releases/1.1.5.md` | 未跟踪文件 | `C22578CC2559CEA58B6EFF9CDE6E090A93BAE8296B75EAEB02744CA0D9587E0B` |

本记录作为本地未跟踪文档留存。仓库分支对齐结果以本次验证时的远端状态为准。
