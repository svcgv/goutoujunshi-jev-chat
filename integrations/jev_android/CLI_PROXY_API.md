# Android：使用 CLI-Proxy-API 判断和起草（无需 Jev）

本 fork 在 Android 设置中增加 **CLI-Proxy-API / 兼容** 策略路线。判断与候选排序使用普通 `POST /v1/chat/completions`，而非 Jev 的 decisions API。DeepSeek / GPT 是上游模型；App 不提供模型额度、上游登录或代理安装。

## 1. 先确认代理提供的模型

在电脑启动、配置好 CLI-Proxy-API，并在其上游配置 DeepSeek、GPT 的可用资源。代理必须支持非流式 Chat Completions；仅支持 Responses 的端点不能直接使用。

在电脑终端验证（假设代理端口为 8317，按实际配置修改）：

```sh
# 不要把密钥写入仓库；用环境变量输入你配置的代理客户端密钥。
export PROXY_API_KEY='你的代理客户端密钥'
curl -fsS http://127.0.0.1:8317/v1/models \
  -H "Authorization: Bearer $PROXY_API_KEY"
```

填写返回的 `data[].id`，包括代理配置的别名或前缀。不要假定 `gpt`、`deepseek`、`deepseek-flash` 一定存在。切换判断模型只需修改模型 ID；回复模型可以是另一个 ID。

## 2. 手机访问电脑上的代理

推荐通过 USB 调试转发，不需要将代理开放到公网：

```sh
adb devices
adb reverse tcp:8317 tcp:8317
```

然后在手机使用 `http://127.0.0.1:8317/v1`。`127.0.0.1` 原本指手机，只有配置了上述转发才会连接电脑代理。重连设备后可能需要重新执行。Android 模拟器也可使用 `http://10.0.2.2:8317/v1`。

使用局域网/远程服务时填写手机可达、证书可信的 **HTTPS** 地址，例如 `https://proxy.example.com/v1`。不要将无认证代理暴露到公网。此版本仅允许 localhost、127.0.0.1 和模拟器主机 10.0.2.2 使用明文 HTTP，其它地址仍要求 HTTPS，不会全局放开明文。

## 3. App 设置

| 设置 | 内容 |
|---|---|
| 策略判断 | CLI-Proxy-API / 兼容 |
| 策略 Base URL | `http://127.0.0.1:8317/v1`，或你的 HTTPS API 根地址 |
| 策略模型 ID | `/v1/models` 中实际可用的 DeepSeek 或 GPT ID |
| 策略接口密钥 | 代理的客户端 API Key，不是上游 OAuth token |
| Jev 判断接口 | 不使用，可留空 |
| 回复接口 | CLI-Proxy-API 或自定义 |
| 回复 Base URL | 同一代理的 `/v1` 地址 |
| 回复模型 ID | 代理中的生成模型 ID（可与判断不同） |
| 回复密钥 | 同一代理密钥；建议显式填写 |
| OCR | 建议先用 ML Kit 本地识别，避免额外云端截图上传 |

策略密钥留空时，只能复用**同一协议、主机和端口**下显式填写的回复密钥。跨服务不会自动复用；代理密钥也不会自动发送给官方 DeepSeek 视觉接口。

先点「测试策略判断」和「测试回复」，再保存设置。策略测试仅发送内置示例，不发送联系人背景或真实聊天。新安装默认选择兼容策略，但模型 ID 留空，必须自行配置；已有 Jev / DeepSeek 选择保留。

## 行为与限制

- 兼容判断请求只含 `model`、`messages`、`stream: false`。不假定支持 `thinking`、`temperature`、`max_tokens`、JSON mode 或 `logprobs`，以避免模型/代理参数不兼容。
- 使用提示词要求 JSON，然后执行现有严格证据校验；格式无效最多再请求一次，仍无效则停止候选生成，不静默切换供应商。
- 代理路线不做三次 token 权重查询。显示的判断把握是模型自评，候选分数仅是相对排序，不是恋爱成功率。
- 保留已核对对话、联系人背景、历史上下文和明确停止联系拦截；程序仍不自动发送。
- 本次只修改 Android，Mac / Windows 的策略配置不变。
- **微信一对一接入已加入本 fork，但微信版本、系统厂商和 FLAG_SECURE 窗口仍需在真机上验证。**
- 尚需在你的实际代理、实际模型和手机上验证；传输到代理后是否记录或转发，由你配置的代理和上游服务决定。

## 排查

- 无法连接：检查代理是否启动、端口和 `adb reverse` 是否正确。
- HTTP 401/403：填写代理客户端密钥，而不是把上游服务密钥当代理密钥。
- 找不到模型：核对 `/v1/models` 的 ID、别名和上游授权。
- 输出不完整/空内容/格式错误：检查模型是否支持 Chat Completions、代理是否返回 `choices[].message.content`，以及是否因上游输出限制被截断。
- 局域网 HTTP 被阻止：改用可信 HTTPS，或使用 USB 转发到 localhost；不建议关闭全局安全策略。

## 构建和回归测试

使用 JDK 17 和 Android SDK 35，在此目录运行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

APK 输出到 `app/build/outputs/apk/debug/goutoujunshi-jev-chat-0.1.8-preview-debug.apk`（文件名自动带上 `versionName`）。本 fork 的源码功能不能通过下载上游旧 APK 获得。新增测试覆盖兼容请求字段、模拟代理 HTTP 请求、JSON 证据解析、输出完整性、API 地址及同源密钥限制；真机、真实代理和实际模型仍需单独验收。
