# Linux 部署手册

> 面向首版（`v0.1.0`）小团队私有部署。本手册**只描述首版路径**，不涉及 HTTPS / Nginx / 多实例 / Docker。
> 环境变量与版本锁表见 [`configuration.md`](./configuration.md)。

## 1. 前置条件

| 项 | 要求 | 安装来源 |
| - | - | - |
| OS | **Ubuntu Server 24.04 LTS**（单目标） | <https://releases.ubuntu.com/24.04/> |
| JDK | Temurin 21 LTS（≥ 21.0.11） | `apt: temurin-21-jdk`（见 §2） |
| PostgreSQL | 16.x（推荐 pgdg apt） | <https://www.postgresql.org/download/linux/ubuntu/> |
| 用户 | `visualspider`（系统用户，无登录 shell） | 由 `install.sh` 自动创建 |
| 端口 | TCP 8080（可在 `SERVER_PORT` 改） | 系统防火墙放行或保留 LAN 私网 |
| 磁盘 | `/opt/visual-spider` ≥ 1 GB（含 Chromium 与日志） | — |

> **Debian 12**：本手册不为 Debian 12 提供验收。大概率可用，但不承诺、不在首版发布支持范围内。

> ⚠ **HTTP 明文传输**：本项目首版不内置 HTTPS。所有凭据、Cookie、页面内容在 LAN 内以明文传输。
> 必须部署在可信 LAN / VPN 内，禁止直接暴露公网。

## 2. 安装 JDK

使用 Ubuntu 默认源安装 OpenJDK 21，或手动安装 Temurin 21 LTS：

```bash
# 方式 A: Adoptium temurin 源（推荐版本与项目一致）
sudo apt-get install -y wget apt-transport-https gpg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | \
    sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo $VERSION_CODENAME) main" \
    | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update
sudo apt-get install -y temurin-21-jdk

# 方式 B: Ubuntu 24.04 默认源（版本可能略晚于 Temurin）
sudo apt-get install -y openjdk-21-jdk
```

验证：

```bash
java -version
# openjdk version "21.0.x" 2024-xx  LTS
```

## 3. 安装 PostgreSQL 16

```bash
# 添加 pgdg apt 源
sudo apt-get install -y curl ca-certificates
curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | \
    sudo gpg --dearmor -o /usr/share/keyrings/pgdg.gpg
echo "deb [signed-by=/usr/share/keyrings/pgdg.gpg] http://apt.postgresql.org/pub/repos/apt $(. /etc/os-release; echo $VERSION_CODENAME)-pgdg main" \
    | sudo tee /etc/apt/sources.list.d/pgdg.list
sudo apt-get update
sudo apt-get install -y postgresql-16
```

`psql --version` 应输出 `psql (PostgreSQL) 16.x`。

### 创建数据库与角色

以 `postgres` 超级用户执行（替换 `<STRONG_PASSWORD>` 为真凭据，**不要用示例值**）：

```bash
sudo -u postgres psql -c "CREATE USER visualspider WITH PASSWORD '<STRONG_PASSWORD>';"
sudo -u postgres psql -c "CREATE DATABASE visualspider OWNER visualspider;"
```

## 4. 创建部署目录

由 `install.sh` 自动创建并设置权限：

- `/opt/visual-spider/` — 部署根目录
- `/opt/visual-spider/app.jar` — 可执行 JAR（拷贝源）
- `/opt/visual-spider/config/` — 配置文件（含 `visual-spider.env`）
- `/opt/visual-spider/logs/` — 日志目录（PID / out / err / install）

> 默认路径为 `/opt/visual-spider/`。改路径需通过 `INSTALL_DIR` 环境变量传入脚本。

## 5. 拷贝脚本与 JAR

将以下文件拷贝到 `/opt/visual-spider/`（或保持默认路径时直接进入该目录）：

- `app.jar`（即 `visual-spider5-v0.1.0.jar` 的重命名版；脚本统一以 `app.jar` 引用）
- `install.sh`、`check-env.sh`、`start.sh`、`stop.sh`、`status.sh`、`logs.sh`
- `visual-spider.service`（systemd unit 模板，单独路径安装；见 §10）

```bash
sudo cp scripts/linux/*.sh /opt/visual-spider/
sudo chmod +x /opt/visual-spider/*.sh
sudo chown -R visualspider:visualspider /opt/visual-spider
```

## 6. 写入环境变量

在 `/opt/visual-spider/config/visual-spider.env` 创建文件，**占位符必须替换为真实值**：

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

权限：`chmod 600 /opt/visual-spider/config/visual-spider.env`（仅 root 与 visualspider 可读）。

