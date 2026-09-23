# 0.3.2：GATE 片源调查与播放兼容

## 实际观察

2026-09-23 查询 AGE 的《GATE 奇幻自卫队》（20150004）和第二季（20160064）。默认源第一集在隔离浏览器中均可加载至 readyState 4。第一季实际清单以 `#EXTM3U` 开头，但 HTTP Content-Type 是 `image/vnd.microsoft.icon`；视频分片以 `image/png` 返回。

第一季抽查第 0、1、2、3、10、30、60、100 个分片的前 64 KiB，均在 139 字节 PNG 前缀后出现连续 TS 同步字节。对一个分片去除前缀后，ffprobe 识别为 H.264 High、1280×720、yuv420p，以及 AAC LC。检查使用了与客户端相同的 Referer/Origin，并非只检查文件扩展名。没有下载完整剧集或把视频样本提交进仓库。

这些证据确认了片源传输层的特殊包装，不能证明用户电视上的全部报错只有这一原因；没有设备日志或实机解码结果。旧版“本地下载无法读取”覆盖了所有本地 PlaybackException，本身无法区分缓存和解码故障。详情入口同样优先命中下载，所以两个入口都失败也不能单独证明真正的网络播放失败。

## 修改

- PNG 包装 TS 的兼容处理位于缓存之外，在线和离线共用，只在完整 PNG 后能验证连续 TS 同步字节时去除前缀。缓存保留原始数据；重试位置映射回原始偏移，显式字节范围请求保持不变；解析上限 64 KiB，普通图片、清单、MP4 和 TS 不被改写。
- 片源解析后作有超时、可取消的短内容检查，识别无后缀/错误 MIME 的 HLS、DASH、SmoothStreaming，拒绝 HTML 错误页和非成功 HTTP 响应，避免把网页保存成“已完成视频”。不依赖服务器是否支持 Range 请求。
- JavaScript HLS 响应探测保留类型提示；URL 判定使用路径，不再把 JS 参数里的 `.m3u8` 或单个 `.m4s` 分片当作完整视频入口。识别 MKV、WebM 等直链容器。
- 同一个 DefaultMediaSourceFactory 处理在线和本地播放，增加 DASH、SmoothStreaming 模块。播放和 DownloadHelper 开启设备可用解码器的初始化回退；没有声称因此支持设备不具备的全部编码。
- 本地缓存启动时按文件头纠正旧 MIME。只保存了清单而缺少分片的旧任务仍需要重新下载，不能凭空恢复缺失数据。
- 错误信息区分网络、HTTP、封装、缓存和解码，并显示错误码。原播放器中可选择“在线播放本集”，明确绕开本地下载、重新解析且保留进度；也可选择原有播放源。没有新增播放器或历史存储。

## 验证与限制

43 项测试、`lintDebug`、`assembleDebug`、`assembleRelease` 通过。新增测试覆盖 139 字节和更大的 PNG 包装、后续分片、重试偏移、字节范围、只读离线缓存、旧下载 MIME 修正、伪 MIME 清单、重定向、HTML/403 响应、容器支持和错误分类。二进制测试数据为合成数据，不依赖公开 CDN 或短期有效 URL。

仍需在用户电视确认实际播放和解码器行为，不能把通过浏览器/数据源测试等同于电视全链路验证。旧缓存缺失、源服务不可用、DRM 或设备不支持的编码仍可能报错，但会显示具体原因和恢复入口。

参考：[Media3 支持的封装与编码说明](https://developer.android.com/media/media3/exoplayer/supported-formats)、[Media3 下载与缓存播放](https://developer.android.com/media/media3/exoplayer/downloading-media)。本次核对了项目固定 [1.4.1 的 TsExtractor](https://github.com/androidx/media/blob/1.4.1/libraries/extractor/src/main/java/androidx/media3/extractor/ts/TsExtractor.java) 和 [HLS 提取器选择](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer_hls/src/main/java/androidx/media3/exoplayer/hls/DefaultHlsExtractorFactory.java)，未笼统升级依赖版本。
