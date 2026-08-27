# 配置参考

> 首版（`v0.1.0`）生产部署配置参考。所有环境变量、版本锁、运行参数与安全警告汇总。
> 平台特定步骤见 [`windows.md`](./windows.md) 与 [`linux.md`](./linux.md)（M7-2 出稿）。

## 1. 环境变量全表

> 应用读取逻辑见 `src/main/resources/application.yml`。
> Spring 占位符语法：`${KEY:default}` —— 括号内是默认值，未设置环境变量时使用。

| 变量 | 必填 | 默认值 | 用途 | 备注 |
| - | - | - | - | - |
| `VISUALSPIDER_DATASOURCE_URL` | 生产必填 | `jdbc:postgresql://localhost:5432/visualspider` | JDBC URL | 必须指向 PostgreSQL 16；详见 §2 |
| `VISUALSPIDER_DATASOURCE_USERNAME` | 生产必填 | `visualspider` | DB 用户 | 须有 `visualspider` 库读写权限 |
| `VISUALSPIDER_DATASOURCE_PASSWORD` | 生产必填 | `visualspider` | DB 密码 | **生产必须替换**；默认值仅供 dev profile |
| `VISUALSPIDER_ADMIN_USERNAME` | 生产必填 | （空） | 初始管理员用户名 | 仅首启 seed 一次；缺则启动失败（`SeedAdminValidator`） |
| `VISUALSPIDER_ADMIN_PASSWORD` | 生产必填 | （空） | 初始管理员明文密码 | **trim 后 ≥ 12 字符**；BCrypt 哈希后入库 |
| `VISUALSPIDER_FLYWAY_ENABLED` | 推荐 `true` | `true` | 是否启用 Flyway 自动 migration | 生产必须 `true`；rollback 场景详见 `backup-restore-upgrade.md` |
| `SERVER_PORT` | 否 | `8080` | HTTP 监听端口 | 改端口需同步放行系统防火墙；脚本通过此变量自动对齐 |
| `VISUALSPIDER_INSTALL_DIR` | 否（脚本用） | `C:\visual-spider` / `/opt/visual-spider` | 部署根目录 | 仅运维脚本读取；应用本身不依赖 |

### 1.1 不通过环境变量配置的项

- **`retention.days`**（运行/结果保留天数，默认 30）：**经 admin REST 调整**，
  `GET/PUT /api/admin/settings/retention.days`（详见 M6-6 `AdminSettingController`）。
  缺行或非法值由 `RetentionCleanupTask` fallback 30。
- **`visualbrowser.target-url.allow-loopback`**（SSRF 回环豁免）：应用启动参数，**生产严禁开启**；详见 §3。
- **`management.endpoints.web.exposure.include`**：actuator 暴露范围，详见 §5。

## 2. 数据库配置细则

```text
VISUALSPIDER_DATASOURCE_URL=jdbc:postgresql://<host>:<port>/<database>
# 默认 5432 端口可省；sslmode 默认 prefer。
# 应用到 PG 的链路安全由部署网络层（LAN / VPN）保证,首版不在 JDBC URL 内强制 TLS。
# 例:jdbc:postgresql://db.local:5432/visualspider
```

字符集：库、role、应用 JDBC 客户端需一致；推荐 `UTF8`（PG 默认）。

权限最小化：`visualspider` 角色对 `visualspider` 库需有 `CONNECT / CREATE / TEMP`（Flyway 启用）；
**不应**授予 `SUPERUSER` 或 `BYPASSRLS`。

## 3. SSRF loopback 豁免（仅测试）

参数 `--visualbrowser.target-url.allow-loopback=true`：

- 默认 `false`（生产）；开启后**仅豁免回环（127/8 + ::1）**。
- 私有 / 链路本地 / 保留 / 云元数据地址仍被拦截（详见 `IpAddressClassifier`）。
- **生产严禁开启** —— 开启等同于放弃回环 SSRF 防护（M6-1 D4 决策）。
- 用途：本地 fixture 测试、M7-4 acceptance 脚本（`python -m http.server` 本地 fixture）。

测试用法示例（验收脚本）：

```bash
java -jar app.jar --visualbrowser.target-url.allow-loopback=true
```

## 4. 日志

