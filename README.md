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
| `rcs-resource` | 基础资源服务：地图、库位、载具、车辆档案、用户 | 地图模块可用 |
| `rcs-protocol-vda5050` | VDA 5050 v2.1 报文模型、编解码、主题、MQTT 收发（车端 `AgvClient` / 主控端 `MasterClient`） | 可用 |
| `rcs-simulator` | AGV 模拟器：多车运动、订单接收与更新、动作、电量、暂停 / 取消 / 重定位 | 可用；车辆增删接口、故障注入待做 |
| `rcs-fleet` | 机器人控制：自动发现车辆、在线 / 心跳判定、下发路线与更新、暂停 / 恢复 / 取消 / 重定位、状态缓存与事件 | 可用；报文日志、订单一致性告警待做 |
| `rcs-traffic` | 路径规划与交通管制：A*（含转向代价）、点位 / 路径 / 互斥区预约、逐段放行、等待与绕行、死锁检测、重启后接管 | 可用；碰撞包络（TRF-14）、死锁主动解除（TRF-16）待做 |
| `rcs-dispatch` / `rcs-gateway` / `rcs-web` | 见需求文档第 3 节 | 未开始 |

服务端口：gateway 8080（规划）· resource 8081 · dispatch 8082（规划）· fleet 8083 · traffic 8084（规划）· simulator 8090

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
deploy/examples/setup-demo-map.sh                     # 发布 3x3 演示地图 DEMO
java -jar rcs-simulator/target/rcs-simulator-0.1.0-SNAPSHOT-exec.jar \
     '--rcs.simulator.map.code=DEMO' \
     '--rcs.simulator.vehicles[0].serial-number=AGV-001' '--rcs.simulator.vehicles[0].initial-point=P10' \
     '--rcs.simulator.vehicles[1].serial-number=AGV-002' '--rcs.simulator.vehicles[1].initial-point=P01' &
java -jar rcs-fleet/target/rcs-fleet-0.1.0-SNAPSHOT-exec.jar &
java -jar rcs-traffic/target/rcs-traffic-0.1.0-SNAPSHOT.jar &

# 两车在 P11 交叉：后到的一台会在路口外等待
curl -X POST localhost:8084/api/traffic/v1/routes -H 'Content-Type: application/json' -d '{"vehicleCode":"AGV-001","targetPoint":"P12"}'
curl -X POST localhost:8084/api/traffic/v1/routes -H 'Content-Type: application/json' -d '{"vehicleCode":"AGV-002","targetPoint":"P21"}'
curl localhost:8084/api/traffic/v1/routes          # 路线与进度
curl localhost:8084/api/traffic/v1/reservations    # 当前预约
```

## 代码约定

- 每个服务分 `api` → `application` → `domain` ← `infrastructure` 四层，`domain` 不依赖任何框架，由 `ArchitectureTest` 强制。
- 跨服务引用使用业务编码，不使用数据库 ID。
- 提交需签署 DCO：`git commit -s`。
