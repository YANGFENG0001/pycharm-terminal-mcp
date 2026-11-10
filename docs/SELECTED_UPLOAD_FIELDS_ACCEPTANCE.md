# 0.1.48 部分上传字段定向验收

先读取能力确认版本 0.1.48，部分文件上传工具已暴露；只读取设置，不修改权限、Deployment 配置或密钥，不进行全项目上传。

1. 无传输项：upload_selected_files_to_deployment_server 的 local_paths=[]。原始 JSON 必须包含 success=false、requestedCount=0、files=[]、skippedPaths=[]。不能用调用客户端自行补齐字段。
2. 无传输项：明确列出两个独立不存在的项目内文件路径。要求 success=false、files=[]，skippedPaths 包含两条原始请求路径。不应出现上传事件。
3. 正向项仅在确认测试服务器和小文件写入范围后执行。重新发现实际 server id；可沿用之前 NODE-A 测试目标，但不能直接复用历史 ID。检查 0.1.47 测试留下的两个本地文件存在且内容未变，确认远端同名文件可被本次测试覆盖后，只上传 a.txt 和 nested/b.txt，不上传目录或 not_selected.txt。
4. 原始成功 JSON 必须有 success=true、正确 serverId、requestedCount=2、两项 files 以及明确的 skippedPaths=[]。若失败，按实际情况报告，不用终端核验结果补填成功字段。
5. 通过目标 PyCharm 终端只读核验两端 SHA256，not_selected.txt 仍不存在；核对 deployment.xml 哈希和本轮审计，保留测试文件，不清理、不重试失败上传。

部分完成后的失败分支由本地序列化回归覆盖，不人为关闭连接或删除远端文件来制造故障。上述验收不扩大为大文件或全项目验收。
