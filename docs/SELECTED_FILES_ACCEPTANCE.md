# 0.1.46 指定文件上传验收

以下指令交给连接 PyCharm MCP 的 AI。没有实际工具或版本不符时停止，不使用普通 ssh/scp 绕过。

1. 调用 get_terminal_mcp_capabilities，确认 pluginVersion=0.1.46 且 deploymentTools 包含 upload_selected_files_to_deployment_server 和 upload_entire_project_to_deployment_server。读取设置但不修改。
2. 发现项目的真实 Deployment server id 和 mapping。使用 select_deployment_server 选择用户指定的测试服务器；目标不明确时先询问，不猜测服务器。
3. 在项目内新建独立测试目录 tmp/selected_upload_acceptance，包含 a.txt、nested/b.txt、not_selected.txt；使用唯一内容。记录三个本地 SHA256 和 deployment.xml 哈希。
4. 调用 upload_selected_files_to_deployment_server，local_paths 只列 a.txt 和 nested/b.txt，project_selector 使用真实项目路径。检查 success、serverId、requestedCount、files、skippedPaths，确认只尝试这两个文件。
5. 通过 PyCharm MCP 目标终端或其他可用原生读取工具校验远端两个文件的路径和 SHA256，并确认 not_selected.txt 未新增。如果没有可验证的读取方式，标记远端验收未完成，不把 API 返回当作哈希校验。
6. 分别测试空列表、空路径、目录路径、项目外文件、缺失文件、有效文件加缺失文件。每次均应 success=false，且没有开始上传；核对本轮新增审计记录，不混用历史日志。
7. 传入同一文件的相对路径和等价绝对路径，确认 requestedCount=1，不重复上传。Windows 使用 /、反斜杠和不同大小写路径验证归一化。
8. 用户明确允许大文件测试时，在同一独立测试目录创建 256 MiB 文件，只列该文件上传。不得解除 2 MiB 终端备用限制，不得发送 base64；应使用原生 Deployment。记录原生返回和远端文件大小、SHA256；超时不重试、不判成功，检查可能存在的部分文件。
9. 核对 deployment.xml 哈希，确认未持久修改；检查没有串服务器、终端备用或 base64 传输。设置 requireConfirmForDeploymentActions=false 时观察插件是否重复请求确认，但不得修改用户设置或绕过 MCP 客户端本身的权限审批。
10. 输出逐项报告，区分本地预检、原生调用完成、远端哈希校验、失败后残留和未验证项。不执行全项目上传，不删除测试文件，列出残留路径。

全项目上传与部分文件上传声明：旧接口省略上传路径在 0.1.46 起会被拒绝；全项目接口须明确 allow_full_project=true。本验收不允许全项目上传。部分接口必须明确列文件，不能传目录，也不会自动读取 IDE 当前选中项或 Git 改动。两者均受 Deployment 映射和排除规则约束，可覆盖同名文件但不删除额外文件。

## 本机构建

本机 JBR 的 Unix-domain socket 临时路径导致 Gradle 回环连接失败时，可仅为本次 PowerShell 进程设置较短路径：

```powershell
$env:JAVA_HOME = 'C:\Program Files\JetBrains\PyCharm 2026.2\jbr'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=%CD%\build'
.\gradlew.bat check buildPlugin --offline
```

check 包含独立 verifySelectedFilePaths 回归任务，无需 JUnit；它验证路径选择，不连接服务器。该临时目录应预先存在。
