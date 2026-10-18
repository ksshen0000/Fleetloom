# RCS3000 技术评估

> 版本：v0.3（草稿） · 日期：2026-10-18
> 对应需求：[requirements.md](requirements.md) v0.2
> 说明：每一项先列候选方案和评估，再给推荐结论。结论汇总在第 1 节，作为后续实现的技术基线；推翻某项结论时，在对应小节写明原因并更新第 1 节。

---

## 0. 硬约束：纯开源、非盈利交付

RCS3000 本身作为**开源项目**发布，非盈利；客户（多为企业）自行下载或由我们协助部署。因此：

1. **平台代码以 Apache-2.0 开源**（理由见 0.1）。
2. **所有运行时组件和依赖必须是 OSI 认可的开源许可，且对下游的商业使用者也免费**。我们非盈利，但使用者（工厂、仓库）是商业主体，所以「非商用免费」「非营利组织免费」的授权不算数。
3. **不允许 source-available 许可**：BSL、FSL、SSPL、RSAL、Elastic License、Commons Clause 等。
4. **链接进平台代码的库只用宽松许可或弱 copyleft**，保证下游可以按 Apache-2.0 自由使用、二次开发；强 copyleft（GPL/AGPL）组件只能作为独立进程、不修改源码使用。
5. 依赖许可在 CI 中自动检查（第 12 节），新增依赖不符合规则则构建失败。

### 0.1 项目许可选择

| 候选 | 特点 | 适合 |
| --- | --- | --- |
| **Apache-2.0** | 宽松；含明确的专利授权与专利报复条款；企业法务最熟悉、接受度最高 | 希望尽可能多的企业采用、二次开发 |
| MIT | 最简单宽松；无专利条款 | 小型库；对工业软件而言专利保护偏弱 |
| AGPL-3.0 | 强 copyleft，修改后对外提供服务也必须开源 | 希望阻止闭源商业分支；但会显著降低企业采用意愿 |
| GPL-3.0 | 分发时必须开源 | 同上，对 SaaS 改造无约束 |

结论：**Apache-2.0**。目标是让更多工厂和集成商用起来；与第 1 节所有依赖兼容。若将来担心被闭源商用分支「拿走不还」，再评估改为 AGPL-3.0（需要届时所有贡献者同意，因此从第一天起要求贡献者签署 DCO，见第 16 节）。

---

## 1. 结论汇总（技术基线）

| 领域 | 结论 | 许可 | 备选 / 后路 |
| --- | --- | --- | --- |
| 后端语言 | **Java 25（LTS），Eclipse Temurin 发行版** | GPLv2 + Classpath Exception | Java 21 LTS |
| 后端框架 | **Spring Boot 4.1.x + Spring Cloud 2025.1.x** | Apache-2.0 | — |
| 架构风格 | **按领域拆分的微服务（5 个业务服务 + 网关）**，代码层强制模块边界 | — | 合并部署（见 3.3） |
| 服务间同步调用 | **Spring HTTP Interface（`@HttpExchange`）** | Apache-2.0 | — |
| 服务间异步事件 | **Valkey Streams**（封装在 `EventBus` 接口后） | BSD-3-Clause | Apache Kafka（Apache-2.0） |
| 关系数据库 | **PostgreSQL 18** | PostgreSQL License | — |
| 数据访问 | **MyBatis-Plus**（需先验证对 Boot 4 的支持） | Apache-2.0 | Spring Data JDBC（Apache-2.0） |
| 表结构迁移 | **Flyway（仅用 `flyway-core` Maven 库）** | Apache-2.0（需核对 jar 内 LICENSE） | MyBatis Migrations（Apache-2.0） |
| 缓存 / 实时状态 | **Valkey** + Spring Data Redis（Lettuce 客户端） | BSD-3-Clause / Apache-2.0 / MIT | — |
| 车辆通信 | **MQTT v5 + VDA 5050 v2.x** | 规范本身 MIT | 私有协议走适配器 |
| MQTT Broker | **Eclipse Mosquitto 2.x**（单节点） | EPL-2.0 / EDL-1.0（BSD-3） | 需要集群时：Apache BifroMQ |
| MQTT 客户端 | **HiveMQ MQTT Client** | Apache-2.0 | Eclipse Paho（EPL-2.0 / EDL-1.0） |
| 认证授权 | **Spring Security + JWT（Nimbus JOSE）** | Apache-2.0 | Keycloak（Apache-2.0） |
| 网关 | **Spring Cloud Gateway（WebFlux 版）** | Apache-2.0 | — |
| 前端实时推送 | **网关推送模块 + WebSocket（JSON 消息）** | — | — |
| 服务发现 / 配置中心 | **v1 不引入** | — | Nacos（Apache-2.0） |
| 路径规划算法 | **自研**；JGraphT 仅在测试中作对照 | 自有 / EPL-2.0 或 LGPL-2.1（仅 test 作用域） | — |
| 模拟器语言 | **Java** | — | — |
| 前端 | **Vue 3 + TypeScript + Vite + Pinia + Element Plus** | MIT | Naive UI（MIT） |
| 地图渲染 | **Konva（vue-konva）** | MIT | PixiJS（MIT） |
| 构建 | **Maven 多模块**；前端 pnpm | Apache-2.0 / MIT | — |
| 容器运行时 | **Docker Engine + Docker Compose（Linux）** | Apache-2.0 | Podman（Apache-2.0） |
| 指标 | **Micrometer + Prometheus**（交付物内） | Apache-2.0 | — |
| 看板 | **Grafana 作为可选组件**（Compose 的 `observability` profile），不修改源码；平台控制台自带运维页 | AGPLv3（独立进程，未修改） | Perses（Apache-2.0） |
| 测试 | **JUnit Jupiter + AssertJ + Testcontainers + ArchUnit** | EPL-2.0 / Apache-2.0 / MIT | — |

