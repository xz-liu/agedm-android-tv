# AGE DM TV

面向 Google TV / Android TV 的 AGE DM 原生客户端。

## 下载

| 版本 | 链接 |
|------|------|
| Release（推荐） | [agedm-tv-release.apk](release-assets/agedm-tv-release.apk) |
| Debug | [agedm-tv-debug.apk](release-assets/agedm-tv-debug.apk) |

最低系统版本：Android 8.0

---

## 功能

### 浏览与搜索

- 七个导航分区：扫码、首页、目录、推荐、更新、排行、记录，作为标签页切换
- 首页提供今日更新、最近更新、每日推荐与每日追番分区
- 目录支持按地区、版本、类型、年份、排序五维筛选；排行榜支持按年份筛选
- 全局搜索，支持关键词模糊匹配
- 导航栏左右首尾循环；内容区到达顶部后焦点不会越界回到导航栏意外的地方
- 导航栏聚焦时连按 2 次 `UP` 可打开设置页

### 详情页

- 展示动画封面、简介、播放源列表、分集、系列作品、相关推荐
- 有观看记录时"立即播放"按钮自动变为"继续 第X集"，无需额外确认
- "刷新源"按钮可强制重新拉取源列表，并尽量保留当前选中的源与集数
- 已有部分外部源时，刷新源会一并重拉外部源结果
- 相关推荐对齐桌面站数据，展示更多推荐项目

### 播放器

- **OK 键单次暂停 / 播放**（控制栏隐藏时直接切换，无需先呼出控制栏）
- **UP 键一键跳过片头 88 秒**，右上角短暂弹出「跳过片头 +1:28」提示
- 左右方向键快退 / 快进 10 秒，屏幕右上角弹出当前跳转时间点提示
- DOWN 键呼出选集 / 选源抽屉；抽屉从右侧滑入，关闭时滑出
- 控制栏显示后 4 秒无操作自动隐藏；任意方向键操作重置计时
- 调速：1× / 1.25× / 1.5× / 2×，倍速设置跨会话持久化
- 自动尝试后续源：某个源解析或播放失败时，自动试播下一个可用源
- 自动连播：一集播完后自动切换到下一集（可在设置页关闭）
- 自动连播沿用当前成功播放的源与分集索引

### 外部源

- 详情页和播放器均支持懒加载 AAFun / DM84 外部补充源
- 已加载部分外部源后仍可继续补齐剩余源
- AGE 所有源全部失败时，播放器自动尝试已有外部源
- 外部源播放页通过隐藏 WebView 嗅探真实流地址（见技术设计）

### 观看记录

- 自动记录每部动画的观看进度（集数 + 时间点），每 5 秒持久化一次
- 详情页直接显示"继续 第X集"并跳转到上次观看位置
- 记录分区展示全部观看历史

### 设置页

- 默认倍速（1× / 1.25× / 1.5× / 2×）
- 自动下一集开关
- 外部源优先级顺序，默认 `AGE → AAFun → DM84`（AGE 固定最高优先级）
- 一键安装最新版本：打开 GitHub 上的最新 Release APK

### 手机扫码投送

- 扫码页作为主界面标签直接展示，无需额外入口
- 手机与电视连接同一 Wi-Fi 后扫码即用
- 支持搜索动画名称，也支持提交 AGE 详情页 URL、播放页 URL 或动画 ID
- 搜索结果以封面卡片横排展示，支持遥控器左右滚动选择
- 手机连续投送时电视自动切换到最新内容

---

## 安装

将 APK 拷到电视直接安装，或通过 adb：

```bash
adb connect <tv-ip>:5555
adb install -r agedm-tv-release.apk
```

---

## 遥控器操作速查

### 主界面

| 按键 | 行为 |
|------|------|
| 左 / 右 | 切换底部导航分区（首尾循环） |
| 下 | 从导航栏移入内容区域 |
| 上（在导航栏） | 首次提示，再次按上打开设置页 |
| 上（在内容区） | 焦点向上，到顶后不会回到导航栏意外位置 |
| OK | 打开当前聚焦的动画详情 |
| BACK | 返回首页 / 退出应用 |

### 播放器（控制栏隐藏时）

| 按键 | 行为 |
|------|------|
| OK | 暂停 / 继续播放 |
| 上 | 跳过片头 +88 秒，右上角显示「跳过片头 +1:28」提示 |
| 下 | 打开选集 / 选源抽屉 |
| 左 | 快退 10 秒 |
| 右 | 快进 10 秒 |
| BACK | 退出播放器，返回详情页 |
| 媒体键 播放/暂停 | 暂停 / 继续播放（任意状态） |

### 播放器（控制栏可见时）

