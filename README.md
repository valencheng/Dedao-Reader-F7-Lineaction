# 划线同步（Android 墨水屏阅读器版）

给 Android 11 墨水屏阅读器用的**无界面剪贴板同步工具**：在任何阅读 App 里复制文字后，
自动通过笔记软件 OpenAPI 上传到默认知识库。不弹窗、无通知、无图标界面，事件驱动、
接近零内存占用。

| 目标 | 实现 |
|---|---|
| 最小内存 | 纯框架 Java、零第三方库、无 Activity、无 GPU 渲染；APK 约 20KB，进程 PSS 约 10~20MB |
| 不影响阅读 | 事件驱动无轮询；绝大多数情况只做一次内存级剪贴板读取，无任何可见行为 |
| 无界面 | 无图标、无界面、无通知；唯一入口是系统「无障碍」设置里的开关 |
| 最小权限 | 仅 `INTERNET` 一项；配置文件读的是应用自身目录，不需要存储权限 |
| USB 安装 | `adb install` 即装；无障碍开关也支持用 adb 命令直接打开 |

产物：`out/clipsync.apk`（重新构建：`./build.sh`，需本机 Android SDK + JDK）。

---

## 一、安装（USB）

Mac/PC 装好 adb 后，阅读器开启 USB 调试并连接：

```bash
adb install out/clipsync.apk
```

## 二、启用监听（两种方式任选）

**方式 A：设备上操作**

设置 → 无障碍 → 已下载的应用 → **划线同步（剪贴板监听）** → 开启。
（不同阅读器固件的菜单层级略有差异，一般在「无障碍/辅助功能」里。）

**方式 B：adb 命令（推荐，无需找菜单）**

```bash
adb shell settings put secure enabled_accessibility_services com.chengfei.clipsync/.ClipSyncService
adb shell settings put secure accessibility_enabled 1
```

如果设备上已启用过其他无障碍服务，把新旧服务用冒号拼接，避免覆盖：

```bash
adb shell settings get secure enabled_accessibility_services     # 先看现有值
adb shell settings put secure enabled_accessibility_services "现有值:com.chengfei.clipsync/.ClipSyncService"
```

**第三步：启用输入法模式（本机必需）**

Android 的剪贴板策略是「默认输入法始终可读」。墨案 F7 这类固件严格限制后台读取，
且阅读 App 划线不产生标准无障碍事件，因此必须让本应用同时作为默认输入法：

```bash
adb shell ime enable com.chengfei.clipsync/.ClipSyncIme
adb shell ime set com.chengfei.clipsync/.ClipSyncIme
```

- 划线、复制等阅读操作完全不受影响；键盘只在真正唤起输入框（如搜书）时才出现
- 需要打字时如想用原来的输入法：点键盘上的「**切**」键临时切换；注意**默认输入法
  一旦换回别的，剪贴板捕获就会失效**，用完切回来即可（`adb shell ime set com.chengfei.clipsync/.ClipSyncIme`）

启用后由系统绑定常驻，**重启设备自动恢复**（无障碍与输入法设置均持久化），无需任何保活措施。

## 三、配置笔记 API（得到大脑 openapi）

已按得到大脑开放平台 `resource/note/save` 接口预置好配置，只需填两个值：

1. 在得到大脑开放平台创建个人开发应用，拿到 **Client ID（`cli_` 开头）** 和 **API Key（`gk_live_` 开头）**
2. 复制 `config.example.json` 为 `config.json`，替换 `headers` 里这两个值
3. 推送到设备（应用专属目录，**不需要**存储权限）：

```bash
adb push config.json /sdcard/Android/data/com.chengfei.clipsync/files/config.json
```

也可以用 USB 文件传输（MTP）放到同一路径。**文件修改后自动生效，无需重启。**

### 预置配置内容

```json
{
  "endpoint": "https://openapi.biji.com/open/api/v1/resource/note/save",
  "method": "POST",
  "token": "",
  "headers": {
    "X-Client-ID": "cli_你的ClientID",
    "Authorization": "gk_live_你的APIKey"
  },
  "body_template": "{\"note_type\": \"plain_text\", \"title\": \"划线 {{date}}\", \"content\": {{content}}, \"tags\": [\"划线\"], \"client_request_id\": \"{{clip_id}}\"}",
  "min_length": 2,
  "max_length": 200000,
  "deep_read": true
}
```