**明确不用（许可原因）**：EMQX 5.9+（BSL）、Liquibase 5.0+（FSL）、Redisson PRO、TimescaleDB 的 TSL 功能、Oracle JDK、Docker Desktop（大企业需订阅）。Redis 8 虽可选 AGPLv3，但 Valkey 已满足需求且许可更宽松，仍用 Valkey。

---

## 2. 评估维度

1. **许可合规**（第 0 节硬约束，一票否决）。
2. **实时性与正确性**：交通管制直接关系到车辆是否碰撞，延迟与一致性优先。
3. **团队上手成本**：组件越少、越主流越好。
4. **生命周期**：项目周期内有免费的安全更新，不依赖商业支持。
5. **可替换性**：关键基础设施藏在接口后面，换掉不影响业务代码。

---

## 3. 架构风格

### 3.1 候选

| 方案 | 说明 | 优点 | 缺点 |
| --- | --- | --- | --- |
| A. 微服务 | 网关 + resource / dispatch / traffic / fleet / simulator 独立部署 | 边界清晰、可独立扩展和发布、契合「模块化」诉求 | 运维组件多；跨服务事务要靠补偿；调试链路长 |
| B. 模块化单体 | 一个进程，内部按领域分 Maven 模块，模块间只通过接口通信 | 部署简单、调试方便、无网络开销 | 一个模块崩溃拖垮全部；后期拆分需要改造通信方式 |
| C. 混合 | 业务服务独立，但 traffic + fleet 合为一个「运动控制」进程 | 实时环路无网络跳 | 违背「机器人控制」和「路径规划」分开的诉求 |

### 3.2 结论：方案 A

需求明确要求基础管理、机器人控制、路径规划分属不同服务，且规模（50 台车、1 Hz 状态）下网络开销不构成瓶颈：车辆状态 → fleet → 事件 → traffic → fleet → 车辆，一次环路在局域网内是毫秒级，而车辆上报间隔是 1000 ms。

| 风险 | 对策 |
| --- | --- |
| 模块边界在代码里被悄悄打破 | 服务间只依赖 `rcs-contracts`；ArchUnit 测试禁止跨服务包引用、禁止 `domain` 层依赖 Spring |
| traffic 有状态、只能单实例 | 明确 traffic 为单实例服务；预约表周期快照到 Valkey；重启时由车辆当前位置重建；重启窗口内车辆停在已持有资源末端（安全侧失效） |
| 跨服务一致性（锁库位 + 派车 + 更新载具） | dispatch 作为订单的唯一协调者，每一步落库后再进行下一步；失败时按订单补偿（释放锁、取消路由） |
| 事件丢失或重复 | Streams 消费者组 + ACK；消费者按 `eventId` 幂等 |
| 本地开发要起很多进程 | `docker compose` 一键起基础设施；IDE 中用 Compound 运行配置同时启动全部服务 |