| 按键 | 行为 |
|------|------|
| OK | 确认当前聚焦的按钮（暂停、倍速、自动连播） |
| BACK | 关闭控制栏 / 关闭选集抽屉 |

---

## 技术设计

### 磁盘缓存（两时间戳设计）

缓存文件头格式：`WRITE_TS\tACCESS_TS\n<数据>`

| 时间戳 | 语义 | 用途 |
|--------|------|------|
| `WRITE_TS` | 数据从网络获取的时刻 | 控制 TTL 新鲜度 |
| `ACCESS_TS` | 数据最后被读取或触碰的时刻 | 控制 14 天冷驱逐 |

- `get(key, ttl)`：命中时更新 `ACCESS_TS`，超过 TTL 则删除重拉
- `peek(key)`：读取数据但不校验 TTL，专供「先展示旧数据」场景
- `touch(key)`：仅刷新 `ACCESS_TS`，用于重置 14 天驱逐窗口
- `evictExpired()`：应用启动时运行，仅驱逐 `ACCESS_TS` 超过 14 天的条目

TTL 策略：首页 / 列表 10 分钟，搜索 5 分钟，动画详情 14 天（即 MAX_AGE）。

每次列表请求完成后，对返回列表中每个动画的 `detail_<id>` 缓存执行 `touch`，确保正在追的番剧（频繁出现在更新列表中）详情缓存不会被冷驱逐；长期未追的老番在 14 天无任何访问后自然清理。

### 渐进式加载（Stale-While-Revalidate）

所有分区（首页、目录、推荐、更新、排行、搜索、**详情页**）均采用相同的三阶段加载策略：

1. 立即从磁盘 `peek` 缓存数据并渲染，**携带页面切入动画**
2. 180ms 后在后台发起网络请求（详情页无延迟，直接并发）
3. 网络数据返回后静默更新，**不重复触发切入动画**

无缓存时正常显示 loading 状态，数据到达后带动画展示。

关键实现：`launchStaleFirstLoad<T>(peek, fetch, render: (T, animate: Boolean) -> Unit)`，第二次 `render` 调用时传入 `animate = false`，`showSections` / `showGrid` 跳过 `animateContentIn`。

### Activity 过渡动画

所有 Activity 切换（Main → Detail → Player）使用 150–200ms alpha 淡入淡出，并通过 `android:windowBackground="@color/age_bg"` 消除默认黑色背景闪烁。

### 播放器 UI 动画

- **控制栏**：显示时 alpha 0→1（150ms DecelerateInterpolator），隐藏时 1→0（120ms AccelerateInterpolator），`overlayScrim`、`topInfoContainer`、`bottomControlContainer` 同步处理
- **选集抽屉**：打开时 `translationX: 360dp→0`（220ms DecelerateInterpolator²），关闭时反向（180ms AccelerateInterpolator）
- **控制栏自动隐藏**：显示后启动 4 秒计时，任意键操作重置；`hideControls()` 取消计时

### OSD 提示（skipOsdText）

跳过片头、快进、快退均通过专用 `skipOsdText` 显示时间反馈，与播放器 `loadingText`（缓冲状态）完全分离，避免两段文字相互覆盖。OSD 显示 1500ms 后自动消失。

### 导航栏指示器动画

底部导航栏下方有一条 3dp 圆角青色小条，始终跟随当前激活的分区。切换时以 260ms `DecelerateInterpolator` 平滑滑动。

实现：通过 `ViewGroupOverlay` 将指示器添加到 `LinearLayout` 覆盖层，避免改动布局结构；坐标由 `offsetDescendantRectToMyCoords` 换算，动画由 `ViewPropertyAnimator.x()` 驱动。

### 流解析（WebView 代理）

部分播放源需要执行 JavaScript 才能获得真实流地址。播放器维护一个 1×1 不可见 `WebView`，通过多层嗅探捕获流地址：

- **网络拦截**：`WebViewClient.shouldInterceptRequest` 捕获明显媒体请求及带 `Range` 的直链视频请求
- **JS Bridge 注入**：注入脚本劫持 `Response.text()` / `XMLHttpRequest`，在页面或同源 iframe 返回 `#EXTM3U` 时直接上报
- **DOM 扫描与轮询**：扫描 `video/source` 标签、ArtPlayer 内部对象，定时轮询补抓晚到的媒体地址

超时（20 秒）仍未解析时，AGE 内置源回退到 AGE 自身解析接口；外部源直接判定为失败。

### 投送服务 QR 预生成

`AgeTvApplication.onCreate()` 在后台线程完成：
1. 启动本地 NanoHTTPD 服务器，获取局域网访问 URL
2. 使用 ZXing 生成 1000×1000 二维码位图

结果存入 `StateFlow<Bitmap?>`。用户打开投送页时直接读取 `.value`，无需等待，不重复生成。
