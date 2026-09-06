# 备份 / 恢复 / 升级手册

> 首版（`v0.1.0`）部署后的数据安全操作指南。覆盖 `pg_dump`/`pg_restore` 备份恢复、版本升级、Flyway 前滚失败回滚。
> 平台步骤引用 [`windows.md`](./windows.md) §13 与 [`linux.md`](./linux.md) §13。

## 1. 备份

### 1.1 全库逻辑备份（推荐）

```bash
# Linux（visualspider 用户 + 库 + 自定义输出路径）
pg_dump -h localhost -U visualspider -d visualspider \
    -Fc -f /var/backups/visualspider/visualspider_$(date +%F).dump

# Windows（PowerShell）
$stamp = Get-Date -Format 'yyyy-MM-dd'
& pg_dump -h localhost -U visualspider -d visualspider `
    -Fc -f "C:\backups\visualspider_$stamp.dump"
```

参数说明：

| 选项 | 用途 |
| - | - |
| `-Fc` | 自定义压缩格式（`pg_restore` 恢复；远小于 `-Fp` 纯文本） |
| `-h` / `-U` / `-d` | 主机 / 用户 / 库 |
| `-f` | 输出文件 |
| `--no-owner` | （可选）恢复时不强制 owner；迁库时常用 |
| `--clean --if-exists` | （可选）备份里带 DROP 语句；恢复时覆盖 |

### 1.2 备份全局对象（角色与权限）

```bash
pg_dumpall -h localhost -U postgres --globals-only \
    -f /var/backups/visualspider/globals_$(date +%F).sql
```

### 1.3 频次与保留建议

- **每日一次** 全库逻辑备份（业务低谷时段；cron 例：`0 2 * * *`，见 §1.4）。
- 保留 7 天 daily + 4 周 weekly + 12 个月 monthly（小型私有部署可简化为 7 天 daily + 4 周 weekly）。
- 备份文件需异地至少一份（rsync / NAS / 云存储；首版不在应用内提供异地复制）。

### 1.4 cron 示例（Linux）

```bash
# /etc/cron.d/visualspider-backup
0 2 * * * visualspider /usr/bin/pg_dump -h localhost -U visualspider \
    -d visualspider -Fc -f /var/backups/visualspider/visualspider_$(date +\%F).dump
0 3 * * 0 visualspider /usr/bin/pg_dumpall -h localhost -U postgres \
    --globals-only -f /var/backups/visualspider/globals_$(date +\%F).sql
```

注意：

- cron 环境无 `PGPASSWORD`，须在 `~/.pgpass` 写入 `localhost:5432:visualspider:visualspider:<PASSWORD>`，权限 `chmod 600`。
- 或在 cron 命令前 `PGPASSWORD=<PASSWORD>` 注入（仅 root cron 可用，进程列表可见）。

## 2. 恢复

### 2.1 到新库（推荐路径——不破坏现有库）

```bash
# 1. 创建空库
sudo -u postgres createdb visualspider_restore -O visualspider

# 2. 恢复
pg_restore -h localhost -U visualspider \
    -d visualspider_restore \
    --no-owner --role=visualspider \
    /var/backups/visualspider/visualspider_2026-09-01.dump

# 3. 验证
psql -h localhost -U visualspider -d visualspider_restore -c '\dt'
```

### 2.2 切换到新库

1. 停应用（参考 §3.2）。
2. 改 `VISUALSPIDER_DATASOURCE_URL=jdbc:postgresql://localhost:5432/visualspider_restore`。
3. 启动应用；Flyway 检测到 `flyway_schema_history` 已存在，跳过全部历史 migration 直接进入待命。
4. 登录冒烟通过后，旧的 `visualspider` 库可保留作为再回退，或 `DROP DATABASE visualspider`。

### 2.3 覆盖原库（仅当原库已损坏且无路可选）

```bash
sudo -u postgres dropdb visualspider
sudo -u postgres createdb visualspider -O visualspider
pg_restore -h localhost -U visualspider -d visualspider \
    --no-owner --role=visualspider \
    /var/backups/visualspider/visualspider_2026-09-01.dump
```

## 3. 升级流程（正常路径）

### 3.1 预备

1. 读 [`release-notes-v0.1.0.md`](./release-notes-v0.1.0.md) 了解破坏性变更与新 migration 数量。
2. 确认升级前后两版本在 `documentation/configuration.md` §6 版本锁矩阵内的依赖一致（Java/Spring Boot/Playwright 版本无需变动）。

### 3.2 步骤