### 3.3 保留后路

每个服务的 `domain` + `application` 层不依赖传输方式（HTTP / 事件都在 `infrastructure` 层实现），将来如需给小客户做单机版，可把多个服务打成一个 Spring Boot 应用，用进程内实现替换 HTTP 客户端和 EventBus。v1 不实现，只保证不堵死。

---

## 4. 后端语言与框架

### 4.1 Java 版本与发行版

| 候选 | 评估 |
| --- | --- |
| Java 21 LTS | 成熟稳定；虚拟线程可用，但 `synchronized` 块会钉住载体线程（pinning） |
| **Java 25 LTS** | 2025-09 发布的最新 LTS；JDK 24 起解决了 `synchronized` 钉住问题（JEP 491）；Spring Boot 4 一等支持 |

发行版：**Eclipse Temurin**（GPLv2 + Classpath Exception，免费商用、可随产品分发）。**不用 Oracle JDK**：其许可对商业生产使用有限制，交付客户时存在合规风险。

结论：**Java 25 + Temurin**。

### 4.2 框架

| 候选 | 评估 |
| --- | --- |
| **Spring Boot 4.x** | 生态最全；Apache-2.0；4.0 于 2025-11 GA，基于 Spring Framework 7 |
| Spring Boot 3.5 | **已于 2026-06-30 结束开源支持**，之后的补丁只有商业支持，新项目不采用 |
| Quarkus / Micronaut | 启动快、内存小；生态与资料少，本项目收益不明显 |

结论：**Spring Boot 4.1.x**（开源支持至 2027-07）+ Spring Cloud 2025.1.x。

> **交付维护约束**：Spring 每个小版本的免费支持期约 1 年，过期后的安全补丁只有商业订阅才有。为保持「纯免费」，产品需要**每年至少升级一次 Spring Boot 小版本**，并把它写进发布计划，交付给客户的版本也要跟随升级。

### 4.3 服务间同步调用

Spring Framework 自带声明式 HTTP 客户端（`@HttpExchange` + `RestClient`），Boot 4 提供自动配置；OpenFeign 已进入维护模式。结论：**`@HttpExchange`**，接口定义放在 `rcs-contracts`。

### 4.4 并发模型

- Web 层和 IO 密集任务：开启虚拟线程。
- **traffic 核心采用单线程事件循环**：所有改变预约表的操作进入一个队列顺序处理，预约表不加锁、可确定性重放、易测试。
- dispatch 的调度循环同样单线程，避免两台车被分配到同一订单。

---

## 5. 数据存储

### 5.1 关系数据库

| 候选 | 许可 | 评估 |
| --- | --- | --- |
| **PostgreSQL 18** | PostgreSQL License（类 BSD） | JSONB、原生分区表、`timestamptz`；无任何商业版限制 |
| MySQL 8.4 | GPLv2（社区版）；Oracle 另有商业版 | 作为独立进程使用不影响平台代码，但 JDBC 驱动 Connector/J 为 GPLv2 + FOSS 例外，闭源产品链接它有争议 |
| MariaDB | GPLv2；驱动 LGPL-2.1 | 比 MySQL 干净，但无明显优势 |
| TimescaleDB | 部分功能为 Timescale License（非开源） | 不用 |

结论：**PostgreSQL 18**，JDBC 驱动 pgJDBC 为 BSD-2-Clause。车辆轨迹用 PG 原生分区表按天分区。一个 PG 实例，**每个服务一个 schema**（`resource`、`dispatch`、`fleet`），各服务数据库账号只能访问自己的 schema。

高可用（交付大客户时）：Patroni（MIT）+ 流复制，均为开源。

### 5.2 数据访问与迁移

| 候选 | 许可 | 评估 |
| --- | --- | --- |
| **MyBatis-Plus** | Apache-2.0 | 国内最常用；对 Boot 4 的支持需 M0 验证 |
| Spring Data JDBC | Apache-2.0 | 简单、无懒加载；MyBatis-Plus 不可用时的后路 |
| Spring Data JPA | Apache-2.0 / Hibernate 为 Apache-2.0 | 隐式行为多，团队需深厚经验 |
| jOOQ | 开源版 Apache-2.0，仅支持开源数据库 | 可用，但商业数据库支持收费，避免将来被动 |

