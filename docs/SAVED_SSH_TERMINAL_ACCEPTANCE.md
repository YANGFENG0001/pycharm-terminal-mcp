# 0.1.49 保存的 SSH 终端准备验收

将以下指令交给 PyCharm 中的 AI：

只验收目标终端准备和只读远程核验，不上传文件，不改 Deployment 配置、凭据、免密、公钥或训练文件。先确认 get_terminal_mcp_capabilities 返回版本 0.1.49 且 terminalTools 包含 ensure_saved_ssh_terminal，再发现实际保存的服务器 ID 和设置。不使用普通 ssh/scp 代替 PyCharm 保存配置。

1. 使用用户实际指定的目标；不能因为本报告提及某台服务器就自行选择写入目标。调用 ensure_saved_ssh_terminal，传本轮的 server_selector、project_selector 和 timeout_ms=30000。
2. 不得先以“BatchMode 失败”或“list_global_terminals=[]”判断无法核验。准备工具会复用唯一对应终端或创建匹配预设，并等待只读命令探测；不修改 selected 服务器。
3. 原始返回 ready=true 且 success=true 才表示本次探测成功。保存 status、created、reused、timed_out、serverId、presetId、terminal.logical_id。只有终端已创建但 ready=false，不算认证成功。
4. 使用返回的 logical_id 调用 execute_global_terminal_command，运行只读的 hostname、id、pwd，以及用户原本要核对的文件的大小/哈希或版本命令；核对文件不存在时明确报告，不上传覆盖。不要将就绪 printf 当作已核对远程代码版本。
5. 再调用一次 ensure_saved_ssh_terminal：正常应 reused=true、created=false，不增加目标终端数。工具未修改 selected 服务器，也未修改 deployment.xml 哈希。
6. 使用不存在服务器 selector 和空 selector 各调用一次：应明确失败，不能连接默认服务器。允许创建终端设置关闭时，如果目标不存在，应拒绝创建；不得修改设置或创建普通本地 SSH 终端绕过。已有可读写目标终端仍可复用。
7. 无预设、多个匹配、终端不可读写、连接未就绪或超时按原始 status 记录；不人为破坏网络、密码或制造重复终端。超时后可再次调用以观察现有创建的终端，但不得反复创建。没有表明恢复原因解决时不重试只读探测命令。
8. 审计仅允许匹配预设创建、ensure-saved-ssh-terminal、只读终端执行；没有传输、base64、普通 ssh/scp、密钥修改、密码内容或服务器回退。测试禁止清理既有终端或修改认证配置。

结果应区分本地模拟回归和真实 PyCharm 认证验收。若 ready=false，保持“不覆盖上传”的原安全决策，说明具体阻塞原因。报告工具总调用数，正常情况减少到“发现服务器→ensure→版本核验”，无需 create/list/read 多次循环。