要点：

- **鉴权**：得到大脑用双请求头，`Authorization` 是裸 Key、**不带** Bearer 前缀，所以
  不走 `token` 字段，直接写在 `headers` 里
- **默认知识库**：`topic_id` 不传即进默认知识库。以后想存到指定知识库，在模板里加
  一项 `"topic_id": "你的知识库ID"` 即可
- **幂等防重**：`client_request_id` 用划线内容的 SHA-1（`{{clip_id}}`），断网重试、
  补传都不会在服务端产生重复笔记
- `tags` 是写死在模板里的固定标签，可自行增删；`title` 里的「划线 {{date}}」同理

### 字段说明（通用）

| 字段 | 必填 | 说明 |
|---|---|---|
| `endpoint` | ✅ | 接口完整地址。为空时不发送，新文本进本地待传队列 |
| `method` | | 默认 `POST` |
| `token` / `token_prefix` | | Bearer 风格接口用：token 非空时加 `Authorization: <prefix> <token>`，prefix 默认 `Bearer`，设 `""` 为裸 token（得到大脑不用这组，直接用 headers） |
| `headers` | | 任意请求头，值里同样支持占位符 |
| `body_template` | | 请求体模板，见下 |
| `min_length` / `max_length` | | 长度过滤，默认 1 / 200000 字符 |
| `deep_read` | | 是否允许「焦点窗口深读」兜底（默认 `true`，个别固件若见闪烁可关） |

### 请求体模板占位符

| 占位符 | 替换为 |
|---|---|
| `{{content}}` | **带引号**的 JSON 字符串（自动转义换行/引号），模板里不要再加引号 |
| `{{clip_id}}` | `clip-` + 内容 SHA-1，纯 ASCII 幂等键（放 `client_request_id` 或 `Idempotency-Key` 头） |
| `{{date}}` | `2026-09-08 14:30` 格式时间 |
| `{{source}}` | 固定 `clip` |

## 四、工作原理（为什么它不干扰阅读）

按固件限制程度分层自适应，逐层兜底：

1. **输入法合约层（本机实际生效）**：本应用同时注册为默认输入法，Android 保证默认
   输入法始终可读剪贴板——复制动作本身就会触发我们的剪贴板监听器，直读即成功
2. **时间戳路径**：读取剪贴板描述中的复制时间戳（部分固件后台可读），精确判定发生
   了一次复制
3. **深读兜底**：时间戳不可用的固件上，靠选词静默/长按事件触发一次 1×1 像素透明
   焦点窗口（触摸穿透、不弹输入法）完成读取（`deep_read: false` 可禁用）

其余设计：

- **去重**：剪贴板时间戳 + 内容 SHA-1 双保险并落盘，重复复制/重启不会重复上传。
- **重试**：断网或接口报错自动进本地队列（上限 256KB），配置好/网络恢复后自动补传。
- **事件驱动**：仅在阅读产生界面事件时做毫秒级探测（系统侧已限流 800ms + 应用内
  500ms 节流），无轮询、无唤醒锁、息屏零活动。

## 五、验证与排障

```bash
# 看运行日志（捕获、上传、失败原因都在这里）
adb logcat -s ClipSync

# 确认服务在运行
adb shell dumpsys accessibility | grep -i clipsync

# 看内存占用
adb shell dumpsys meminfo com.chengfei.clipsync
```

完整链路测试：配好 `config.json` → 阅读器上随便复制一段文字 → 日志出现
`new clip captured` 与 `upload ok` → 笔记软件默认知识库收到内容。

| 现象 | 处理 |
|---|---|
| 复制后日志无反应 | 确认无障碍开关已打开（`dumpsys accessibility`）；换一个 App 复制试试 |
| 日志出现 `endpoint not configured` | `config.json` 没推到位或 `endpoint` 为空 |
| `upload http 4xx/5xx` | 看 token/headers/body_template 是否与接口文档一致 |
| 之前离线失败的文本 | 配好后自动补传，无需手动操作 |

## 六、卸载

```bash
adb shell settings put secure enabled_accessibility_services ""   # 可选：先关开关
adb uninstall com.chengfei.clipsync
```

关闭无障碍开关后进程即退出，设备恢复原状；卸载会清空待传队列与本工具全部数据
（已上传到笔记软件的内容不受影响）。
