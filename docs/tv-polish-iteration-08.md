# 0.3.5：AGE 全站请求的证书兼容

## 证据与范围

2026-10-13 实际连接 `api.agedm.io:443`，服务器提供以下证书链：

- `CN=m.agedm.io`，SAN 包含 AGE API 域名；有效期自 2026-10-12 15:08:40 UTC 至 2027-04-28。
- 中间证书 `SSL.com TLS Issuing RSA CA R1`。
- 所需根证书 `SSL.com TLS RSA Root CA 2022`（服务器无需发送根证书）。

Android 12 初始 CA 列表不包含该根证书的 subject hash `2c63f966`；电视未更新根证书库时，与用户报告的 `Trust anchor for certification path not found` 相符。未读取用户电视证书库，不能排除电视时间或当地网络造成的其他证书故障。

## 修复

使用 Android Network Security Configuration，仅对 `agedm.io` 及子域增加官方公共根证书，同时保留系统信任。没有修改 TrustManager、HostnameVerifier 或播放网络逻辑；证书签名、域名、有效期验证仍由平台执行。现有局域网二维码 HTTP 通信配置保持不变。

这是应用级域名配置，覆盖共用 `api.agedm.io` 的首页、目录、更新、推荐、搜索、排行和详情，以及 AGE 网页请求，不是仅对扫码搜索设置例外。

根证书来源：[SSL.com 官方证书仓库](https://www.ssl.com/repository/)，[PEM 下载](https://ssl.com/repo/certs/SSLcom-TLS-Root-2022-RSA.pem)。与官方公布的 SHA-256 指纹核对一致：

`8FAF7D2E2CB4709BB8E0B33666BF75A5DD45B5DE480F8EA8D4BFE6BEBC17F2ED`

证书在 `app/src/main/res/raw/sslcom_tls_root_2022_rsa.pem`；测试目录的叶子和中间证书是 2026-10-13 抓取的公开证书，仅作固定时间的回归验证，不作为信任锚。

扫码搜索上游失败现在返回 HTTP 503 和可读的错误消息，不再伪装为空结果。手机页面区分证书错误、连接失败和真正零结果，并将错误同步到电视现有搜索区域；下一次正常搜索会清除错误。电视普通搜索的证书异常也显示中文提示，完整异常保留在日志。

## 验证

- PKIX 验证：缺少新根时当前 AGE 证书链失败，使用核验后的根时通过，过期证书仍被拒绝。
- XML 配置检查：新增根只限 AGE，保留系统信任和局域网 HTTP 配置。
- 手机搜索真实本地 HTTP 路由：证书异常返回 503，错误可同步；之后成功的空搜索返回 200 / `[]`，错误状态可清除。
- 携带该 CA 的 HTTPS 实际搜索 `GATE`，接口返回 9 个结果。
- 首页 `home-list`、目录 `catalog`、详情 `detail/20150004` 均返回 HTTP 200 及正确结构的 JSON。
- 隔离浏览器检查手机页面：证书失败、网络失败、零结果、重试成功和同步电视错误清除均通过。
- 全部 55 项测试、`lintDebug`、Debug/Release 构建通过。版本为 0.3.5 / versionCode 10。

参考：[Android 网络安全配置](https://developer.android.com/privacy-and-security/security-config)、[Android 12 CA 列表](https://android.googlesource.com/platform/system/ca-certificates/+/android-12.0.0_r1/files/)。
