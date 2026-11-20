# HTTP API（0.3）

默认基础地址 `http://127.0.0.1:18765`。所有 `/api/*` 请求需要请求头 `X-Rescue-Token`。USB 凭证为四位数字，LAN 为随机长字符串；它们会在服务重启时变化。不能把凭证放进模型提示、URL 查询参数或仓库。

浏览器请求带 `Origin` 时必须与服务器同源；不提供跨域通配授权。API 直接执行获授权的请求，没有 Android 式确认对话框。

## 接口一览

| 方法 / 路径 | 输入 | 返回 |
| --- | --- | --- |
| `GET /api/status` | 无 | `machine, mode, logDir, workspace, inbox, outbox, maxFileBytes, version` |
| `POST /api/jobs` | JSON 命令请求 | 新任务 202；同 ID 同内容 200 |
| `GET /api/jobs/{id}` | UUID | 任务快照；不存在 404 |
| `POST /api/jobs/{id}/cancel` | `{}` | 请求取消；不存在 404 |
| `GET /api/files` | 无 | `workspace, files, truncated, maxFileBytes` |
| `POST /api/files/upload?name=...` | 原始文件字节 | 201 文件描述 |
| `GET /api/files/download?path=...` | URL 编码的相对路径 | 原始字节，支持 Range |

鉴权失败 401，跨源请求 403；输入不合法通常 400；任务冲突 409；文件超限 413。请同时检查 HTTP 状态和响应内容，不把 202 当成执行成功。

## 提交命令

```json
{
  "id": "6dc87879-b7f2-45c7-9e80-9e878fb043ae",
  "command": "Write-Output 'hello'",
  "workingDirectory": "C:\\Users\\Public",
  "timeoutSeconds": 60
}
```

示例 UUID 必须在每个新操作中替换为新生成值。工作目录必须存在；优先用 `/api/status` 返回的 `workspace`。command 最多 16000 字符，timeoutSeconds 为 1–300，缺省 60。

任务快照：

```json
{"id":"...","state":"completed","exitCode":0,"stdout":"hello\r\n","stderr":"","truncated":false}
```

状态为 `running`、`completed`、`cancelled`、`timed_out`、`failed`。`completed` 只表示进程结束，仍需检查 `exitCode` 和实际输出。内部启动失败时退出码可以为 null。stdout/stderr 各限制 131072 个 .NET 字符；截断时 `truncated=true`。

只有一个命令能同时运行。每个进程会话最多保留 500 个任务；日志写入本机数据目录。重复提交相同 UUID 和完全相同 payload 返回原任务；改变内容则 409。提交后网络断开时先查询原 UUID，**不要换 UUID 重新提交**。服务器重启会丢失内存中的查询表，此时先检查磁盘日志和实际结果。

## 文件

上传正文不是 multipart，而是原始文件字节，Content-Type 可设为 `application/octet-stream`。`name` 只接受文件名，不能包含目录；用 URL 编码传中文。每次上传保存到不同的 `inbox/<随机目录>/name`。100 MB 上限指 100 × 1024 × 1024 字节。

文件描述字段：

```json
{"name":"result.json","path":"outbox/result.json","fullPath":"C:\\...\\workspace\\outbox\\result.json","bytes":42,"modifiedUtc":"..."}
```

`path` 用于下载，`fullPath` 可供电脑命令使用。下载只允许工作区 inbox/outbox 下的路径，拒绝父目录穿越、绝对路径、NTFS 流和检测到的重解析点。API 不提供任意路径文件下载，也不提供删除接口；经授权的 PowerShell 命令仍具备当前用户的文件访问权限。

列表最多 200 项，`truncated` 表示还有未列出的文件。内容正确性要由任务自身验证，列表存在不代表编译或转换成功。上传/下载中断没有断点续传业务协议；下载 HTTP Range 能力不代表手机 UI 已实现续传。

## 运行配置

- `--lan`：绑定所有网卡，使用长凭证。
- `PHONERESCUE_PORT`：默认 18765；测试使用 18766。Android 客户端固定 18765，因此自定义端口主要供开发测试。
- `PHONERESCUE_DATA`：覆盖默认 `%LOCALAPPDATA%\PhoneRescue`。自定义数据目录主要用于隔离测试；自动 USB 脚本读取默认位置的 session 文件。
- 正常启动写入 `session.json`，含 `url, code, processId`；正常退出删除，强制终止可能留下旧文件。

本地 CLI 见 [Agent 使用指南](AGENT_USAGE.md)。不要将配对码当成用户身份系统，也不要把此 API 部署为公网执行服务。