```bash
# ===== Linux（systemd 路径）=====
# 1. 备份（先于一切操作）
pg_dump -h localhost -U visualspider -d visualspider \
    -Fc -f /var/backups/visualspider/before-upgrade_$(date +%F_%H%M).dump
ls -lh /var/backups/visualspider/before-upgrade_*.dump

# 2. 停
sudo systemctl stop visual-spider

# 3. 换 JAR
sudo cp /tmp/visual-spider5-0.1.0.jar /opt/visual-spider/app.jar
sudo chown visualspider:visualspider /opt/visual-spider/app.jar

# 4. 启（Flyway 自动前滚）
sudo systemctl start visual-spider

# 5. 健康检查
curl -fsS http://localhost:8080/actuator/health
# 期望：{"status":"UP"}

# 6. 冒烟（admin 登录 + 创建采集人员 + 单页任务）
# 见 linux.md §11 / windows.md §10
```

Windows 路径（PowerShell）：

```powershell
# 1. 备份
$stamp = Get-Date -Format 'yyyy-MM-dd_HHmm'
& pg_dump -h localhost -U visualspider -d visualspider `
    -Fc -f "C:\backups\before-upgrade_$stamp.dump"

# 2. 停
.\stop.ps1

# 3. 换 JAR（覆盖前建议备份旧 JAR）
Copy-Item C:\visual-spider\app.jar C:\visual-spider\app.jar.bak.$stamp -Force
Copy-Item C:\tmp\visual-spider5-0.1.0.jar C:\visual-spider\app.jar -Force

# 4. 启
.\start.ps1

# 5. 健康检查
Invoke-WebRequest http://localhost:8080/actuator/health
```

### 3.3 Flyway 前滚观察

启动日志会输出类似：

```
Flyway Community Edition 10.20.1 by Redgate
Database: jdbc:postgresql://localhost:5432/visualspider
Successfully validated 3 migrations
Migrating schema "public" to version "4 - task v3 pagination and content"
Successfully applied 1 migration to schema "public"
```

期望：所有新增 migration `Successfully applied`；无 `Migration failed` / `ERROR` 关键字。出现 `ERROR` 立即进入 §4 失败回滚。

## 4. 失败回滚

### 4.1 Flyway 已应用 V<N> 但 V<N+1> 失败

最常见场景：V<N+1> SQL 有 bug（语法错、约束冲突、超时）。

**轻量回滚（仅当 V<N+1> 是新表 / 新增列的可空变更）**：

```sql
-- 手动回退 V<N+1>
DROP TABLE IF EXISTS <new_table>;
ALTER TABLE <existing_table> DROP COLUMN IF EXISTS <new_column>;
DELETE FROM flyway_schema_history WHERE version = '<N+1>';
```

然后改 V<N+1> SQL 文件后重新启动。

**严重回滚（V<N+1> 已写入数据或破坏 schema）**：进入 §4.2。

### 4.2 严重回滚——恢复备份库 + 回退旧 JAR

```bash
# 1. 停
sudo systemctl stop visual-spider

# 2. 回退 JAR
sudo cp /opt/visual-spider/app.jar.v0.0.1 /opt/visual-spider/app.jar

# 3. 选 2A 或 2B（推荐 2A）
# 2A. 恢复到新库 + 改 URL（应用层无感知）
sudo -u postgres dropdb visualspider_broken
sudo -u postgres createdb visualspider_broken -O visualspider
pg_restore -h localhost -U visualspider -d visualspider_broken \
    --no-owner --role=visualspider \
    /var/backups/visualspider/before-upgrade_2026-09-01_0200.dump
# 改 visual-spider.env: VISUALSPIDER_DATASOURCE_URL=jdbc:postgresql://localhost:5432/visualspider_broken
# 把 _broken 库重命名（确保无活动连接后）
sudo -u postgres psql -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='visualspider';"
sudo -u postgres psql -c "ALTER DATABASE visualspider RENAME TO visualspider_old;"
sudo -u postgres psql -c "ALTER DATABASE visualspider_broken RENAME TO visualspider;"

# 2B. 覆盖原库（破坏性，确认无人在用）
# 见 §2.3

# 4. 启动旧 JAR
sudo systemctl start visual-spider

