# 0.1.45 终端执行定向验收

本轮重点：以下所有未完成分支必须在 MCP 原始 JSON 中包含键 `exit_code`，且值严格为 JSON null，不接受缺失键、字符串 null 或根据终端回显补填。测试仅发送、超时、超时后的忙碌拒绝，以及 timeout_ms=99 的参数错误；完成分支验证数字 0 和 7。错误参数不得发送命令。

以下内容可直接交给 AI。使用本轮发现的终端 selector，不复用报告中的旧 ID。

请只测试终端执行接口，不上传项目、不修改 Deployment 配置、不操作密钥、不启动训练。先读取能力，确认运行版本为 0.1.45，再调用 list_global_terminals 选择已连接、可读写的 SSH POSIX 终端；无终端时仅从实际返回的预设创建。工具不可发现时立即停止，参见 MCP_TOOL_DISCOVERY.md，不用其他接口替代验收。

1. 对选定终端调用 execute_global_terminal_command，command 为 `printf 'MCP_EXEC_043_OK\n'`，wait_for_completion=true，timeout_ms=10000。要求 completed=true、success=true、exit_code=0、timed_out=false，output 包含测试文本。不得用额外 read 的输出填补返回字段。
2. 同一终端执行 `printf 'MCP_EXEC_043_EXIT\n'; exit 7`。要求 completed=true、success=false、exit_code=7，output 包含测试文本。随后执行 `printf 'MCP_EXEC_043_ALIVE\n'` 并确认退出码为 0，证明交互 shell 未被关闭。
3. 执行 `false`，要求 completed=true、exit_code=1、success=false，不把非零退出码判为 MCP 调用异常。
4. 执行 `printf 'MCP_EXEC_043_SENT\n'`，wait_for_completion=false。要求 completed=false、success=false、exit_code=null；只表示已发送，不要求同步输出。此项允许一次读取核验。
5. 最后执行 `sleep 2; printf 'MCP_EXEC_043_LATE\n'`，timeout_ms=100。要求 timed_out=true、completed=false、success=false、exit_code=null。立即再次等待执行应结构化拒绝，不能将新命令发送到尚未完成的任务中；待原命令实际结束后再执行 ALIVE 命令应恢复正常。不得重发 sleep 或自动中断。
6. 如已有本地 PowerShell/cmd 终端，shell=auto 应在发送前返回结构化不支持错误，不向它注入 POSIX 包装。没有此类终端则标记未测。

记录每次 MCP 原始结构化结果和工具调用数，检查 output 不混入包装命令回显及旧尾部。普通完成项不追加 read；只发送、超时和故障诊断才读取终端。当前 read 接口没有 cursor 验收契约，不声称本轮修复了 cursor。

此测试不覆盖大量输出、手动输入并发、非 POSIX SSH shell、断线恢复或服务器直传。若任一完成字段只来自终端推断，应判定严格 API 未通过。
