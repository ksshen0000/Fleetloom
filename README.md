# RCS3000

开源的多 AGV 管理控制平台（Robot Control System）。Java 25 + Spring Boot 4 后端，Vue 3 前端，车辆通过 VDA 5050 / MQTT 接入，自带 AGV 模拟器。

许可：[Apache-2.0](LICENSE)

## 文档

- [需求文档](docs/requirements.md)
- [技术评估](docs/tech-evaluation.md)

## 模块

| 模块 | 说明 | 状态 |
| --- | --- | --- |
| `rcs-common` | 统一响应、错误码、全局异常处理、服务间事件总线（Valkey Streams） | 可用 |
| `rcs-contracts` | 服务间契约：DTO、枚举、事件（纯 Java） | 地图、车辆部分可用 |
| `rcs-resource` | 基础资源服务：地图、库区库位、载具与货物、车辆档案、车辆轨迹与电量历史、用户与登录（签发 JWT）、告警中心、操作审计日志 | 可用；Excel 导入导出待做 |
| `rcs-protocol-vda5050` | VDA 5050 v2.1 报文模型、编解码、主题、MQTT 收发（车端 `AgvClient` / 主控端 `MasterClient`） | 可用 |
| `rcs-simulator` | AGV 模拟器：多车运动、订单接收与更新、动作、电量、暂停 / 取消 / 重定位、运行时增删虚拟车、故障注入（报错 / 障碍停车 / 断线）、场景脚本（定时下单压测） | 可用 |
| `rcs-fleet` | 机器人控制：自动发现车辆、在线 / 心跳判定、下发路线与更新、暂停 / 恢复 / 取消 / 重定位、移除离线车辆、状态缓存与事件、报文日志、丢失订单重发与不同步告警、重启后接续车上订单 | 可用 |
| `rcs-traffic` | 路径规划与交通管制：A*（含转向代价）、点位 / 路径 / 互斥区预约、单行道方向锁（防对头）、逐段放行、等待与绕行、死锁检测（持续时定期重报）与解除（一台车退到旁路让行）、重启后接管 | 可用；碰撞包络（TRF-14）待做 |
| `rcs-dispatch` | 任务调度：搬运 / 移动订单、库位锁定、按路径代价选车、取放货联动、自动充电、自动停靠、空闲车让路、停靠途中陷入死锁时放弃停靠并让路、其他服务短暂不可用时重试、挂起恢复取消、订单模板、截止时间告警、上游回调、订单统计 | 可用 |
| `rcs-web` | Vue 3 控制台：登录、监控大屏、看板、订单、车辆（含报文日志、历史轨迹）、地图编辑器、库区库位、载具、告警、模拟器、用户管理、审计日志、系统设置；WebSocket 实时推送 | 可用 |
| `rcs-gateway` | 统一入口：按 `/api/<服务>/**` 路由、JWT 校验与角色权限、向服务注入 `X-User-Name` / `X-User-Role`、`/ws` 事件推送、写操作审计、登录失败限流；fleet 的原始下发接口不对外（只能经交通管制） | 可用；通用限流待做 |

服务端口：gateway 8080 · resource 8081 · dispatch 8082 · fleet 8083 · traffic 8084 · simulator 8090

## 本地开发

前置：JDK 25、Docker（当前用户需在 `docker` 组中）。

```bash
# 启动 PostgreSQL / Valkey / Mosquitto
docker compose -f deploy/docker-compose.yml up -d

# 构建并运行全部测试（Docker 不可用时跳过集成测试）
./mvnw verify

# 许可检查 + SBOM
./mvnw -Plicense-check verify -DskipTests

# 启动基础资源服务（http://localhost:8081）
./mvnw -pl rcs-resource spring-boot:run
```

### 一键启动全部服务

```bash
./mvnw install -DskipTests
deploy/dev-stack.sh start              # 基础设施 + 6 个服务 + 前端 http://localhost:5173；日志在 .dev/logs
deploy/dev-stack.sh status
deploy/dev-stack.sh restart fleet      # 改了某个模块后：./mvnw install -pl rcs-fleet -DskipTests，再只重启它
deploy/dev-stack.sh stop
```

