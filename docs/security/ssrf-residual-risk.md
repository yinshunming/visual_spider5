# SSRF 残余风险与部署侧补偿措施（M6-1）

> 适用：首版（v0.0.x）默认配置的部署者。
> 范围：服务端应用层 SSRF 防护已覆盖的部分，以及"应用层防不住、需要部署侧补"
> 的残余风险。
> 配套文档：[`docs/specs/m6.md`](../specs/m6.md) §D1–D5；
> [`product-spec.md`](../product-spec.md) §安全边界。

## 1. 已覆盖（应用层防护）

| 维度 | 实现位置 | 行为 |
| --- | --- | --- |
| 协议白名单 | `PublicTargetUrlPolicy`（`visualbrowser.internal`） | 仅 `http://` / `https://`；其他 scheme（`file:`、`data:`、`ftp:`、`javascript:` 等）一律拒绝 |
| 主机名语法 | `BasicTargetUrlPolicy`（内嵌复用） | DNS label 校验、`≤253`、尾点处理 |
| IP 字面量分类 | `IpAddressClassifier`（纯函数） | IPv4 / IPv6 / IPv4-mapped 全部分类；拒绝回环 / 私有 / 链路本地（含云元数据 `169.254.169.254`）/ 保留 / 组播 / 未指定 |
| 混合表示 | `PublicTargetUrlPolicy.isIpLiteral` | IPv6 含 `:`、十进制点分（`1–4` 组）、超长整数（`2130706433`）、十六进制（`0x` 前缀）全部走 IP 字面量路径 |
| DNS 解析 | `InetDnsResolver` + `CachingDnsResolver`（TTL 30s） | 全部 A/AAAA 结果逐一 IP 分类；任一被拒即整体拒；解析失败（NXDOMAIN）fail-closed |
| 请求拦截 | `SsrfRouteGuard` + `BrowserContext.route("**/*")` | 顶层导航 / 重定向 / iframe / 图片 / 脚本 / XHR 全部经策略；不通过 `route.abort()`；通过 `route.resume()` |
| 复验 | 运行执行器既有 `currentUrl()` 校验保留 | 拦截是第一道防线，复验是纵深防御 |
| 仅 loopback 豁免 | `visualbrowser.target-url.allow-loopback` | 仅 `it` / `dev` profile 置 `true`；不豁免任何私有网段 / 元数据 |
| 指标 | micrometer `ssrf.blocked` | 每次拦截 +1；通过 `/actuator/metrics` 可查 |
| 事件 | `run_event(stage="SSRF_BLOCKED", level=WARN, message="host=<host>")` | 每个 run 每个 host 至多写一次（去重在 `DefaultRunPageHandleProvider` 闭包内） |

## 2. 已接受但应用层无法完全消除的残余风险

### 2.1 DNS rebinding（TOCTOU 竞态）

**机理**：策略在 route 回调里做 `InetAddress.getAllByName` 解析并校验 → 通过 →
`route.resume()` → Chromium 重新走自己的 DNS 栈去连接目标 → **若权威 DNS
在这两步之间把 A 记录从公网切到内网 IP**，浏览器仍会发出请求到内网 IP。

**当前缓解**：

- `CachingDnsResolver` TTL 30s：在同一 host 短时间内复用同一解析结果，
  缩小"先解析后连接"之间的时间窗口。但**不消除**首次请求的窗口。
- 拦截回调同步在 Playwright dispatcher 线程执行；`route.resume()` 在解析校验
  通过后才被调用，与浏览器自身解析之间仍存在毫秒到秒级窗口。

**已知未消除**：应用层不可能根治此 TOCTOU。这是公认的行业限制。

### 2.2 操作系统层路径

**机理**：应用层只防"被诱导访问内网"。如果操作系统层（例如 Kubernetes network
policy、iptables、Cloud SG 规则）允许进程对内网网段 / 云元数据地址的出站，
即便应用层 SSRF 防护全程生效，攻击者仍可通过其他路径到达那些地址
（容器逃逸、其它服务、宿主机进程等）。

**当前缓解**：无；属部署侧问题。

### 2.3 DNS 解析结果在 `route.abort` 与 Chromium 实际网络行为之间

**机理**：`route.abort()` 在 Chromium 内部有几种错误码（`failed` / `blockedbyclient`
/ `blockedbyresponse` 等）。当前默认 `abort()` 等价 `failed`，
浏览器侧表现为 `net::ERR_FAILED`。如果 Chromium 在 abort 完成前已经将请求
加入 socket pool，可能在低概率下仍有半连接。**已知未消除**。

## 3. 部署侧推荐补偿

> 以下不是产品功能承诺，是给运维 / 部署评审的"在产品已防的基础上再加深一层"
> 建议；M7 跨平台验收、M8+ 部署文档可按需引用。

### 3.1 主机 / 容器层出口防火墙（**推荐**）

让运行用户对以下地址段 / 目标的出站被主机防火墙阻断。这样即便应用层 SSRF
防护被绕过，连接也无法真正建立。

**Linux（iptables 示例，思路方向）**：

