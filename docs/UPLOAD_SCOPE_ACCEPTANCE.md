# 0.1.46 上传范围保护验收

将以下指令交给 AI。先发现能力并确认运行版本 0.1.46，工具不可发现立即停止；读取设置但不要修改，不进行全项目上传、训练、密钥配置或 Deployment 文件修改。项目和 server selector 使用本轮发现值。任何调用结果不能从终端回显补填。

## 无传输拒绝项

记录审计起始位置和 deployment.xml 哈希，然后测试：

1. transfer_selected_deployment_server 只传真实 project_selector，省略 local_path 和 mode：应 success=false，说明缺少路径，没有上传事件。
2. upload_to_selected_deployment_server 省略路径；再传空字符串及空白字符串：全部拒绝。传 allow_full_project=true 但仍省略路径，也必须拒绝。
3. invoke_selected_deployment_action 分别用 action=upload、upload_to、sync、sync_with，均省略路径：拒绝。
4. 三个旧接口指定项目根目录，但不声明 allow_full_project 或设 false：拒绝。验证根目录的 /、反斜杠、大小写变体以及 configs/..（该目录必须实际存在），均不能绕过。
5. upload_entire_project_to_deployment_server 省略 allow_full_project 或设 false：拒绝。测试中禁止传 true，不执行全量上传。
6. upload_selected_files_to_deployment_server 传空列表、目录及有效文件与缺失文件的混合列表：全部在传输前拒绝。
7. 若存在本轮发现的真实 SSH/Eel endpoint，对 transfer_remote_directory 指定项目根目录、mode=upload，不声明 allow_full_project：拒绝；不能将 webdeploy ID 冒充 Eel endpoint。无 endpoint 标记未测。
8. 服务器间 copy/sync 使用实际发现的双端服务器，省略 local_path 或指定根目录但不声明全量：拒绝。不要为绕过拒绝而自动填项目根目录或开启全量声明。

检查上述调用之后没有新增 native upload、endpoint transfer、terminal-backed、base64 或 staging 传输记录。不能仅凭审计缺事件证明绝无副作用；同时按实际返回和可用远端读取能力检查。确认配置哈希未变。

## 经授权的部分上传

只有用户已指定测试目标并允许小文件写入时才做：创建独立临时目录，含 a.txt、b.txt、not_selected.txt。通过 upload_selected_files_to_deployment_server 只列 a.txt、b.txt；核验这两个远端文件哈希及 not_selected.txt 未上传。指定此测试子目录的旧上传接口应允许通过范围预检，但目录语义为递归上传全部内容，不应把它当作部分文件上传。

全量正向测试只在用户另外明确授权时执行，不能因为本测试提及 allow_full_project 就视作授权；本次拒绝验收不会验证全项目上传完成。报告区分范围预检、调用完成、远端校验和未测项。