空数据库首次启动时自动建好演示数据（旧的小地图也会升级）：地图 DEMO，库区 A 的 9 个库位和货架 S1 / S2，两台模拟车 AGV-001 / AGV-002。地图 DEMO 包括两部分：
- 3×3 网格，带充电点 CH1。
- 东侧一个单向通道的小仓库：入库口 IN-1..3、存储位 ST-01..12（货架 SH01..06 带货物）、出库口 OUT-1..3、拣选站 PS-1/2，另有停靠点 PARK1..3 和充电点 CH2。服务从 `.dev/jars` 下的副本运行，所以运行中重新构建不会影响它们。`RCS_NO_WEB=1` 不启动前端。

任一服务重启后，进行中的订单都能接着做完（NFR-03）：
- fleet 从车辆上报的状态接回订单。
- traffic 重建预约，并取消它不认识、已经停住的旧订单。
- dispatch 发现路线丢失时重新申请；traffic 或 resource 暂时连不上时，最多重试 2 分钟才挂起订单。

模拟器重启相当于车辆重启：运行时加的车会回到 fleet 最后看到的位置，订单挂起，在「订单」页恢复即可。

### 登录与权限

浏览器只访问网关（8080）。首次启动时 rcs-resource 创建管理员 `admin`，密码取 `RCS_ADMIN_PASSWORD`（默认 `admin12345`，交付前务必修改）。令牌由 rcs-resource 用 `RCS_JWT_SECRET` 签发（HS256，至少 32 字节，默认 12 小时有效），网关用同一密钥校验，两边必须一致。

