# Windows 部署手册

> 面向首版（`v0.1.0`）小团队私有部署。本手册**只描述首版路径**，不涉及 HTTPS / Nginx / 多实例 / Docker。
> 环境变量与版本锁表见 [`configuration.md`](./configuration.md)。

## 1. 前置条件

| 项 | 要求 | 安装来源 |
| - | - | - |
| OS | Windows 11（21H2 或更新） | 任意 SKU |
| JDK | Temurin 21 LTS（≥ 21.0.11） | <https://adoptium.net/temurin/releases/?version=21> |
| PostgreSQL | 16.x（推荐 EDB 安装器） | <https://www.enterprisedb.com/downloads/postgres-postgresql-downloads> |
| PowerShell | pwsh 7.4+ | <https://learn.microsoft.com/powershell/scripting/install/installing-powershell> |
| 端口 | TCP 8080（可在 `SERVER_PORT` 改） | 系统防火墙放行或保留 LAN 私网 |
| 磁盘 | 安装目录 ≥ 1 GB（含 Chromium 与日志） | C: 盘默认 |

> ⚠ **HTTP 明文传输**：本项目首版不内置 HTTPS。所有凭据、Cookie、页面内容在 LAN 内以明文传输。
> 必须部署在可信 LAN / VPN 内，禁止直接暴露公网。

## 2. 安装 JDK

1. 下载 Temurin 21 LTS 安装包（`.msi`）。
2. 双击安装；勾选 `Set JAVA_HOME` 与 `Add to PATH`（默认即勾选）。
3. 打开 PowerShell 7 验证：

   ```powershell
   java -version
   # openjdk version "21.0.x" 2024-xx  LTS
   ```

## 3. 安装 PostgreSQL 16

1. 启动 EDB 安装器，一路下一步。**记下超级用户密码**（postgres 用户）。
2. 在 `Components` 页面勾选 `pgAdmin 4`、`Command Line Tools`（含 psql）。
3. 安装完成后，`psql --version` 应输出 `psql (PostgreSQL) 16.x`。

### 创建数据库与角色

以 `postgres` 超级用户执行（替换 `<STRONG_PASSWORD>` 为真凭据，**不要用示例值**）：

```powershell
$env:PGPASSWORD = '<POSTGRES_SUPERUSER_PASSWORD>'
psql -U postgres -h localhost -c "CREATE USER visualspider WITH PASSWORD '<STRONG_PASSWORD>';"
psql -U postgres -h localhost -c "CREATE DATABASE visualspider OWNER visualspider;"
Remove-Item Env:PGPASSWORD
```

## 4. 创建部署目录

```powershell
New-Item -ItemType Directory -Force -Path 'C:\visual-spider' | Out-Null
```

> 默认路径为 `C:\visual-spider\`，脚本通过环境变量 `VISUALSPIDER_INSTALL_DIR` 参数化。
> 改路径需同步改 `config\visual-spider.env` 与脚本调用方式。

## 5. 拷贝脚本与 JAR

将以下文件拷贝到 `C:\visual-spider\`：

- `app.jar`（即 `visual-spider5-v0.1.0.jar` 的重命名版；脚本统一以 `app.jar` 引用）
- `install.ps1`、`check-env.ps1`、`start.ps1`、`stop.ps1`、`status.ps1`、`logs.ps1`
- （可选）`config\visual-spider.env`（首次拷贝时不存在，由 §6 创建）

## 6. 写入环境变量

在 `C:\visual-spider\config\visual-spider.env` 创建文件，**占位符必须替换为真实值**：

```text
# --- 数据源 ---
VISUALSPIDER_DATASOURCE_URL=jdbc:postgresql://localhost:5432/visualspider
VISUALSPIDER_DATASOURCE_USERNAME=visualspider
VISUALSPIDER_DATASOURCE_PASSWORD=<STRONG_PASSWORD>

# --- 初始管理员（仅首启 seed 一次）---
VISUALSPIDER_ADMIN_USERNAME=admin
VISUALSPIDER_ADMIN_PASSWORD=<STRONG_ADMIN_PASSWORD_AT_LEAST_12_CHARS>

# --- 端口 ---
SERVER_PORT=8080

# --- Flyway（生产必须 true）---
VISUALSPIDER_FLYWAY_ENABLED=true
```

> 完整字段表（含合法值、默认值、安全警告）见 [`configuration.md`](./configuration.md)。

## 7. 安装（install）

```powershell
cd C:\visual-spider
.\install.ps1
```

脚本依次：

1. 校验 JDK ≥ 21（`java -version`）。
2. 校验 PostgreSQL 客户端（`psql --version`）。
3. 准备目录：`C:\visual-spider\{app.jar, config\, logs\}`。
4. 调用 Playwright CLI 安装 Chromium，并把实际 revision 写入 `logs\install.log`。

退出码见脚本头部注释；任一步失败请按 `[FAIL]` 行提示修正。

## 8. 预检（check-env）

```powershell
cd C:\visual-spider
.\check-env.ps1
```

校验：JDK 版本、psql 可连通、`server.port` 未占用、磁盘剩余 ≥ 500MB、Chromium 已装。
**任一失败 exit code 非 0**；按 `[FAIL]` 修复后重跑。

## 9. 启动（start）

```powershell
cd C:\visual-spider
.\start.ps1
```

行为：

1. 检查 `logs\app.pid`，若指向活进程则拒绝重复启动（exit 2）。
2. 后台启动 `java -jar app.jar`，stdout/stderr 重定向到 `logs\app.{out,err}.log`，PID 写入 `logs\app.pid`。
3. 轮询 `http://localhost:8080/actuator/health`，最长 90s，超时 exit 3。

