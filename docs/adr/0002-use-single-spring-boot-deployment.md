# 使用单个 Spring Boot 服务部署应用

首版使用 Java、Spring Boot、Vue 3 和 Playwright for Java：Vue 构建产物由 Spring Boot 提供，Playwright 通过独立 Chromium 子进程执行页面交互与采集，整套应用打包为一个可直接启动的 JAR，不依赖 Docker 或独立 Worker。该选择优先满足维护者熟悉 Java 且希望简化部署的约束，并接受 API 与采集执行共享 JVM 所带来的资源隔离限制；代码内部仍保持浏览器执行模块边界，以便容量需求增长后再拆分进程。
