/**
 * task 模块。
 *
 * <p>本包承载 task 业务能力：采集任务定义、任务 JSON schemaVersion、
 * 草稿状态、乐观锁、所有权隔离与校验。
 *
 * <p>M1/M2 启用（M1 落地草稿 CRUD，M2 接入字段与预览校验）；M0.5 仅完成包结构占位。
 *
 * <p>本包遵守 ADR-0003（按业务模块组织深模块）：
 * 仅通过稳定 interface 对外暴露能力；隐藏 PostgreSQL Flyway migration、
 * 任务 JSON 持久化细节与 schemaVersion 校验实现。
 */
package com.visualspider.task;