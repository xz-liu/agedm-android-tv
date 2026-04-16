# AGE DM TV

一个面向 Google TV / Android TV 的 AGE DM 原生客户端。

## 下载

- APK 下载：[agedm-tv-debug.apk](release-assets/agedm-tv-debug.apk)
- 包名：`io.agedm.tv`
- 最低系统版本：Android 8.0 (`minSdk 26`)

说明：
- 当前提供的是 `debug` 包
- 还没有接正式发布签名

## 目前功能

- 原生首页、目录、推荐、更新、排行、记录
- 原生搜索和详情页
- 原生播放器：播放/暂停、快进快退、选集、切源、自动下一集
- 全局倍速记忆
- 观看记录和继续观看
- 手机扫码把 AGE 链接投到电视

## 技术方案

- 列表和详情数据来自 `https://api.agedm.io/v2/`
- 播放时优先让 AGE 自带解析页拿到真实视频地址
- 实际播放使用 `Media3 ExoPlayer`

主要入口：
- `MainActivity`：主浏览页
- `DetailActivity`：详情页
- `PlayerActivity`：播放器
- `LinkCastActivity`：扫码投送

## 本地构建

需要：
- JDK 17
- Android SDK
- Gradle Wrapper

构建命令：

```bash
source ~/.zprofile
./gradlew :app:assembleDebug
```

输出 APK：

```bash
app/build/outputs/apk/debug/app-debug.apk
```

## 安装

可以直接把 APK 拷到电视安装，或者用 `adb`：

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 当前限制

- 依赖 AGE 接口和解析页，站点改版后可能失效
- 部分播放源还需要继续扩大真机验证
- 目前仍是 `debug` 包