```bash
# 默认 DROP 内网出站，仅放行公网
iptables -A OUTPUT -d 10.0.0.0/8 -j DROP
iptables -A OUTPUT -d 172.16.0.0/12 -j DROP
iptables -A OUTPUT -d 192.168.0.0/16 -j DROP
iptables -A OUTPUT -d 169.254.0.0/16 -j DROP   # 含云元数据
iptables -A OUTPUT -d 100.64.0.0/10 -j DROP   # CGNAT
iptables -A OUTPUT -d 127.0.0.0/8 -j DROP     # 仅当不需要本地 fixture 时
# IPv6 同理
ip6tables -A OUTPUT -d fc00::/7 -j DROP
ip6tables -A OUTPUT -d fe80::/10 -j DROP
```

**Linux（nftables 示例）**：

```nft
table inet ssrf_egress {
  chain output {
    type filter hook output priority 0; policy accept;
    ip daddr { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16,
                169.254.0.0/16, 100.64.0.0/10 } drop
    ip6 daddr { fc00::/7, fe80::/10 } drop
  }
}
```

**Windows 防火墙（PowerShell 思路方向）**：

```powershell
New-NetFirewallRule -DisplayName "Block SSRF Egress" `
  -Direction Outbound -Action Block `
  -RemoteAddress 10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,169.254.0.0/16,100.64.0.0/10
```

### 3.2 Kubernetes / 容器运行时

- `NetworkPolicy` egress 默认 deny，显式 allow 公网（按域名或 IP 段）。
- 不要给应用 Pod 任何 `hostNetwork: true`。
- 云元数据访问：`metadata.knative.io/obfuscate: "true"`（Knative）或
  Pod spec `metadata: { annotations: { "container.apparmor.security.beta.kubernetes.io": "..." } }`；
  主流云厂商（EKS / GKE / AKS）提供"屏蔽 IMDS"选项。

### 3.3 DNS 锁定（可选强化）

若全部目标都在已知 DNS 服务商，可在宿主机层面用 `unbound` / `dnsmasq` 做 DNS
sinkhole：把 RFC 1918 / CGNAT / 链路本地 / TEST-NET 地址段全部解析为
`NXDOMAIN` 或 `0.0.0.0`。这与 §3.1 防火墙组合可获得"应用层 + DNS 层 + 网络层"
三层防护。**注意**：本系统不内置 DNS 锁定；这是部署侧的可选项。

### 3.4 监控与告警

应用层提供 `ssrf.blocked` 指标（`/actuator/metrics/ssrf.blocked`）。建议在
监控 / 告警系统里设阈值：单小时 / 单任务拦截次数超过某值（依业务定）即触发告警，
用于发现"有人在试 SSRF"。

## 4. 能力边界声明

本系统的 SSRF 防护**仅覆盖**：采集运行时被诱导访问公网 / 私有 / 元数据
地址的请求路径。具体地：

- ✅ 拦截任务定义里的恶意 URL
- ✅ 拦截浏览器在运行过程中跟随重定向到内网
- ✅ 拦截页面拉取内网子资源
- ✅ 拦截混合表示 IP（`127.1` 等）
- ❌ 不防操作系统 / 容器层已放行的访问
- ❌ 不防 DNS resolver 被攻击者控制（MITM / 缓存投毒）
- ❌ 不防目标站点主动连接到我们的服务（SSRF 方向）
- ❌ 不防运维误操作直接 curl 内网（需 §3 部署侧补偿）

## 5. 与其他模块的关系

- `run` 模块的 `DefaultRunPageHandleProvider` 接受被拦截 host 回调，写
  `run_event(WARN, SSRF_BLOCKED)`；事件 message 只含 host（脱敏约定）。
- `SingleDomainPacingPolicy` 与本工单无直接关系；SSRF 拦截在
  导航 / 子资源发起阶段就 abort，pacing 计数器不会被消耗。
- `PageStopDetector` 的 429 / 403 / 验证码检测独立运行；SSRF 拦截不会触发
  它的 STOP_* 事件，因为阻断原因不属于"被目标拒绝"。

## 6. v0.1.1+ 启动期 fail-fast（M8-3）

v0.1.0 时 `visualbrowser.target-url.allow-loopback=true` 没有任何启动期检查；
prod 误配为 `true` 会启动成功，直到运行时首次 SSRF 触发才报错，且错误信息
只说"被运行时策略拦截"。

v0.1.1 起（[`docs/specs/m8.md`](../specs/m8.md) D3，issue #62），新增
[`LoopbackStartupFailFastValidator`](../../src/main/java/com/visualspider/shared/config/LoopbackStartupFailFastValidator.java)：

- 在 Spring Bean 创建期（早于端口绑定）读取 `visualbrowser.target-url.allow-loopback` 与 `spring.profiles.active`。
- 当 `allow-loopback=true` 且激活 profile **不在** `dev` / `it` / `smoke` / `e2e` 之列 → 抛 `IllegalStateException`，错误信息明确指向 [`docs/deploy/configuration.md §3`](../deploy/configuration.md)。
- 激活 profile 包含上述白名单之一 → 仅 INFO 日志放行。

**这不是策略变更**：运行时 SSRF 拦截逻辑（`PublicTargetUrlPolicy` /
`IpAddressClassifier` / DNS 解析 + 重定向 + 子资源）保持 M6-1 不变；本类只在
启动期补一层 fail-fast，让 prod 误配在启动第一行就暴露。

**残余风险未变**：本类只防"prod 误配 allow-loopback=true"这一类配置错误；
不替代运行时拦截，不防部署侧绕过（见 §3 / §4）。