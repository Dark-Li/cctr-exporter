# Main 提交历史整理

## 目标与执行结果

2026-11-18，经用户授权，从 fork 的实际远端 main 创建干净分支，迁移两个普通提交，保留当前已提交文件内容并排除此前已被上游压缩合并的七个原始提交。

| 引用或检查点 | 提交 SHA |
| --- | --- |
| 执行前本地 main | `9ac1772f7ce3de7c16b214c3b5993ab71a17a51b` |
| 执行前 fork main 与上游 main | `7d00086e4c61d292951d2b1cdb7887aec5dad89d` |
| 本地及 GitHub 备份 `codex/backup-main-before-clean-20261118-9ac1772` | `9ac1772f7ce3de7c16b214c3b5993ab71a17a51b` |
| 原 PDF 修复提交 | `07781e97059fdc052f4cf744e69f45c8b2f8a9b1` |
| 迁移后的 PDF 修复提交 | `b797700` |
| 原 1.1.6 版本提交 | `1c2774e7b3a6a945ca2d8a182539935936725358` |
| 迁移后的版本提交及最终 main | `bd954f2f37e6159ffa46aa1b4b5f40bc23486e2e` |
| 本地审查分支 `codex/clean-pdf-1.1.6` | `bd954f2f37e6159ffa46aa1b4b5f40bc23486e2e` |
| 保留的旧功能分支 `codex/pdf-export-layout` | `1c2774e7b3a6a945ca2d8a182539935936725358` |

[GitHub 备份分支](https://github.com/Dark-Li/cctr-exporter/tree/codex/backup-main-before-clean-20261118-9ac1772)

## 保护与验证

- 首先将原本地 main 备份到 GitHub，并通过独立 `git ls-remote` 核验备份 SHA 后再整理历史。11 月 16 日的备份分支 `codex/backup-main-before-upstream-sync-20261116-851f208` 同时保留，仍指向 `851f2083329b46e4a500da5271ba699511fc5b92`。
- 在已被忽略的 `build/clean-main-sync-20261118` 内创建隔离工作树，从已实时核验的 `7d00086` 顺序 cherry-pick `07781e9` 和 `1c2774e`，两个提交均无冲突。
- 两个旧合并提交 `ffb1cb1`、`9ac1772` 的 tree 分别与它们合入的普通提交相同，迁移普通提交覆盖了它们的全部文件内容。
- 干净分支和原 main 的 Git tree 完全相同，均为 `282eb2738ca3be86ad132c93ee0cd2bfcbfd24d2`。
- 对 `9418db2..851f208` 的七个原始提交逐一检查，它们与干净分支祖先集合的交集为零。
- 整理后的分支相对 `7d00086` 为 `0 behind / 2 ahead`。通过预期旧 SHA 条件更新本地 main，然后普通快进推送成功 `7d00086..bd954f2`。
- 推送后，独立 `git ls-remote` 确认 GitHub main 为 `bd954f2`，新旧备份均保留。本地 main、origin/main、干净审查分支指向同一提交。
- GitHub compare 上游 main 与 fork main 返回 `ahead_by=2`、`behind_by=0`、`total_commits=2`。
- 临时工作树确认干净后，经绝对路径位于当前工作区内的检查，使用 `git worktree remove` 清理，保留审查分支。

## 本地状态与后续开发

当前仍检出 `codex/pdf-export-layout`。其旧历史保留于备份及功能分支。后续工作从新的 main 创建分支，避免将这个旧功能分支再次合回 main 而带回原来的七个提交。

原有暂存区为空，三份原未提交或未跟踪文档的内容与状态保持一致，同步前后 SHA-256 如下。

| 文件 | 状态 | SHA-256 |
| --- | --- | --- |
| `docs/releases/1.1.3.md` | 已修改 | `1A360E1585A0632B4B4C6D4BE12E2FD834EAA56FCC8F5ADEA521CFFB0A161D53` |
| `docs/releases/1.1.5.md` | 未跟踪 | `C22578CC2559CEA58B6EFF9CDE6E090A93BAE8296B75EAEB02744CA0D9587E0B` |
| `docs/changes/2026-11-16-fork-main-upstream-sync.md` | 未跟踪 | `D12035F28956FDC6930B1F5832C141BCE6E1FD99F6099813B671567569016281` |

本记录作为本地未跟踪文档留存，未加入整理后的两次提交。
