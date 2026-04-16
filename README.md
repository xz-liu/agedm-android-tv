# AGE DM TV

一个面向 Google TV / Android TV 的 AGE DM 客户端。

这个项目不是通用浏览器壳。当前实现遵循两个原则：
- 浏览直接使用原生 TV 界面
- 播放切到原生播放器，按 TV 遥控器习惯操作

参考站点：
- AGE DM 首页：`https://m.agedm.io/`
- 示例详情页：`https://m.agedm.io/#/detail/20150086`
- 示例播放页：`https://m.agedm.io/#/play/20150086/1/1`

## 当前状态

当前仓库已经可以成功编译 `debug` APK，主要能力包括：
- 原生 TV 首页、目录、推荐、更新、排行五个主标签页
- 通过 AGE 官方接口获取首页、目录、更新、排行、搜索和详情数据
- 原生详情页支持封面、简介、切源、选集、系列作品和相似推荐
- 详情页命中播放路由后切到原生播放器
- 通过 AGE 站点自己的解析页提取真实视频流地址并交给 `Media3 ExoPlayer`
- 原生播放器支持播放/暂停、快进快退、倍速、选集、切源、自动下一集
- 播放进度按动画和分集保存，并支持继续观看
- 电视端生成二维码，手机在同一局域网内提交 AGE 链接后自动打开

当前产物：
- 包名：`io.agedm.tv`
- 最低系统版本：Android 8.0 (`minSdk 26`)
- 调试包路径：[app-debug.apk](/Users/theforce/Dropbox/Codebase/agedm-android-tv/app/build/outputs/apk/debug/app-debug.apk)

## 现有架构

主要入口：
- [MainActivity.kt](/Users/theforce/Dropbox/Codebase/agedm-android-tv/app/src/main/java/io/agedm/tv/ui/MainActivity.kt)：原生首页、目录、推荐、更新、排行、搜索和筛选
- [DetailActivity.kt](/Users/theforce/Dropbox/Codebase/agedm-android-tv/app/src/main/java/io/agedm/tv/ui/DetailActivity.kt)：原生详情、切源、选集、相关推荐
- [PlayerActivity.kt](/Users/theforce/Dropbox/Codebase/agedm-android-tv/app/src/main/java/io/agedm/tv/ui/PlayerActivity.kt)：原生播放器、选集、倍速、续播、自动下一集
- [LinkCastActivity.kt](/Users/theforce/Dropbox/Codebase/agedm-android-tv/app/src/main/java/io/agedm/tv/ui/LinkCastActivity.kt)：扫码投送入口

数据层：
- [AgeRepository.kt](/Users/theforce/Dropbox/Codebase/agedm-android-tv/app/src/main/java/io/agedm/tv/data/AgeRepository.kt)：AGE API 访问与播放流解析
- [PlaybackStore.kt](/Users/theforce/Dropbox/Codebase/agedm-android-tv/app/src/main/java/io/agedm/tv/data/PlaybackStore.kt)：本地播放记录
- [LinkCastManager.kt](/Users/theforce/Dropbox/Codebase/agedm-android-tv/app/src/main/java/io/agedm/tv/data/LinkCastManager.kt)：局域网投送服务
- [AgeRoute.kt](/Users/theforce/Dropbox/Codebase/agedm-android-tv/app/src/main/java/io/agedm/tv/data/AgeRoute.kt)：AGE 链接解析与路由映射

## 开发环境

不要求一定安装 Android Studio。

当前仓库已经验证过下面这套轻量环境可以工作：
- JDK 17
- Android SDK command-line tools
- Android SDK Platform 35
- Android SDK Build-Tools 35.0.0
- Gradle Wrapper

如果只是编译和往电视上装包，`VS Code + terminal` 就够了。

## 构建

首次使用前，确认本机有可用的 Java 和 Android SDK。

如果你已经按当前仓库的默认配置装好环境变量，可以直接执行：

```bash
source ~/.zprofile
./gradlew :app:assembleDebug
```

构建成功后，APK 在：

```bash
app/build/outputs/apk/debug/app-debug.apk
```

说明：
- 当前默认是 `debug` 包
- 还没有配置正式发布签名

## 安装到电视

有两种方式。

### 1. 直接拷 APK 到电视安装

把 [app-debug.apk](/Users/theforce/Dropbox/Codebase/agedm-android-tv/app/build/outputs/apk/debug/app-debug.apk) 通过 U 盘、局域网共享、网盘或消息投送到电视，然后在电视上安装。

前提：
- 电视允许安装未知来源应用
- 电视系统版本不低于 Android 8.0

### 2. 使用 `adb` 安装

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 运行与调试

常用命令：

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
adb logcat | rg io.agedm.tv
```

如果只想看 APK 信息：

```bash
apkanalyzer apk summary app/build/outputs/apk/debug/app-debug.apk
```

## 功能说明

### 原生浏览

- 启动后默认进入原生首页
- 顶栏包含返回、首页、搜索、扫码传链接、继续观看
- 底部主导航固定为：首页、目录、推荐、更新、排行
- 目录页提供 TV 友好的筛选方式，不再依赖网页标签点击

### 原生播放器

- 浏览层和播放器完全脱钩，不再展示 AGE 网页播放器 UI
- 使用 AGE 详情接口获取完整分集列表
- 优先让 AGE 自带 parser 解析真实流地址，再交给原生播放器
- 支持：
  - 播放 / 暂停
  - 左右 10 秒快进快退
  - 1.0x / 1.25x / 1.5x / 2.0x 倍速
  - 切换播放源
  - 快速选集
  - 自动下一集
  - 继续观看

### 播放进度

- 每 5 秒周期性保存
- 暂停、退出、切集时即时保存
- 记录动画、分集、播放源、播放位置、时长、更新时间

### 链接投送

- 电视端会启动一个局域网 HTTP 服务
- 手机扫码后可提交：
  - AGE 详情页链接
  - AGE 播放页链接
  - 纯数字动画 ID
- 非 AGE 链接会被拒绝

## 已知限制

当前仓库能编译，不代表已经在所有电视上完成稳定性验证。已知风险主要有：
- 依赖 AGE 站点接口和解析页格式，站点改版后可能失效
- 个别播放源仍然依赖 AGE parser 页的时序，真机上还需要继续扩大验证样本
- 目前是 `debug` 包，不适合直接当发布版本分发
- 还没有做正式图标、启动图、发布签名、崩溃上报和自动化测试
- `compileSdk = 35` 当前使用的 Android Gradle Plugin 是 `8.5.2`，构建可过，但会有兼容性警告

## 后续建议

如果继续推进，优先级建议如下：
- 在真实 Android TV / Google TV 上跑一轮遥控器焦点和返回键验证
- 验证更多 AGE 播放源，处理解析失败回退策略
- 增加最近观看和详情页“继续观看”信息展示
- 补正式发布签名、应用图标和 release 构建流程
- 增加最基本的日志、错误提示和故障排查页

## 仓库说明

- 需求原文在 [requirements.MD](/Users/theforce/Dropbox/Codebase/agedm-android-tv/requirements.MD)
- 本 README 描述的是当前仓库的实际实现状态，不是完整最终版本承诺