迁移工具：

| 候选 | 许可 | 评估 |
| --- | --- | --- |
| **Flyway（`flyway-core` + `flyway-database-postgresql`）** | 源码头为 Apache-2.0；Redgate 的 CLI / 桌面版走其 EULA | **只用 Maven 库、随应用启动执行迁移**，不使用 Redgate 发行的 CLI 和 Teams/Enterprise 功能；M0 时核对 jar 内 LICENSE |
| Liquibase 5.0+ | FSL（非开源） | **不用** |
| MyBatis Migrations | Apache-2.0 | Flyway 许可核对不过时的后路 |

结论：**MyBatis-Plus + Flyway（仅库）**。禁止 `ddl-auto`。

### 5.3 缓存与实时状态

| 候选 | 许可 | 评估 |
| --- | --- | --- |
| **Valkey** | BSD-3-Clause | Linux 基金会项目，Redis 协议兼容，治理上单一厂商无法改许可 |
| Redis 7.4+ / 8 | RSALv2 / SSPLv1 / AGPLv3 | 不满足第 0 节，**不用** |

客户端：Spring Data Redis（Apache-2.0）+ Lettuce（MIT）。**不用 Redisson**（开源版可用，但高级功能在 PRO 收费版，容易误用）。

结论：**Valkey**。

---

## 6. 消息与通信

### 6.1 服务间事件总线

| 候选 | 许可 | 评估 |
| --- | --- | --- |
| **Valkey Streams** | BSD-3-Clause | 复用已有组件；消费者组、ACK、重放；规模足够 |
| Apache Kafka | Apache-2.0 | 最强，但运维重，v1 过度设计；作为后路 |
| RabbitMQ | MPL-2.0 | 开源，但多一个组件 |
| NATS JetStream | Apache-2.0 | 轻量；团队熟悉度低 |

结论：**Valkey Streams**，业务代码只依赖 `rcs-common` 的 `EventBus` 接口。

### 6.2 MQTT Broker

| 候选 | 许可 | 集群 | 评估 |
| --- | --- | --- | --- |
| **Eclipse Mosquitto 2.x** | EPL-2.0 / EDL-1.0（选 EDL，即 BSD-3） | 否 | 最轻量、最稳定；单节点支撑数百台车毫无压力；v1 采用 |
| Apache BifroMQ | Apache-2.0（ASF 孵化项目） | 是（StandardCluster） | Java 实现、原生集群、内置存储；**客户要求 Broker 高可用时的升级路径**；注意其 Independent Workload Cluster 模式依赖非开源组件，不使用该模式 |
| HiveMQ CE | Apache-2.0 | 否（集群在商业版） | 无优势 |
| NanoMQ | MIT | 否 | 偏边缘场景 |
| VerneMQ | 源码 Apache-2.0；官方预编译包商用需订阅 | 是 | 需自行从源码构建，维护成本高，不用 |
| EMQX 5.9+ | BSL 1.1 | 是（需商业许可） | **不用** |
| EMQX 5.8 及以前 | Apache-2.0 | 是 | 已停止更新，不用 |

结论：**Mosquitto**；平台只使用标准 MQTT v5 能力，换 BifroMQ 不改代码。

MQTT 客户端：**HiveMQ MQTT Client**（Apache-2.0），封装在 `rcs-protocol-vda5050` 中，fleet 与 simulator 共用。

### 6.3 前端实时推送

| 候选 | 评估 |
| --- | --- |
| **网关推送模块 + 原生 WebSocket** | 网关订阅面向前端的事件，按订阅主题推送 JSON；鉴权与 REST 共用 JWT |
| STOMP over WebSocket | Spring 的 STOMP 支持基于 Servlet 栈，与 WebFlux 网关不匹配 |
| 浏览器直连 MQTT | Broker 需对浏览器暴露并做细粒度 ACL，内外网消息混用 |
| SSE | 浏览器同域连接数受限 |

结论：**网关推送模块 + 原生 WebSocket**。消息格式 `{ topic, type, data, ts }`，前端发送 `{ action: "subscribe", topics: [...] }`。车辆位置类消息在网关按 5 Hz 限频合并。

