# 0.1.47 endpoint 失败定向验收

先调用 get_terminal_mcp_capabilities 确认版本 0.1.47，再读取设置、服务器发现，不修改配置、权限或密钥，不实际上传项目。读取本轮审计基线和 deployment.xml 哈希。测试路径使用实际存在的项目内单个文件，不用项目根目录、不声明全量。可以沿用 AGENTS.md，但必须检查其本地存在。

通过 transfer_remote_directory，mode=upload，local_path 为该文件，remote_path 为独立 /tmp 测试文件路径，分别传 endpoint_selector：

1. 空字符串。
2. 空白字符串。
3. 唯一构造的不存在 selector。
4. 本轮发现的 webdeploy ID（不是 Eel ID）。

以上应返回插件 JSON 对象，包含 success=false、message、error；不得是只有 MCP tool call failed 文本，不应出现成功传输或回退事件。空 selector 在仅有一个真实 endpoint 时也必须拒绝。如果有本轮发现的真实 endpoint，可用其 ID 测 mode=invalid：模式在进入传输前拒绝。

额外省略 endpoint_selector，或把 local_paths 数组传成无法解析的文本：这是宿主参数校验，可能为 isError=true 文本，不属于插件业务 JSON 契约，不为得到 JSON 而放宽必填参数或数组类型。

若 sshEndpoints=[]，正向传输标记为未测/阻塞，不以拒绝项通过替代 upload/download/sync 的完成验收，不把终端或 Deployment ID 伪装为 endpoint，不自动回退到其他服务器。原生部分文件上传与方案 C 是不同工具，须单独明确测试范围与写入目标，不能替代本接口验收。

截图可见 SSH 终端但 list_global_terminals=[] 时，记录为插件终端发现异常或时点差异，而不是证明 IDE 没有终端。这个问题不等于 Eel endpoint 可用，也不在本版本中声称已修复。报告须保留原始发现结果及观测时点。

核对本轮审计新增行与配置哈希，若无远端读取能力只说明未发现传输副作用，不声称独立验证了远端文件系统完全无变化。
