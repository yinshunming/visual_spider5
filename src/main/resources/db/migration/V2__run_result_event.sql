-- M3: collection_run / run_result / run_event 三表 + 索引 + 级联（spec §D5 / ADR-0006）
-- 历史数据: 全新表，无历史数据迁移
-- 锁影响: 全新表 CREATE，不锁既有表；索引建立无数据
-- 回滚: 无（只前滚兼容；回滚需 drop 三表，会丢运行历史，故不提供回滚脚本，靠数据库备份恢复）

CREATE TABLE collection_run (
    id                BIGSERIAL PRIMARY KEY,
    task_id           BIGINT       NOT NULL REFERENCES collection_task(id),
    owner_id          BIGINT       NOT NULL REFERENCES app_user(id),
    snapshot          JSONB        NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN
                        ('WAITING','RUNNING','SUCCESS','PARTIAL_SUCCESS',
                         'FAILED','CANCELLED','INTERRUPTED')),
    stop_reason       VARCHAR(32),
    cancel_requested  BOOLEAN      NOT NULL DEFAULT false,
    page_count        INT          NOT NULL DEFAULT 0,
    record_count_raw      INT      NOT NULL DEFAULT 0,
    record_count_dedup    INT      NOT NULL DEFAULT 0,
    record_count_final    INT      NOT NULL DEFAULT 0,
    fail_count        INT          NOT NULL DEFAULT 0,
    current_url       TEXT,
    stage             VARCHAR(32),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE collection_run IS '采集运行权威状态; snapshot 不可修改; status 7 态 (M3 产 6 态, PARTIAL_SUCCESS 留位 M4)';
COMMENT ON COLUMN collection_run.stop_reason IS '停止原因; WAITING/RUNNING 阶段为 NULL';

CREATE INDEX idx_collection_run_owner_status_created
    ON collection_run (owner_id, status, created_at DESC);
CREATE INDEX idx_collection_run_finished
    ON collection_run (finished_at)
    WHERE finished_at IS NOT NULL;  -- 保留清理用

CREATE TABLE run_result (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT       NOT NULL REFERENCES collection_run(id) ON DELETE CASCADE,
    sequence_no     INT          NOT NULL,
    unique_key_hash BYTEA,       -- M3 恒 NULL; M4 去重启用
    data            JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (run_id, sequence_no)
);
COMMENT ON TABLE run_result IS '结果行; 按 (run_id, sequence_no) 分页/游标; run 删除级联';

CREATE INDEX idx_run_result_run_seq ON run_result (run_id, sequence_no);

CREATE TABLE run_event (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT       NOT NULL REFERENCES collection_run(id) ON DELETE CASCADE,
    level        VARCHAR(16)  NOT NULL CHECK (level IN ('INFO','WARN','ERROR')),
    stage        VARCHAR(32),
    url          TEXT,
    error_code   VARCHAR(64),
    message      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE run_event IS '用户可见结构化事件; 不复制完整技术堆栈';

CREATE INDEX idx_run_event_run_created ON run_event (run_id, created_at);