### 6.4 服务发现与配置

v1 用 Docker Compose 服务名 + `application.yml` + 环境变量。需要动态伸缩时引入 Nacos（Apache-2.0）。

---

## 7. 安全

| 候选 | 许可 | 评估 |
| --- | --- | --- |
| **Spring Security + JWT** | Apache-2.0 | 网关作为 Resource Server 校验 JWT，下游信任网关传入的用户头 |
| Sa-Token | Apache-2.0 | 与 Spring Cloud Gateway 结合需额外适配 |
| Keycloak | Apache-2.0 | 客户要求 SSO / LDAP 时引入 |

结论：**Spring Security + JWT**（JWT 库 Nimbus JOSE + JWT，Apache-2.0）。用户、角色、权限在 `rcs-resource/system`，resource 签发 JWT，网关统一校验，接口权限用 `@PreAuthorize`；上游系统用 API Key。

---

## 8. 算法实现

| 候选 | 评估 |
| --- | --- |
| **自研图结构 + A* + 预约表** | 需与预约、动态代价、车型过滤、朝向约束深度结合；核心代码量不大，完全自有、无许可问题 |
| JGraphT | EPL-2.0 / LGPL-2.1 双许可；**只在 test 作用域**作为「标准答案」对照，不进交付物 |
| openTCS | MIT；仅作设计参考 |

结论：**自研**，放在 `rcs-traffic/core`，纯 Java、无 Spring 依赖。

---

## 9. 模拟器

| 候选 | 评估 |
| --- | --- |
| **Java（Spring Boot）** | 复用 `rcs-protocol-vda5050`，协议实现只有一份 |
| Python | 要再维护一份 VDA 5050 模型，易与平台不一致 |
| 现成开源 VDA 5050 模拟器 | 用于协议兼容性交叉测试，不进交付物 |

结论：**Java**，单线程固定步长（默认 50 ms）推进所有虚拟车，保证可重复。模拟器随产品交付，供客户现场调试和培训使用。

---

## 10. 前端

### 10.1 框架与组件库

| 项 | 结论 | 许可 |
| --- | --- | --- |
| 框架 | Vue 3 + TypeScript + Vite | MIT / Apache-2.0 / MIT |
| 状态管理 | Pinia | MIT |
| 路由 | Vue Router | MIT |
| 组件库 | Element Plus（含 @element-plus/icons-vue） | MIT |
| HTTP | axios | MIT |
| 类型生成 | openapi-typescript | MIT |
| 包管理 | pnpm | MIT |

字体与图标只用开源许可资源（如系统字体、思源黑体 OFL-1.1），不引入商业字体和付费图标库。

### 10.2 地图渲染

| 候选 | 许可 | 评估 |
| --- | --- | --- |
| **Konva（vue-konva）** | MIT | 内置拖拽、变换、命中检测、分层；最适合编辑器 |
| PixiJS | MIT | WebGL，性能最强；交互需自己实现 |
| Leaflet | BSD-2-Clause | 适合超大瓦片背景图；编辑交互弱 |
| Fabric.js | MIT | 大量对象时性能一般 |

结论：**Konva**，编辑器与监控页共用图层组件；M1 性能不达标时监控页车辆层改用 PixiJS。

---

## 11. 工程与运维

| 项 | 结论 |
| --- | --- |
| 构建 | Maven 多模块 + Maven Wrapper；父 POM 统一版本 |
| 代码规范 | Spotless（google-java-format）、ESLint + Prettier；ArchUnit 守住边界 |
| 对象映射 | MapStruct（Apache-2.0） |
| API 文档 | springdoc-openapi（Apache-2.0），网关聚合 |
| 测试 | JUnit Jupiter、AssertJ、Testcontainers（PG、Valkey、Mosquitto）、模拟器场景脚本 |
| 日志 | Logback JSON 输出（EPL-1.0 / LGPL-2.1 双许可，Spring Boot 默认，作为未修改的库使用）；统一 `traceId`；交付物内不含日志平台，客户可接入自有 ELK/Loki |
| 指标 | Micrometer + Prometheus（Apache-2.0）进交付物；平台控制台内置「系统运维」页展示关键指标（车辆在线数、订单吞吐、规划耗时、事件积压），不依赖 Grafana |
| 看板 | Grafana（AGPLv3）放在 Compose 的可选 `observability` profile 中，使用官方镜像、不修改源码；项目提供预置 Dashboard JSON（Apache-2.0）。不想引入 AGPL 组件的使用者可只用平台运维页或改用 Perses（Apache-2.0） |
| 部署 | Docker 镜像（基础镜像 `eclipse-temurin:25-jre`）；`deploy/docker-compose.yml` 一键启动；客户环境用 Linux 上的 Docker Engine 或 Podman，**不依赖 Docker Desktop** |
| 离线交付 | 提供离线安装包：全部镜像 `docker save` 打包 + 安装脚本，客户现场无需外网 |
| CI | 单元测试 + ArchUnit + 许可检查 + 前端 lint/类型检查；合并前跑集成测试 |
| Node.js | 当前 LTS（24.x），仅构建期使用，不进交付物 |