# 5. 验证
curl -fsS http://localhost:8080/actuator/health
```

### 4.3 baseline-on-migrate 注意事项（历史库陷阱）

应用 `application.yml` 配置 `spring.flyway.baseline-on-migrate=true`：

- **空库（`flyway_schema_history` 不存在）**：Flyway 从 V1 开始全部应用。
- **已有同名表但无 `flyway_schema_history`**（典型场景：从旧版手动升上来，或测试残留）：Flyway 自动 baseline 当前 schema 状态为 V0，**跳过所有 ≤ baseline 的 migration**，不会回放 V1~V4 建表语句。如果目标版本已 ≥ 当前 baseline，跳过无害；如果目标版本 < baseline，会"飞过"。

**陷阱**：首启时若 `visualspider` 库已含前版本残留表（如 `spider_tasks`），且残留表与新 schema 同名（如 `collection_task`），Flyway 会 baseline 后直接尝试 V2 创建 `collection_task`，因表已存在而失败。

**解法**：

1. 升级前比对 schema：`pg_dump --schema-only -t <old_table> visualspider` 看是否与新 V1~V4 冲突。
2. 如有冲突：
   - 选项 A：导出旧库数据 → 丢弃旧库 → 重新创建 → 恢复数据（数据迁移路径见 §5）。
   - 选项 B：手写一次 baseline SQL（在 V1 之前插入一个 `V0__baseline.sql`，记录 baseline 状态），但这会污染 migration 历史，**首版不推荐**。

引用 memory 中 "baseline-on-migrate 旧表陷阱"：M0 阶段本项目已踩过（`visualspider` 库曾含前版 `spider_tasks`），解法是 `DROP 旧表 + flyway_schema_history` 后重启。

### 4.4 Flyway checksum 不匹配

如果换 JAR 后启动报 `Migration checksum mismatch for migration version X`：

- 原因：JAR 内的 V<X>.sql 与数据库 `flyway_schema_history.checksum` 不一致（有人改了历史 SQL 文件）。
- 修复：**永远不要**改已发布的 migration。改 V<X+1> 写补救脚本（`ALTER` / `UPDATE`），或在测试库 `flyway_schema_history` 删掉 V<X> 的行后 `repair`。

## 5. 数据迁移（旧库 → 新库 schema 不同）

如果旧版表结构与首版不兼容（最常见：字段重命名、新增 NOT NULL 列），且需要保留旧数据：

1. 升级前 `pg_dump --schema-only` 导出旧库表结构。
2. 在测试环境建 `visualspider_old` 库 + 导入旧数据。
3. 用 SQL 脚本把旧数据导入新 schema（`INSERT INTO collection_task SELECT ... FROM visualspider_old.spider_tasks ...`）。
4. 校验记录数：`SELECT COUNT(*)` 两边对比。
5. 切换 URL 指向新库；旧库保留 30 天后 `DROP`。

> **不在首版发布范围内**：首版发布前应已确认无旧数据需要迁移；本节仅为升级手册的兜底说明。

## 6. 演练记录（占位）

> 本节由用户在真实环境演练后回填。
> 演练起点：M6 收口构建（`b6c8685`）+ 其数据库。

### 6.1 成功路径（待用户填写）

```text
执行环境：Ubuntu 24.04 LTS / Windows 11 VM
起点版本：M6 收口构建 b6c8685
目标版本：M7-6 tag v0.1.0

[Step 1] pg_dump 备份
  命令：
  输出文件大小：
  备份耗时：

[Step 2] 停应用
  命令：
  验证 stopped：

[Step 3] 替换 JAR
  cp / cp 命令：
  旧 JAR 归档：

[Step 4] 启动
  启动命令：
  Flyway 日志关键行（grep -E "Successfully|Migrating|version"）：
  启动耗时：

[Step 5] 冒烟
  /actuator/health：
  admin 登录：
  单页采集运行 ID + 状态：

结论：升级演练成功 / 失败；如失败进入 §6.2 失败路径。
```

### 6.2 失败路径（待用户填写）

```text
执行环境：同上

[Step 1] 制造一次前滚失败
  方式：人为修改 V<N+1> SQL（语法错 / 重复键冲突 / 引外键失败）
  命令：

[Step 2] 启动观察
  Flyway 错误日志：
  应用启动失败退出码：

[Step 3] 走"恢复备份"路径
  备份文件：
  恢复命令：
  回退 JAR 命令：
  旧版本启动验证：

[Step 4] 数据完整性
  关键表记录数对比（升级前 / 失败后 / 恢复后）：
  登录测试：

结论：失败恢复演练成功；数据完整可登录。
```

## 7. 进一步参考

- [`configuration.md`](./configuration.md) §1 环境变量、§4 日志、§6 版本锁
- [`windows.md`](./windows.md) §13 升级
- [`linux.md`](./linux.md) §13 升级
- [Flyway baseline-on-migrate](https://documentation.red-gate.com/fd/baseline-184127470.html)
- [pg_dump / pg_restore 参考](https://www.postgresql.org/docs/16/app-pgdump.html)