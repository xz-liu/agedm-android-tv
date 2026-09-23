# 0.3.0：下载融入原有浏览与播放

## 交互

下载分区继续与首页、记录、扫码使用相同的左右方向导航。下载按作品显示六列海报，沿用现有海报高度、焦点边框和文字风格，附完成集数、总进度、实时速度和占用空间。进入作品后显示四列分集，支持整部或单集暂停、继续、重试和删除；播放返回保留分集位置，BACK 回到原作品海报。

详情页的长集数弹出列表已移除。可下载当前源全集，或长按原选集网格中的任一集进入多选，短按 OK 勾选，再次长按就地打开“下载所选 / 全选 / 退出多选”。上方也保留可见操作条。返回退出多选、切源清空选择、正序倒序不改变实际集数身份，Activity 重建会恢复已选集数。

下载队列先原子持久化，再由前台服务逐集解析、下载，避免长队列后面的 URL 提前过期。暂停、删除会取消当前解析；失败重试重新解析地址；重复任务按作品 ID、源 key 和原集索引去重。传输实时字节来自 DownloadManager，速度采用四秒滚动增量；界面通过 DiffUtil 更新原卡片，不每秒重建焦点。

## 一个播放器、一份观看记录

删除 OfflinePlayerActivity 和对应 manifest 入口。下载管理也进入 PlayerActivity；每次开始/切集优先查完整下载，命中后直接使用下载请求和只读本地缓存。控制栏、倍速、跳片头、自动下一集、Bangumi 状态和历史保存均复用原流程。未完整下载的集数继续使用在线解析。

已下载集数启动时不等待详情联网刷新。下载保留完整作品元信息，内容缓存清理后仍能选集和播放；老版本仅剩部分下载元信息时保留原集索引，不能把第 100 集因为排在列表首项就当成第 1 集，也不自动跨越缺失集数。

## 验证

- 24 项测试通过：原有 12 项，加上批量去重/暂停恢复/磁盘失败、1201 集倒序多选、源身份隔离、元信息兼容、速度采样和进度分组，以及原生控件、Media3 索引、本地缓存读取测试。
- Robolectric 使用真实 Android 字体和原生绘制引擎验证长按不触发播放、原索引选择、海报计数宽度、尺寸及进度重绑定焦点。渲染卡片位于 `app/build/reports/download-card.png`（测试样例，无真实海报）。测试环境按 [Robolectric 官方配置](https://robolectric.org/getting-started/)设置。
- `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleRelease` 通过；lint 无错误，仍有项目警告。
- 无已连接电视或可用 AVD。本轮尚未实机验证长按手感、完整导航回路、后台 WebView 解析第三方源、实际 HLS 下载及断网连续播放。

作品海报和焦点样式参考 [Android TV 卡片指南](https://developer.android.com/design/ui/tv/guides/components/cards)及[焦点系统指南](https://developer.android.com/design/ui/tv/guides/styles/focus-system)，作品/分集下载层次参考 [Plex Downloads](https://support.plex.tv/articles/downloads-overview/)。

进程终止或电视重启后，仍需打开下载分区恢复未暂停队列；未添加开机调度器。不支持 DRM 和直播。旧版遗留任务缺少解析元信息时，过期地址仍需删除后重新加入。