---

## 12. 许可合规流程

### 12.1 白名单 / 灰名单 / 黑名单

| 类别 | 许可 | 规则 |
| --- | --- | --- |
| 白名单（直接可用） | Apache-2.0、MIT、BSD-2/3-Clause、ISC、PostgreSQL、EDL-1.0、EPL-2.0、MPL-2.0、LGPL-2.1/3.0、EPL-1.0、GPLv2 + Classpath Exception、Unicode、OFL-1.1（字体）、CC0 | 可作为依赖链接进平台代码 |
| 灰名单（仅限独立进程） | GPL-2.0/3.0、AGPL-3.0 | 只能作为独立运行的组件（如 Grafana），使用官方发行版、不修改源码，不得作为库链接进平台代码；在 `docs/licenses.md` 登记 |
| 黑名单（禁止） | SSPL、BSL、FSL、RSAL、Elastic License、Commons Clause、任何「非商用」「非营利限定」或「需付费商用」条款 | CI 构建失败 |

### 12.2 工具

| 工具 | 用途 |
| --- | --- |
| CycloneDX Maven 插件 + `@cyclonedx/cyclonedx-npm` | 每次构建生成 SBOM（软件物料清单），随交付物提供给客户 |
| license-maven-plugin（MojoHaus） | 检查 Java 依赖许可，命中黑名单则失败 |
| license-checker（npm） | 检查前端依赖许可 |
| 镜像清单 | 交付镜像（PG、Valkey、Mosquitto、Temurin 基础镜像）在 `docs/licenses.md` 中逐个登记版本与许可 |

### 12.3 发布物要求

- 源码仓库根目录放 `LICENSE`（Apache-2.0 全文）和 `NOTICE`。
- 发布包与镜像内附 `THIRD-PARTY-NOTICES`（第三方许可声明，由 SBOM 自动生成），SBOM 随每个 Release 发布。
- EPL-2.0、MPL-2.0 等文件级 copyleft 组件：不修改其源码；若必须修改，修改部分按其许可公开。
- 每次升级基础设施镜像（尤其 Valkey、Mosquitto、PostgreSQL、Flyway）前复核许可，防止上游再次改许可（Redis、EMQX、Liquibase 都发生过）。

---

## 13. 资源估算（单机部署，50 台模拟车）

| 组件 | 内存（约） |
| --- | --- |
| gateway / resource / dispatch / traffic / fleet / simulator | 6 × 512 MB ~ 1 GB |
| PostgreSQL | 1 GB |
| Valkey | 256 MB |
| Mosquitto | < 50 MB |
| Prometheus | 512 MB |
| **合计** | **约 5 ~ 8 GB**，满足 NFR-08（8C16G 单机） |

经验估算，M5 压测时实测修正。

---

## 14. M0 阶段需验证的事项

- [ ] Spring Boot 4.1 + Spring Cloud 2025.1 + Spring Cloud Gateway 版本组合可正常启动
- [ ] MyBatis-Plus 在 Boot 4 / Spring 7 下可用（不行则切 Spring Data JDBC）
- [ ] `flyway-core` 与 `flyway-database-postgresql` jar 内许可为 Apache-2.0（不是则切 MyBatis Migrations）
- [ ] HiveMQ MQTT Client 与 Mosquitto 2.x 的 MQTT 5 收发、遗嘱消息、重连
- [ ] 网关 WebSocket 推送：JWT 鉴权 + 订阅 + 5 Hz 限频
- [ ] Konva 渲染 2000 点 + 3000 路径 + 50 车的帧率
- [ ] Valkey Streams 消费者组在服务重启后的未 ACK 消息重投
- [ ] CI 中许可检查与 SBOM 生成跑通，故意引入一个 AGPL 依赖确认构建失败