- 路径：`logs/app.log`（Spring Boot logback `RollingFileAppender`，`logback-spring.xml`）。
- 滚动：单文件 10MB × 10 文件 + 30 天归档 + 总 100MB 上限。
- 敏感字段脱敏：`LoggingScrubber` 自动擦除 `password`、`Cookie`、`Authorization`、`XSRF-TOKEN` 等。
- 运维脚本输出：`logs/app.out.log` / `logs/app.err.log`（`java -jar` 重定向）；
  `logs/install.log`（Playwright CLI 安装日志，含实际 Chromium revision）。

## 5. 健康与指标端点（actuator）

`management.endpoints.web.exposure.include` 默认 `health,info`。

| 端点 | 状态 | 用途 |
| - | - | - |
| `/actuator/health` | **默认暴露** | 探活 + 组件状态（含 `db`、`diskSpace`、`browser` lane） |
| `/actuator/info` | **默认暴露** | 构建信息 |
| `/actuator/metrics` | **默认不暴露** | Micrometer 指标（M6-6 已埋 6 项；生产关 `/actuator/metrics` 端点不影响指标采集） |

### 5.1 临时开启 metrics（排障）

**生产不建议长期打开**；排障期间通过外部 `application.yml` 覆盖：

```yaml
# C:\visual-spider\config\application.yml（或 /opt/visual-spider/config/）
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

Spring Boot 加载优先级：JAR 内 `application.yml` < `application-{profile}.yml` < 外部 `application.yml`（与 JAR 同目录或 `config/` 子目录）。

### 5.2 Micrometer 指标清单

M6-6 落地的 6 项指标（`MeterRegistry` 内注册，端点不暴露不影响计数）：

- `visualspider.run.started.total` / `visualspider.run.completed.total`（含 terminal 状态 tag）
- `visualspider.run.duration`（Timer）
- `visualspider.run.pages.fetched`
- `visualspider.run.records.persisted`
- `visualspider.lane.browsers.active`

详见 `src/test/java/com/visualspider/result/MetricsRegistrationTest.java`。

## 6. 版本锁矩阵

首版（`v0.1.0`）锁定以下版本；后续里程碑遵守同一矩阵。

| 维度 | 版本 |
| - | - |
| JDK | 21.0.11 LTS（Eclipse Temurin） |
| Maven | 3.9.11（Maven Wrapper 携带） |
| Spring Boot | 3.4.13 |
| Playwright for Java | 1.61.0（其捆绑的 Chromium revision 由 `install` 脚本安装时打印，不手工抄进文档） |
| PostgreSQL | 16.x（EDB 安装器 / pgdg apt） |
| Flyway | 10.x |
| Vue | 3.5.x |
| Vite | 6.x |
| TypeScript | 5.6.x |
| Node | v22.14.0（frontend-maven-plugin 固定） |

> Maven / Gradle 选型固定 Maven（`./mvnw`）；首版不引入 Gradle。

## 7. 安全相关默认

- **HTTP 明文**（§1 已警告）：无 HTTPS；同源部署；`SameSite=Lax` HttpOnly Cookie。
- **认证**：Spring Security 服务端 `HttpSession` + BCrypt + CSRF。
- **CSRF**：启用（除 `/actuator/health`、静态资源、`/ws/**` 放行）。
- **单实例**：`SingleInstanceGuard` 通过 PostgreSQL advisory lock（M6-2）；同库双 JAR 第二个启动失败。

## 8. 部署形态与边界（澄清）

- **首版交付物 = 单个可执行 JAR**，直接提供 HTTP（Spring Boot 内嵌 Tomcat）。
- **不引入** Docker / Nginx / 反向代理 / Redis / 队列 / Elasticsearch / 对象存储 / 多实例。
- 反向代理 TLS 终止属**首版后候选**（roadmap §12），**不在首版部署形态内**。
- Linux 部署首选 systemd（`visual-spider.service`）；Windows 不注册服务（脚本 + PID 文件 + `/actuator/health`）。

## 9. 文档示例凭据约定

所有文档示例一律为占位符（`<STRONG_PASSWORD>`、`<STRONG_ADMIN_PASSWORD_AT_LEAST_12_CHARS>`）；
演练产生的真实凭据**不进入**版本控制。