启动成功后继续输出 `logs\app.out.log` 最近 80 行。

## 10. 登录与冒烟

1. 浏览器打开 `http://<host>:8080/`。
2. 用 `VISUALSPIDER_ADMIN_USERNAME` / `VISUALSPIDER_ADMIN_PASSWORD` 登录。
3. 创建一个采集人员账号；用采集人员账号重新登录。
4. **单页采集冒烟**：选择单页模式 -> 选 HTTPS 公网目标（如 `https://example.com/`）-> 字段添加 `title`（CSS `h1`）-> 点保存 -> 进入配置会话（远程 Chromium 预览）-> 元素拾取确认 -> 切正式运行 -> 运行成功后导出 CSV。

> ⚠ **loopback 豁免仅供测试**：对 `http://127.0.0.1:xxxx/fixture.html` 等本地页面采集需要 `visualbrowser.target-url.allow-loopback=true`（脚本 M7-4 acceptance 使用）。**生产严禁开启**——开启等同于放弃回环 SSRF 防护。详见 [`configuration.md` §3](./configuration.md)。

## 11. 运维脚本速查

| 脚本 | 用途 | 关键参数 / 退出码 |
| - | - | - |
| `install.ps1` | 一次性安装 JDK 校验 + Chromium 装机 | exit 2 = JDK 缺 / JAR 缺；3 = Playwright 失败 |
| `check-env.ps1` | 启前预检 | exit 2..6 各对应一类失败 |
| `start.ps1` | 后台启动 + 等待 health UP | exit 2 = 重复启动；3 = 健康超时 / 进程退出 |
| `stop.ps1` | 优雅停止（PID 文件 + 超时强杀） | exit 0 = 未运行也返回成功；2 = 强杀超时 |
| `status.ps1` | 报告 PID + health，**不修改**状态 | exit 0 健康；1 进程在跑但 health 异常；2 未运行 |
| `logs.ps1` | tail 两个日志 | `-Lines` 默认 100；`-Follow` 跟随 |

所有脚本支持 `VISUALSPIDER_INSTALL_DIR` 覆盖安装路径；`-InstallDir` 参数优先级高于环境变量。

## 12. 停止与重启

```powershell
.\stop.ps1          # 优雅停止（30s 内退出），强杀回退 10s
.\status.ps1        # 确认停止（应返回未运行）
.\start.ps1         # 重新启动
```

## 13. 升级

见 [`backup-restore-upgrade.md`](./backup-restore-upgrade.md)。简要流程：

1. `pg_dump` 备份数据库（参考备份手册）。
2. `.\stop.ps1`。
3. 用新 JAR 覆盖 `app.jar`。
4. `.\start.ps1`（Flyway 自动前滚）。
5. `.\status.ps1` 确认 UP，登录冒烟。

## 14. 故障排查

| 现象 | 定位 | 处理 |
| - | - | - |
| `actuator/health` 持续 DOWN | `Get-Content logs\app.err.log -Tail 200` | 多数为 datasource 连不通；核对 `VISUALSPIDER_DATASOURCE_*` |
| 启动退出码 1 且日志含 `seed.admin.username 未配置` | 环境变量文件未加载 | `start.ps1` 当前不读 `.env` 文件；改用 `setx` 写到系统环境变量，或在当前 shell `source` 后启动 |
| 端口被占用 | `netstat -ano | findstr :8080` | 改 `SERVER_PORT` 或停占用者 |
| Chromium 启动报 `Executable doesn't exist` | `logs\install.log` 是否含 revision 行 | 重跑 `.\install.ps1` |
| `/actuator/metrics` 返回 404 | 默认未暴露（见 `configuration.md` §5） | **生产不建议**打开；排障临时开详见 §5 |

> **环境变量加载**：`start.ps1` 启动时若 `config\visual-spider.env` 存在，会读取 `KEY=VALUE` 行并注入当前进程（仅当同名变量在当前进程未设置时）。空行、`#` 开头、无 `=` 行忽略；值可用双引号包裹。优先级：当前 shell 已设变量 > `.env` > 系统环境变量（不会覆盖现有值）。
>
> 备选：`setx VISUALSPIDER_ADMIN_PASSWORD "..." /M`（需管理员，永久生效）；或在调用 `start.ps1` 前手工 `$env:VAR = "..."`。
>
> 注：`.env` 由 start.ps1 加载；`install.ps1` / `check-env.ps1` / `stop.ps1` / `status.ps1` / `logs.ps1` 不需要它。

## 15. 已知限制（首版）

- 不注册 Windows 服务；无开机自启（roadmap §7 已点名）。
- 单实例；同一业务数据库禁止两 JAR 并存（`SingleInstanceGuard`，M6-2）。
- 无 HTTPS；不内置 TLS 终止。
- 无 UI 配置面板；`retention.days` 经 admin REST 调整。

完整已知限制见 `docs/deploy/release-notes-v0.1.0.md`（M7-5 出稿后引用）。