---

## 15. 待决问题

- [ ] 确认项目许可为 Apache-2.0（0.1 节），还是希望用 AGPL-3.0 防止闭源分支？
- [ ] 代码托管在 GitHub 还是 Gitee（或双向镜像）？
- [ ] 是否有现成的统一认证（LDAP / SSO）需要对接？
- [ ] 客户现场部署是单机 Docker 还是 K8s？是否有离线（无外网）环境？
- [ ] 客户是否要求 MQTT Broker、数据库高可用（决定是否在 v1 就验证 BifroMQ 集群和 Patroni）？

---

## 16. 开源项目治理

| 项 | 做法 |
| --- | --- |
| 仓库文件 | `README`（中英文）、`LICENSE`、`NOTICE`、`CONTRIBUTING.md`、`CODE_OF_CONDUCT.md`、`SECURITY.md`（漏洞报告方式） |
| 贡献者协议 | **DCO**（提交时 `git commit -s` 签名声明），不用 CLA，门槛低；CI 检查每个提交都有 `Signed-off-by` |
| 版本 | 语义化版本（SemVer）；`main` 为开发分支，按版本打 tag，Release 附离线安装包、SBOM、变更日志 |
| 演示环境 | `docker compose up` 即可得到「平台 + 模拟器 + 示例地图 + 示例订单」的完整演示，降低试用门槛 |
| 文档 | `docs/` 下的需求、设计、接口文档随代码一起开源；部署和二次开发文档优先 |
| 扩展点 | 车辆适配器（`VehicleAdapter`）、路径规划器（`PathPlanner`）、选车策略（`VehicleSelector`）作为公开 SPI，便于社区贡献新车型协议和算法 |
| 商标 | 项目名「RCS3000」使用前检索是否与已有商标冲突 |

---

## 参考资料

- [EMQX Licensing FAQ](https://www.emqx.com/en/content/license-faq)
- [EMQX 采用 BSL 许可的公告](https://www.emqx.com/en/blog/adopting-business-source-license-to-accelerate-mqtt-and-ai-innovation)
- [Liquibase Community 改用 FSL 的公告](https://www.liquibase.com/blog/liquibase-community-for-the-future-fsl)
- [Liquibase 5.0 发布说明](https://docs.liquibase.com/community/release-notes/5-0)
- [Flyway Community 许可讨论（Redgate 论坛）](https://forum.red-gate.com/roletracker/jump/90503)
- [Apache BifroMQ 集群文档](https://bifromq.apache.org/docs/cluster/intro/)
- [Apache BifroMQ 介绍](https://bifromq.apache.org/blog/introducing-apache-bifromq/)
- [Spring Boot 3.5.16 发布（3.5 最后一个开源版本）](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)
- [Spring Boot 版本 EOL 时间表（HeroDevs）](https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026)
- [Redis 增加 AGPL 许可（InfoQ）](https://www.infoq.com/news/2025/05/redis-agpl-license)
- [Valkey 与 Redis 许可对比](https://blog.elest.io/valkey-vs-redis-vs-keydb-which-in-memory-store-after-the-license-change/)

---

## 变更记录

| 版本 | 日期 | 修改人 | 内容 |
| --- | --- | --- | --- |
| v0.1 | 2026-10-18 | | 初稿 |
| v0.2 | 2026-10-18 | | 增加「交付客户 + 纯开源免费」硬约束；每项选型补充许可；新增许可合规流程（第 12 节）；Grafana 移出交付物；明确不用 Liquibase、Redisson、Oracle JDK、Docker Desktop；Broker 集群路径改为 BifroMQ |
| v0.3 | 2026-10-18 | | 交付方式确定为纯开源、非盈利：项目许可 Apache-2.0；许可名单放宽（LGPL 等可链接，GPL/AGPL 仅限独立进程）；Grafana 改为可选组件；新增开源项目治理（第 16 节） |
