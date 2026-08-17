-- M1 baseline：app_user / collection_task / system_setting 三表 + 索引
-- 唯一允许 DDL 的迁移；INSERT 由 Spring Boot ApplicationRunner 完成（见 identity.SeedAdminInitializer）

CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL UNIQUE,
    password_hash   VARCHAR(72)  NOT NULL,
    role            VARCHAR(16)  NOT NULL CHECK (role IN ('ADMIN', 'COLLECTOR')),
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE  app_user IS '登录账号；唯一 username；BCrypt cost=12 哈希密码';
COMMENT ON COLUMN app_user.role   IS 'ADMIN 全局可见；COLLECTOR 仅访问自己资源';
COMMENT ON COLUMN app_user.status IS 'ACTIVE 可登录；DISABLED 拒绝（不回显避免用户名枚举）';

CREATE INDEX idx_app_user_status_created ON app_user (status, created_at);

CREATE TABLE collection_task (
    id              BIGSERIAL PRIMARY KEY,
    owner_id        BIGINT       NOT NULL REFERENCES app_user(id),
    name            VARCHAR(200) NOT NULL,
    mode            VARCHAR(16)  NOT NULL CHECK (mode IN ('SINGLE_PAGE', 'LIST')),
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('DRAFT', 'READY')),
    schema_version  INT          NOT NULL,
    definition      JSONB        NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE  collection_task IS '采集任务草稿；definition JSONB 由 Jackson 序列化 TaskDefinition；乐观锁通过 version 列实现';
COMMENT ON COLUMN collection_task.mode IS 'SINGLE_PAGE 单页；LIST M4 启用（M1 仅可创建 status=DRAFT）';
COMMENT ON COLUMN collection_task.status IS 'M1 仅使用 DRAFT；READY M2 校验通过后设置';

CREATE INDEX idx_collection_task_owner_updated ON collection_task (owner_id, updated_at DESC);

CREATE TABLE system_setting (
    key             VARCHAR(64) PRIMARY KEY,
    value           TEXT        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE system_setting IS '系统级配置键值表；M1 仅写入 seed.admin.username 一行';
