# 更新日志 / Changelog

[返回 README](../README.md) · [简体中文 README](../README.zh-CN.md)

版本号与 `src/main/resources/META-INF/plugin.xml`、`build.gradle.kts` 中的 `version` 一致。安装包见 [GitHub Releases](https://github.com/YANGFENG0001/pycharm-terminal-mcp/releases)。

## 0.1.50：仓库整理与文档入仓

本版本不改动 MCP 工具契约与权限语义，工具数量仍为 26 个，`execute_global_terminal_command`、`ensure_saved_ssh_terminal`、上传范围保护等行为与 0.1.49 一致。改变的是代码、测试和文档的组织方式：

- **回归检查接入构建**：7 组回归入口（`SelectedFilePaths`、`SelectedUploadResult`、`SavedSshTerminalRecovery`、`UploadScope`、`SshEndpointSelection`、`TerminalExecution`、`PluginClassloader`）迁入 `src/test`，并由 `build.gradle.kts` 注册为独立 `verify*` 任务挂到 `check` 上；`verifyPluginClassloader` 直接读取构建出的安装包，在隔离类加载器中验证工具集注册与序列化。
- **文档入仓**：新增 [AI 使用指南](AI_USAGE_GUIDE.md)、[MCP 工具发现排查](MCP_TOOL_DISCOVERY.md)、[安装说明](INSTALL.md)、[GitHub 元信息](GITHUB.md)，以及保存的 SSH 终端、终端执行、上传范围、部分上传字段、选择文件、endpoint 失败、选择文件路径共 7 篇定向验收记录。
- **跨项目 skill**：新增 `skills/pycharm-terminal-mcp/`，可整体拷入任意支持 `SKILL.md` 的 agent，参数细节按需读取随附 references。
- **README 中英双语**：`README.md` 为英文主版本，新增 `README.zh-CN.md` 完整中文版，历史更新日志移至本文件，避免两处维护。

## 0.1.49：保存的 SSH 终端自动准备

新增 ensure_saved_ssh_terminal：明确传入本轮发现的 Deployment server selector，唯一匹配用户、host、port，并限制在指定项目内。已有终端先复用；不存在时按唯一保存的 SSH 预设调用 PyCharm 创建，等待连接与可识别的 POSIX 提示符，再执行只读 printf 探测。ready=true 后返回 terminal（含 logical_id），供 AI 继续核对远程版本/哈希。已有终端不可读写、目标/预设歧义、缺少预设、认证未就绪或超时均失败，不另建终端规避权限、不切换其他服务器、不自动上传。重复调用不会重发尚未可见的创建动作。

此操作沿用“允许 AI 创建已配置的 SSH 或远程终端”和读写权限；使用 PyCharm 预设自身的凭据管理，不向 AI 返回密码，不在终端输入密码，不配置 authorized_keys。普通本机/服务器间 BatchMode 失败，不代表 IDE 保存凭据的 SSH 连接一定不可用；但保存凭据失效、主机不可达、人工认证要求仍可能阻塞。不会伪造 Eel endpoint 或服务器间免密成功。自定义非 POSIX 提示符可能无法自动识别，返回明确未就绪，不假装已连接。若 Deployment 名称未暴露 user@host:port，必须明确使用发现的 SSH 预设而不能猜测用户。

新的工具说明要求 AI 先尝试终端准备再报告无终端；不能保证任意模型始终遵从。工具发现与 MCP 客户端权限仍须正常。复测见 [SSH 终端准备验收](SAVED_SSH_TERMINAL_ACCEPTANCE.md)。

## 0.1.48：部分上传结果的空列表字段

部分上传返回始终包含 files 和 skippedPaths 数组。成功且没有跳过文件时明确返回 skippedPaths: []；预检失败且没有尝试文件时明确返回 files: []。不改变上传范围、传输实现、服务器选择或权限。serverId 在尚未解析服务器的预检失败中仍可能省略，不将它改为新的必需字段。新增 20 项 JSON 配置/场景检查和安装包隔离加载检查。复测见 [部分上传字段验收](SELECTED_UPLOAD_FIELDS_ACCEPTANCE.md)。

## 0.1.47：SSH/Eel endpoint 拒绝与失败返回

transfer_remote_directory 的空 selector 在 endpoint 查询前明确拒绝；不存在或不唯一的 endpoint 返回插件 FileTransferResult（success=false、message、error），不回退 selected Deployment server。修复仅有一个 endpoint 时空 selector 可能意外匹配的问题；host:port/user@host:port 按完整地址匹配，端口 22 不会匹配 2222。进入传输工具函数后的常规异常统一返回失败对象，取消仍保留宿主取消语义。

SSH 终端、保存的 Deployment 配置和 SSH/Eel endpoint 是不同的发现对象：可见终端不保证 sshEndpoints 非空。此版本不创建或伪造 Eel endpoint，不保证缺失 endpoint 环境的正向传输可用。缺少必填参数、错误 JSON 数组等调用前错误仍由 IDE MCP/execute_tool 解析层返回，插件函数无法捕获，不能声称所有宿主失败均为插件 JSON。定向复测见 [endpoint 失败验收](ENDPOINT_FAILURE_ACCEPTANCE.md)。

## 0.1.46：上传范围默认保护

上传和同步不再将省略/空 local_path 默认为项目根目录，即使 allow_full_project=true 也必须明确路径。指定项目根目录或其父目录时，必须声明 allow_full_project=true；检查真实路径，防止大小写、分隔符、点路径及符号链接误绕过。保护适用于 upload_to_selected_deployment_server、transfer_selected_deployment_server、invoke_selected_deployment_action 的上传/同步动作，以及已打开项目的 SSH 目录上传/同步。服务器间 copy/sync 和隐式映射 relay 也不再默认整个项目；显式双端远程路径的 server-relay 不改变既有语义。下载/比较保留原接口语义。

全项目上传使用 upload_entire_project_to_deployment_server，必须 allow_full_project=true，默认拒绝；此参数是用户请求的范围声明，不是人工审批开关，关闭确认或开启自动模式不会解除范围保护。日常代码更新优先使用 upload_selected_files_to_deployment_server，AI 应仅列出用户指定或本次任务涉及的文件，不能自动把所有 Git 改动视作本次上传范围。

定向拒绝验收见 [上传范围保护](UPLOAD_SCOPE_ACCEPTANCE.md)。这是旧接口的有意兼容性变更，请同步更新 AI 提示及脚本。

## 0.1.45：修复 MCP 工具集注册失败

0.1.44 的专用序列化器在 PyCharm 中触发 Kotlin 类加载冲突，导致整个桥接工具集不可发现。移除插件包中重复的 Kotlin 标准库，使用 IDE 提供的运行库；保留未完成分支的 "exit_code":null 契约。增加安装包检查及隔离类加载回归，覆盖序列化器初始化、描述符和空值返回，而不只在单一测试 classpath 上验证。安装后需要重启 PyCharm，再刷新或新建 MCP 会话。详情见 [工具发现故障排查](MCP_TOOL_DISCOVERY.md)。

## 0.1.44：未完成分支的空退出码

修复仅发送、超时、忙碌拒绝及其他未完成返回省略退出码的问题。执行结果使用专用 JSON 序列化器，即使宿主设置 `explicitNulls=false`，仍明确返回 `"exit_code":null`；完成分支保持数字退出码。回归覆盖 4 组空值/默认值配置与 5 类结果，不修改宿主全局 JSON 设置。安装后需重测真实 MCP 原始返回，不能仅依据本地测试判定通过。

## 0.1.43：终端执行并等待返回

`execute_global_terminal_command` 默认 `wait_for_completion=true`，在 SSH POSIX shell 中执行并等待，直接返回 `output`、`exit_code`、`completed`、`timed_out`、`success`。正常完成不必再调用 `read_global_terminal`。本地 POSIX 终端需显式传 `shell="posix"`；此等待协议不支持 PowerShell/cmd。

命令在子 shell 中执行：`exit 7` 返回退出码 7，不关闭交互终端；`cd`、`export` 不保留到后续命令。需要持久改变交互 shell 状态时使用 `send_global_terminal_text` 或 `wait_for_completion=false`，仅发送不代表执行成功。

`timeout_ms` 范围为 100..240000，默认 30000。超时返回 `completed=false`、`timed_out=true`、`exit_code=null`，不会自动重发或中断。同一终端的上次命令未确认结束时拒绝再次等待执行；手动中断可能无法产生结束标记，此时需检查并重建终端。大量输出若挤出开始标记也可能无法确认完成。

定向验收见 [终端执行验收](TERMINAL_EXECUTION_ACCEPTANCE.md)。此版本保留下述部分文件上传能力，未将全项目上传变为默认推荐操作。

## 0.1.42：全项目上传与指定部分文件上传

| 操作 | MCP 接口与参数 | 范围 |
| --- | --- | --- |
| 明确上传全项目（0.1.46 起） | upload_entire_project_to_deployment_server，allow_full_project=true | 递归上传项目根目录，受 PyCharm 映射、排除及传输规则限制，不保证每个文件都被发送 |
| 上传一个目录 | 同一旧接口，指定 local_path 为目录 | 递归上传该目录 |
| 上传指定部分文件 | upload_selected_files_to_deployment_server，必填 local_paths | 只提交列出的项目内普通文件，不递归目录、不自动扩大范围 |

部分上传不是读取 IDE 当前选择，也不是自动检测 Git 改动。AI 必须明确提供文件列表；0.1.46 起省略上传路径会被执行层拒绝，不会上传项目根目录。

示例（先通过 select_deployment_server 选择已发现的服务器）：

```json
{
  "project_selector": "D:/Projects/example",
  "local_paths": ["src/models.py", "configs/train.yaml"]
}
```

空列表、空路径、目录、项目外路径、缺失文件或无映射文件在上传前拒绝；相对路径以项目根目录为准，重复文件合并。全部预检通过后逐个调用原生 Deployment，不使用终端或 base64 备用通道，因此不受终端通道 2 MiB 上限限制。沿用现有传输权限和确认设置。

两种上传均可能覆盖远端同名文件，但不删除远端额外文件，不是删除式镜像同步。部分上传失败后停止，返回 files（已尝试结果）和 skippedPaths（未尝试），已上传文件不回滚。success=true 仅表示原生上传调用完成，不代表独立校验远端哈希；仍受 PyCharm 自身排除规则影响。

更早的 0.1.21–0.1.41 变更记录见仓库历史提交。