| 角色 | 权限 |
| --- | --- |
| `VIEWER` 只读 | 所有 GET |
| `OPERATOR` 操作员 | 另可下单、控制车辆、维护地图 / 库位 / 载具 |
| `ADMIN` 管理员 | 另可管理用户（`/api/resource/v1/users`）、修改系统设置（`PUT /api/*/v1/settings`）、访问 actuator |

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/resource/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin12345"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["token"])')
curl localhost:8080/api/fleet/v1/vehicles -H "Authorization: Bearer $TOKEN"
```

网关会丢弃客户端自带的 `X-User-Name` / `X-User-Role` 再按令牌重新填写，所以各服务端口（8081~8090）在生产环境不应对外暴露，只开放 8080。

### 告警、审计、统计、回调

- **单行道方向锁**：地图上由两条以上双向路径串成、中间没有岔路的路段（如网格的拐角、两个路口之间的窄通道）自动识别为「单行道」。车辆在进入前一段就按行驶方向锁住整段：同向的车可以跟车进入，反向的车在路口外等待，不会在通道里对头卡死。为了不让单方向车流把对向车饿死，有对向车先等时，同向车不再跟进，等通道清空后对向先走。预约列表里显示为 `CORRIDOR:<一端>~<另一端>`。
- **死锁解除**（TRF-16）：等待环持续 `replanAfter`（默认 10 s）仍未被绕行或 dispatch 挪车化解时，交通管制从环中挑一台「退得最近」的车，让它退到最近一个其他车用不到的空闲点，等对方通过后再继续原目标。两车在死胡同里互换位置这类无处可退的情况仍只告警，需人工处理。
- **告警**（`/api/resource/v1/alarms`）：rcs-resource 订阅事件自动开 / 关告警——车辆上报错误、离线、交通阻塞、死锁、订单挂起 / 失败。同一条件只有一条未关闭告警；条件消失自动关闭，订单失败等需人工「关闭」。控制台菜单显示未确认数，ERROR / FATAL 新告警会弹出通知。
- **审计日志**（`/api/resource/v1/audit-logs`，仅管理员）：网关记录每个写请求（含被拒绝的和登录尝试）的用户、路径、结果、耗时、来源 IP，默认保留 180 天（`rcs.audit.retention`）。
- **订单统计**（`GET /api/dispatch/v1/statistics?hours=24`）：每小时完成 / 失败 / 取消数和平均完成时长，以及当前进行中订单数；控制台「看板」页展示。
- **订单模板**（DSP-03，`/api/dispatch/v1/templates`）：搬运订单按模板拆成步骤——源库位动作、途经点位（可带动作）、目标库位动作。内置 `STANDARD`（pick / drop，未指定模板时使用，不可删除）和 `LIFT`（潜伏顶升 lift / lower）；控制台「订单 → 订单模板」可增改。
- **截止时间**（DSP-10）：下单可带 `deadline`（ISO 时间），到时未完成会产生「订单超时」告警，订单结束后自动关闭。
- **自动停靠**（DSP-08）：车辆空闲超过 `rcs.dispatch.park-after-idle`（默认 30 s，0 关闭）且不在停靠点（PARKING 类型点位）时，去最近的空闲停靠点；地图上没有停靠点则不动。停靠途中陷入死锁的车（如停靠点被手动放上的车占着，而那辆车要出来）放弃停靠并让路。
- **上游回调**（DSP-11）：设置 `RCS_ORDER_CALLBACK_URL` 后，每次订单状态变化 POST `{event, eventId, occurredAt, previous, order}`；失败重试 3 次（1 s / 2 s / 4 s）；`X-RCS-Event-Id` 用于去重，设置 `RCS_ORDER_CALLBACK_SECRET` 时附带 `X-RCS-Signature: sha256=<HMAC>`。
- **车辆报文日志**（FLT-07，`GET /api/fleet/v1/vehicles/{code}/messages?from=&to=&limit=`）：下发的订单 / 即时动作、连接变化全部记录；状态报文只在订单、点位、动作、错误等变化时记录，位置电量变化每分钟一条；存 Valkey，保留 3 天（`rcs.fleet.message-log.retention`）。控制台「车辆 → 报文」。
- **车辆历史**（VEH-09，`GET /api/resource/v1/vehicles/{code}/history?from=&to=`）：位置与电量采样（移动 ≥ 0.5 m、经过点位、电量变化 1% 或至少每分钟一条），保留 7 天（`rcs.vehicle-history.retention`）。控制台「车辆 → 历史」画出轨迹和电量曲线。
- **订单一致性**（FLT-06）：VDA 5050 订单以 QoS 0 发送，可能丢失。车辆 3 秒内没有上报 fleet 最后发出的订单（orderId + orderUpdateId）且没有拒绝时，fleet 重发（车辆会忽略已有的更新），最多 3 次；仍不一致则车辆状态带 `orderOutOfSync` 错误，产生告警。
- **取消与挂起**：取消订单时车辆把正在走的这段路走完、停在下一个点位（VDA 5050 允许），不会停在两点之间；挂起后立即恢复，调度会等车辆停稳再发新路线。
- **移除离线车辆**（`DELETE /api/fleet/v1/vehicles/{code}`）：车辆不在线时才可移除；它的离线 / 故障告警一并关闭，交通管制释放它占着的点位，broker 上保留的连接消息也清掉，重启后不会再冒出来。
- **载具货物**（CAR-05，`GET/PUT /api/resource/v1/carriers/{code}/goods`，`GET /api/resource/v1/goods?sku=`）：记录载具上的 SKU、名称、数量、单位、批次（v1 只记录，不参与调度）；按 SKU 前缀查货在哪个载具、载具在哪。控制台「载具 → 货物」和「按 SKU 查找货物」。
- **地图导入导出**（MAP-09）：地图编辑器「导出」把当前版本存成 JSON 文件（`format: rcs3000-map`），「导入」把这种文件或纯内容 JSON（如 `deploy/examples/demo-map.json`）载入草稿，检查后保存、发布。
- **系统设置**（WEB-09，`/api/dispatch/v1/settings`、`/api/traffic/v1/settings`）：默认优先级、空闲停靠时间、前方预约点数 / 距离、受阻报告与绕行等待等，修改即时生效并持久保存；控制台「系统设置」（仅管理员）。
- **模拟器虚拟车**（SIM-11，`POST /api/simulator/v1/vehicles` 批量创建，`DELETE /api/simulator/v1/vehicles/{sn}`）：控制台「模拟器 → 新增虚拟车」可按前缀批量创建，自动分配空闲点位（优先停靠点，其次通道上的点，最后才是尽头点）并登记车辆档案；删除时同时从 fleet 移除。模拟器重启时，运行时创建且 fleet 仍认识的车会回到最后的位置（`rcs.simulator.restore-vehicles`，默认开）；常驻车写在配置里。
- **场景脚本**（SIM-12，`POST /api/simulator/v1/scenarios`，YAML）：
  - 可以加虚拟车（同时登记车辆档案）。
  - 按 `start / every / count` 定时向 dispatch 下 MOVE / TRANSPORT 单，目标点位、载具列表轮流取用。
  - 搬运单可以模拟仓库作业：
    - `arrive`（入库）：货架带货物出现在空闲入库口，再送去存储区。
    - `fromZone`（可加 `sku`）：从库区取一个空闲的载具，用于出库、拣选、移库。
    - `depart`（出库）：送达后若干秒装车离场。
    - `returnAfter`（拣选）：在站台停留后送回。
  - 没有空闲库位或载具时排队等待，超过 `maxWait`（默认 2 分钟）记为被拒；平台暂时不响应时自动重试。
  - 场景设置的 `timeScale` 只在运行期间有效。
  - 跟踪到全部结束，报告完成 / 被拒 / 失败数、平均与 P95 耗时、每小时完成量，并按作业流分别统计。

  `deploy/examples/run-scenario.sh [文件]` 运行并打印进度。示例：
  - `scenario-warehouse.yaml`：一个班次的入库 / 拣选 / 出库 / 移库，入出平衡，可反复运行。
  - `scenario-warehouse-peak.yaml`：6 台车的高峰压测。
  - `scenario-demo.yaml`：网格上的移动和搬运。

  控制台「模拟器」页可以选用这些示例或粘贴自己的。
- **模拟器故障注入**（`/api/simulator/v1/vehicles/{sn}/faults/...`）：`error`（WARNING / FATAL，FATAL 会停车）、`obstacle`（障碍停车 N 秒）、`disconnect`（断网 N 秒），`DELETE .../faults` 全部清除；控制台「模拟器」页可直接操作。

### 实时推送

`ws://<网关>/ws?access_token=<令牌>`，每条消息为 `{"type","occurredAt","payload"}`，转发的事件类型见 `rcs-gateway` 的 `rcs.gateway.push.events`（车辆状态、连接变化、车辆移除、订单状态、路线进度、交通阻塞、死锁、地图发布、告警）。推送是尽力而为：断线期间的事件不补发，前端重连后会重新拉取一次数据；轮询保留为兜底（推送在线时放慢）。

