# visual_spider5

面向小团队私有部署的零安装 Web 可视化网页采集工具。

> ⚠ **HTTP 明文传输警告**：本项目首版**不内置 HTTPS**，所有凭据、Cookie、页面内容以明文在网络中传输。
> **必须**把应用部署在可信 LAN / VPN 内，禁止直接暴露到公网。

## 规划文档

- [产品规格](./docs/product-spec.md)
- [系统架构](./docs/architecture.md)
- [里程碑路线图](./docs/roadmap.md)
- [领域词汇](./CONTEXT.md)
- [架构决策记录](./docs/adr/)
- [M7 Spec（首版收尾）](./docs/specs/m7.md)

## 部署（首版 `v0.1.0`）

部署形态 = 单个可执行 JAR（Spring Boot 内嵌 Tomcat） + 同机 PostgreSQL 16；不引入 Docker / Nginx / 反向代理 / 多实例。

- **平台特定步骤**：[`docs/deploy/windows.md`](./docs/deploy/windows.md)（M7-1）、[`docs/deploy/linux.md`](./docs/deploy/linux.md)（M7-2）
- **环境变量与配置参考**：[`docs/deploy/configuration.md`](./docs/deploy/configuration.md)
- **备份 / 恢复 / 升级**：[`docs/deploy/backup-restore-upgrade.md`](./docs/deploy/backup-restore-upgrade.md)（M7-3）
- **端到端验收脚本**：[`scripts/e2e/m7-acceptance.ps1`](./scripts/e2e/m7-acceptance.ps1) / [`m7-acceptance.sh`](./scripts/e2e/m7-acceptance.sh)（M7-4，双平台全链路 14 步）
- **发布说明与已知限制**：[`docs/deploy/release-notes-v0.1.0.md`](./docs/deploy/release-notes-v0.1.0.md)（M7-5）

> 版本锁矩阵（JDK / Spring Boot / Playwright / PostgreSQL 等）已迁移至 [`configuration.md` §6](./docs/deploy/configuration.md)。
> 首版不内置 HTTPS；TLS 终止属于首版后候选（roadmap §12），不在本版本部署形态内。

## 技术栈概览

固定 Java + Spring Boot + Vue 3 + Playwright for Java + PostgreSQL + Maven Wrapper；由根构建先构建前端，再由 Spring Boot 提供静态资源，最终交付一个可执行 JAR。

不引入：Docker / Testcontainers / Redis / Nginx / Elasticsearch / 任何新外部依赖。
PostgreSQL 是唯一外部基础设施。

## 启动（开发环境）

1. 准备 PostgreSQL 16 实例（推荐本地或容器化但不在生产用 Docker）：

   ```bash
   # Linux（apt 假设 PostgreSQL 官方 APT 源已配置）
   sudo apt-get install -y postgresql-16
   sudo -u postgres psql -c "CREATE USER visualspider WITH PASSWORD 'visualspider';"
   sudo -u postgres psql -c "CREATE DATABASE visualspider OWNER visualspider;"
   ```

   Windows：从 [PostgreSQL 16 安装包](https://www.enterprisedb.com/downloads/postgres-postgresql-downloads) 安装，
   用 pgAdmin 创建 `visualspider` 角色与同名数据库。

2. 注入初始管理员凭据（**密码 trim 后 ≥ 12 字符**，否则启动失败）：

   ```powershell
   # PowerShell 7
   $env:VISUALSPIDER_ADMIN_USERNAME = "admin"
   $env:VISUALSPIDER_ADMIN_PASSWORD = "change-me-please-12+"
   ./mvnw spring-boot:run
   ```

   ```bash
   # bash
   export VISUALSPIDER_ADMIN_USERNAME=admin
   export VISUALSPIDER_ADMIN_PASSWORD='change-me-please-12+'
   ./mvnw spring-boot:run
   ```

3. 启动脚本（含日志/PID 写入）：`scripts/windows/dev-start.ps1`、`scripts/linux/dev-start.sh`（M1-5 落地）。

## 验证

```bash
# 不依赖 PG：跑全部单元测试 + 已就绪的视觉浏览器 IT（不含 PG IT）
./mvnw verify

# 依赖真实 PG：执行全部 *IT（含 PG IT）；需先设置 VISUALSPIDER_ADMIN_* + pg.it.* 属性
./mvnw verify -Ppg-it -Dpg.it.url=jdbc:postgresql://localhost:5432/visualspider_test \
  -Dpg.it.username=visualspider -Dpg.it.password=visualspider
```

无 PG 时 `-Ppg-it` 跳过 `*IT` 并日志说明，**不**静默成功。

### 验证 M4 主链路

```bash
# 1) 跑 list 集成测试（真 PG + 真 Chromium + 5 fixture 一对一）
./mvnw verify -Ppg-it -Dpg.it.url=jdbc:postgresql://localhost:5432/visualspider_it \
  -Dpg.it.username=visualspider -Dpg.it.password=visualspider \
  -Dtest='ListRunIT,ListDedupeIT,ListPartialFailIT,ListCandidiateInferenceIT,ListPreviewIT,RunExportIT'

# 2) E2E smoke（真 JAR + PG + Chromium，10 步）：
#    标准列表 / dedup / PARTIAL_SUCCESS / cancel + lane / 0 残留
pwsh scripts/e2e/m4-smoke.ps1
```

5 形态 fixture 在 `src/test/resources/list/`：standard-list / card-grid / nested-list /
with-duplicates / partial-fail（最后一个含一个 U+0000 不可入库字段值 + 一个 setTimeout 延迟渲染，
触发真实 sink 行级失败 → `PARTIAL_SUCCESS + fail=1`）。

`m4-smoke.sh` 是 Linux/macOS 镜像占位，**不在本机执行**（同 M3 .sh 先例）；真实跨平台验收
延后到 M7（roadmap §7）。

`mvn package` 产出可执行 JAR，含 `static/index.html` + `Application.class` + `db/migration/V1__baseline.sql`。
