-- M4: schemaVersion bump to 2 + list-mode 索引 + run_result 去重局部唯一索引（spec §D2）
-- 历史数据: 启动 hook TaskSchemaUpgrader 静默补全字段；本 migration 只建索引
-- 锁影响: CREATE INDEX CONCURRENTLY 等价做法（先 IF NOT EXISTS），已有 collection_task / run_result 不锁业务表
-- 回滚: drop 新索引；不回滚 schema（V4+ 必须保留 V2）

CREATE INDEX IF NOT EXISTS idx_task_list_mode_status
    ON collection_task (mode, status, updated_at DESC);

COMMENT ON INDEX idx_task_list_mode_status IS 'M4: 任务按 mode/status 查询加速（list mode 任务列表路径）';

CREATE UNIQUE INDEX IF NOT EXISTS uq_run_result_dedup_key
    ON run_result (run_id, unique_key_hash)
    WHERE unique_key_hash IS NOT NULL;

COMMENT ON INDEX uq_run_result_dedup_key IS 'M4: 运行内去重局部唯一索引；NULL 行不参与唯一性（兼容单页 M3 任务）';