### 地图接口速览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/resource/v1/maps` | 新建地图 |
| GET | `/api/resource/v1/maps` | 地图列表 |
| POST | `/api/resource/v1/maps/{code}/versions` | 新建草稿（可带 `copyFromVersionNo`） |
| PUT | `/api/resource/v1/maps/{code}/versions/{no}/content` | 保存草稿全文（带 `revision` 乐观锁），返回校验结果 |
| POST | `/api/resource/v1/maps/{code}/versions/{no}/validate` | 发布前校验 |
| POST | `/api/resource/v1/maps/{code}/versions/{no}/publish` | 发布 |
| GET | `/api/resource/v1/maps/{code}/active` | 当前生效拓扑（含路径长度） |

### 模拟器

```bash
# 启动模拟器（http://localhost:8090），默认一台车 AGV-001 停在 (0, 0)
./mvnw -pl rcs-simulator spring-boot:run
# 或加载已发布的地图，车辆可用 initial-point 放在地图点位上
RCS_SIM_MAP=WH1 ./mvnw -pl rcs-simulator spring-boot:run

# 手动发一个订单：A(0,0) -> B(5,0) 取货 -> C(5,5)
docker exec -i rcs3000-mosquitto-1 mosquitto_pub -t uagv/v2/RCS3000-SIM/AGV-001/order -s < deploy/examples/order-a-b-c.json

# 看车的状态
docker exec rcs3000-mosquitto-1 mosquitto_sub -t 'uagv/v2/RCS3000-SIM/AGV-001/state'
curl localhost:8090/api/simulator/v1/vehicles
```

