-- M5: schemaVersion bump to 3 + content_fail_count 列 + 按目标 URL 查询索引（spec §D3）
-- 历史数据: 已有 collection_task.definition (schemaVersion=2) 由启动 hook TaskSchemaUpgrader 升级到 V3
--   （仅 bump schemaVersion；paginationRule 缺省视为 null，等价"只跑当前页"，与 V2 LIST 任务行为一致）
--   collection_run 历史行 content_fail_count 默认 0（V2 时期无内容页概念，语义等价）
-- 锁影响: ADD COLUMN ... NOT NULL DEFAULT 是 PG 11+ 轻量操作（不重写表）；
--   索引沿用 V3 迁移先例（CREATE INDEX IF NOT EXISTS，非 CONCURRENTLY，建索引期间持 SHARE 锁，
--   首版私有部署小容量、停机窗口可接受）
-- 回滚: ALTER TABLE collection_run DROP COLUMN content_fail_count; DROP INDEX idx_collection_run_url;
--   启动 upgrader 是 idempotent（V3 任务 no-op）

ALTER TABLE collection_run
    ADD COLUMN content_fail_count INTEGER NOT NULL DEFAULT 0;
COMMENT ON COLUMN collection_run.content_fail_count IS
    'M5: 内容页 navigate 失败(retry 耗尽)的记录数; 与 fail_count(sink 行级失败)语义互不重叠';

-- 多页任务按目标 URL 查历史 run 时加速（spec §D3 "idx_collection_run_url"）。
-- collection_run 无 start_url 物理列（起始 URL 在 snapshot JSONB），用表达式索引等价落地。
CREATE INDEX IF NOT EXISTS idx_collection_run_url
    ON collection_run ((snapshot->>'startUrl'), created_at DESC);
COMMENT ON INDEX idx_collection_run_url IS
    'M5: 按任务起始 URL 查询历史运行（snapshot->>startUrl 表达式索引；start_url 非物理列）';