> 完整字段表（含合法值、默认值、安全警告）见 [`configuration.md`](./configuration.md)。

## 7. 安装（install）

```bash
sudo /opt/visual-spider/install.sh
```

脚本依次：

1. 创建系统用户 `visualspider`（如已存在则复用）。
2. 准备 `/opt/visual-spider/{app.jar, config/, logs/}` 并 `chown`。
3. 校验 JDK ≥ 21（`java -version`）。
4. 校验 PostgreSQL 客户端（`psql --version`；缺失则警告不阻断）。
5. 拷贝 JAR 到 `/opt/visual-spider/app.jar`。
6. **Linux 专属**：`playwright install-deps chromium`（安装 Chromium 系统依赖）。
7. 以 `visualspider` 用户身份执行 `playwright install chromium`，把实际 revision 写入 `logs/install.log`。

退出码见脚本头部注释；任一步失败请按 `[FAIL]` 行提示修正。

## 8. 预检（check-env）

```bash
sudo -u visualspider /opt/visual-spider/check-env.sh
```

> 推荐以 `visualspider` 身份运行，确保 Chromium 路径解析与运行时一致。

校验：JDK 版本、psql 可连通、`SERVER_PORT` 未占用、磁盘剩余 ≥ 1024 MB、Chromium 已装。
**任一失败 exit code 非 0**；按 `[FAIL]` 修复后重跑。

## 9. 启动与停止（脚本路径）

> systemd 是首版 Linux 推荐路径（见 §10）。本节为回退路径，便于不开机自启的环境或排障。

```bash
# 启动（root 运行时自动切换到 visualspider；当前用户为 visualspider 时直接启）
sudo /opt/visual-spider/start.sh          # exit 0 UP / 2 重复 / 3 健康超时

# 状态（不修改任何状态）
sudo -u visualspider /opt/visual-spider/status.sh
# exit 0 健康；1 进程在跑但 health 异常；2 未运行

# 停止
sudo /opt/visual-spider/stop.sh           # exit 0 已停；2 强杀超时

# 日志
sudo -u visualspider /opt/visual-spider/logs.sh
# 加 --follow 持续跟踪；--lines N 控制行数
```

启动成功后继续显示 `logs/app.out.log` 最近 80 行。

## 10. systemd 路径（推荐）

将 unit 模板拷贝到 systemd 目录并启用：

```bash
sudo cp scripts/linux/visual-spider.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable visual-spider.service   # 开机自启
sudo systemctl start  visual-spider.service   # 立即启动
```

常用命令：

| 操作 | 命令 |
| - | - |
| 查看运行状态 | `sudo systemctl status visual-spider` |
| 启动 / 停止 / 重启 | `sudo systemctl start\|stop\|restart visual-spider` |
| 开机自启启用 / 禁用 | `sudo systemctl enable\|disable visual-spider` |
| 最近日志（journal） | `sudo journalctl -u visual-spider -n 200` |
| 实时跟踪（journal） | `sudo journalctl -u visual-spider -f` |
| 进程文件输出（与脚本共用） | `tail -f /opt/visual-spider/logs/app.out.log` |

`visual-spider.service` 关键字段：

- `User=visualspider`、`Group=visualspider` —— 与脚本路径一致
- `EnvironmentFile=/opt/visual-spider/config/visual-spider.env` —— 与 start.sh 读取同一文件
- `Restart=on-failure`、`RestartSec=5` —— 崩溃自动重启
- `StandardOutput=append:/opt/visual-spider/logs/app.out.log` —— 与脚本路径日志同源
- `TimeoutStopSec=30` —— SIGTERM → 30s → SIGKILL（与 `stop.sh` 一致）

> 两条路径的关系：systemd 启动后，PID 文件由 `start.sh` 维护（systemd 也写 cgroup / journal 但不动 `logs/app.pid`）；
> 因此 `status.sh` / `stop.sh` 与 systemd 都能用——`stop.sh` 通过 PID 文件 SIGTERM，与 systemd 的 `TimeoutStopSec` 行为一致。

## 11. 登录与冒烟

1. 浏览器打开 `http://<host>:8080/`。
2. 用 `VISUALSPIDER_ADMIN_USERNAME` / `VISUALSPIDER_ADMIN_PASSWORD` 登录。
3. 创建一个采集人员账号；用采集人员账号重新登录。
4. **单页采集冒烟**：选择单页模式 -> 选 HTTPS 公网目标（如 `https://example.com/`）-> 字段添加 `title`（CSS `h1`）-> 点保存 → 进入配置会话（远程 Chromium 预览）-> 元素拾取确认 -> 切正式运行 -> 运行成功后导出 CSV。