模拟器不做避让：两台车会互相穿过，防碰撞是交通管制服务的职责。

### 通过 fleet 控制车辆

```bash
./mvnw -pl rcs-fleet spring-boot:run          # http://localhost:8083，需 Mosquitto 与 Valkey

curl localhost:8083/api/fleet/v1/vehicles     # 已发现的车辆及实时状态
curl -X POST localhost:8083/api/fleet/v1/vehicles/AGV-001/orders \
     -H 'Content-Type: application/json' -d @deploy/examples/fleet-route.json
curl -X POST localhost:8083/api/fleet/v1/vehicles/AGV-001/pause   # 还有 resume / cancel-order / relocate
```

下发路线的接口是内部接口：正式流程中由交通管制服务规划并预约路线后调用，不经网关对外开放。

### 完整链路演示（地图 → 交通管制 → fleet → 模拟车）

```bash
docker compose -f deploy/docker-compose.yml up -d
./mvnw install -DskipTests

java -jar rcs-resource/target/rcs-resource-0.1.0-SNAPSHOT.jar &
deploy/examples/setup-demo-map.sh                     # 发布演示地图 DEMO（网格 + 小仓库）
java -jar rcs-simulator/target/rcs-simulator-0.1.0-SNAPSHOT-exec.jar \
     '--rcs.simulator.map.code=DEMO' \
     '--rcs.simulator.vehicles[0].serial-number=AGV-001' '--rcs.simulator.vehicles[0].initial-point=P10' \
     '--rcs.simulator.vehicles[1].serial-number=AGV-002' '--rcs.simulator.vehicles[1].initial-point=P01' &
java -jar rcs-fleet/target/rcs-fleet-0.1.0-SNAPSHOT-exec.jar &
java -jar rcs-traffic/target/rcs-traffic-0.1.0-SNAPSHOT-exec.jar &

# 两车在 P11 交叉：后到的一台会在路口外等待
curl -X POST localhost:8084/api/traffic/v1/routes -H 'Content-Type: application/json' -d '{"vehicleCode":"AGV-001","targetPoint":"P12"}'
curl -X POST localhost:8084/api/traffic/v1/routes -H 'Content-Type: application/json' -d '{"vehicleCode":"AGV-002","targetPoint":"P21"}'
curl localhost:8084/api/traffic/v1/routes          # 路线与进度
curl localhost:8084/api/traffic/v1/reservations    # 当前预约
```

### 前端

```bash
cd rcs-web
corepack pnpm install
corepack pnpm dev          # http://localhost:5173，/api 与 /ws 代理到网关 8080（可用 RCS_GATEWAY_URL 改）
corepack pnpm test         # 单元测试（Vitest）
corepack pnpm e2e          # 端到端测试（Playwright，需先 deploy/dev-stack.sh start；账号可用 E2E_USER / E2E_PASSWORD 指定）
corepack pnpm build        # 类型检查 + 生产构建
```

监控、订单、车辆页由 WebSocket 推送驱动，车辆位置做插值平滑；推送断开时退回每秒轮询。

### 容器化部署

```bash
deploy/docker/build.sh       # 构建 jar、前端和镜像
RCS_JWT_SECRET=<至少 32 字节> RCS_ADMIN_PASSWORD=<管理员初始密码> \
  docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml up -d
# 加两台模拟车：再加 --profile sim
```

只对外开放控制台端口（`RCS_HTTP_PORT`，默认 8000）：nginx 提供前端静态文件，并把 `/api`、`/ws` 转发给网关；各服务只在 compose 内网互通。

## 代码约定

- 每个服务分 `api` → `application` → `domain` ← `infrastructure` 四层，`domain` 不依赖任何框架，由 `ArchitectureTest` 强制。
- 跨服务引用使用业务编码，不使用数据库 ID。
- 提交需签署 DCO：`git commit -s`。
