# 单体内按业务能力组织深模块

Spring Boot 应用按 `identity`、`task`、`visualbrowser`、`extraction`、`run` 和 `result` 等业务能力组织代码，而不是使用全局 `controller/service/repository` 三层目录。每个模块通过少量稳定 interface 隐藏数据库、Playwright 和执行状态等实现复杂度，使调用方与测试共享同一 seam，同时保持单 JAR 部署，不引入微服务边界。