> ⚠ **loopback 豁免仅供测试**：对 `http://127.0.0.1:xxxx/fixture.html` 等本地页面采集需要 `visualbrowser.target-url.allow-loopback=true`（脚本 M7-4 acceptance 使用）。**生产严禁开启**——开启等同于放弃回环 SSRF 防护。详见 [`configuration.md` §3](./configuration.md)。

## 12. 运维脚本速查

| 脚本 | 用途 | 关键参数 / 退出码 |
| - | - | - |
| `install.sh` | 一次性安装：用户 + 目录 + Chromium 装机 | exit 2 = 缺 JDK/缺 JAR/缺 root；3 = Playwright 失败 |
| `check-env.sh` | 启前预检 | exit 2..6 各对应一类失败 |
| `start.sh` | 后台启动 + 等待 health UP | exit 2 = 重复启动；3 = 健康超时 / 进程退出 |
| `stop.sh` | 优雅停止（PID 文件 + 超时强杀） | exit 0 = 未运行也返回成功；2 = 强杀超时 |
| `status.sh` | 报告 PID + health，**不修改**状态 | exit 0 健康；1 进程在跑但 health 异常；2 未运行 |
| `logs.sh` | tail 两个日志 | `--lines` 默认 100；`--follow` 跟随 |

所有脚本支持 `INSTALL_DIR` 覆盖安装路径；同名环境变量优先级高于脚本内默认值。

## 13. 升级

见 [`backup-restore-upgrade.md`](./backup-restore-upgrade.md)。简要流程（systemd 路径）：

```bash
# 1. 备份
pg_dump -Fc > /var/backups/visualspider_$(date +%F).dump

# 2. 停
sudo systemctl stop visual-spider

# 3. 换 JAR
sudo cp new-app.jar /opt/visual-spider/app.jar

# 4. 启（Flyway 自动前滚）
sudo systemctl start visual-spider

# 5. 冒烟
curl http://localhost:8080/actuator/health
```

脚本路径下用 `stop.sh` 替 `systemctl stop`、`start.sh` 替 `systemctl start`。

## 14. 故障排查

| 现象 | 定位 | 处理 |
| - | - | - |
| `actuator/health` 持续 DOWN | `tail -n 200 /opt/visual-spider/logs/app.err.log` | 多数为 datasource 连不通；核对 `VISUALSPIDER_DATASOURCE_*` |
| 启动 exit 1 且日志含 `seed.admin.username 未配置` | `.env` 未加载 | `start.sh` 当前会读 `config/visual-spider.env`；检查文件存在 + 权限 + 路径；或 `export VAR=...` 后手工启动 |
| 端口被占用 | `ss -ltnp \| grep :8080` | 改 `SERVER_PORT` 或停占用者 |
| Chromium 启动报 `Executable doesn't exist` | `grep -i revision /opt/visual-spider/logs/install.log` | 重跑 `install.sh`（root） |
| `/actuator/metrics` 返回 404 | 默认未暴露（见 `configuration.md` §5） | **生产不建议**打开；排障临时开详见 §5 |
| systemd unit 加载失败 | `sudo systemd-analyze verify /etc/systemd/system/visual-spider.service` | 常见：路径错、用户不存在、`/opt/visual-spider` 权限 |

> **环境变量加载**：`start.sh` 启动时若 `/opt/visual-spider/config/visual-spider.env` 存在，会读取 `KEY=VALUE` 行并 export（仅当同名变量在当前进程未设置时）。空行、`#` 开头、无 `=` 行忽略；值可用双引号包裹。优先级：当前 shell 已设变量 > `.env` > 系统环境变量（不会覆盖现有值）。
>
> 备选：在调用 `start.sh` 前手工 `export VISUALSPIDER_ADMIN_PASSWORD=...`；或在 systemd unit 用 `Environment=KEY=VALUE` 覆盖（`EnvironmentFile` 之后）。
>
> 注：`.env` 由 `start.sh` 加载；`install.sh` / `check-env.sh` / `stop.sh` / `status.sh` / `logs.sh` 不需要它。

## 15. 已知限制（首版）

- Linux 单目标 = Ubuntu Server 24.04 LTS；Debian 12 一句话说明（见 §1）。
- 单实例；同一业务数据库禁止两 JAR 并存（`SingleInstanceGuard`，M6-2）。
- 无 HTTPS；不内置 TLS 终止。
- 无 UI 配置面板；`retention.days` 经 admin REST 调整。
- systemd `Restart=on-failure` 不重启 OOM-killed 进程（OOM 视同非失败）；遇 OOM 须人工调查内存配置。

完整已知限制见 `docs/deploy/release-notes-v0.1.0.md`（M7-5 出稿后引用）。