/** JSON shapes of the backend APIs (see rcs-contracts and the services' view records). */

export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  traceId: string | null
}

export interface Position {
  x: number
  y: number
}

// ------------------------------------------------------------------ maps (rcs-resource)

export type PointType = 'NORMAL' | 'WORKSTATION' | 'CHARGING' | 'PARKING' | 'ROTATION' | 'WAITING'
export type PathDirection = 'UNIDIRECTIONAL' | 'BIDIRECTIONAL'
export type AreaType = 'MUTEX' | 'SPEED_LIMIT' | 'FORBIDDEN' | 'CHARGING_ZONE'

export interface MapPoint {
  code: string
  x: number
  y: number
  theta?: number | null
  type: PointType
  properties?: Record<string, string>
}

export interface MapPath {
  code: string
  fromPoint: string
  toPoint: string
  direction: PathDirection
  shape?: 'LINE' | 'BEZIER'
  controlPoints?: Position[]
  maxSpeed?: number | null
  orientation?: 'FORWARD' | 'BACKWARD' | 'ANY'
  allowedVehicleTypes?: string[]
  weightFactor?: number
  enabled?: boolean
  length?: number
}

export interface MapArea {
  code: string
  type: AreaType
  polygon: Position[]
  properties?: Record<string, string>
}

export interface MapContent {
  points: MapPoint[]
  paths: MapPath[]
  areas: MapArea[]
}

export interface MapView {
  code: string
  name: string
  description: string | null
  activeVersionNo: number | null
  createdAt: string
  updatedAt: string
}

export type MapVersionStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'

export interface MapVersionSummary {
  versionNo: number
  status: MapVersionStatus
  revision: number
  createdAt: string
  publishedAt: string | null
  publishedBy: string | null
}

export interface MapVersionView extends MapContent {
  mapCode: string
  versionNo: number
  status: MapVersionStatus
  revision: number
}

export interface MapTopology extends MapContent {
  mapCode: string
  versionNo: number
}

export interface ValidationIssue {
  severity: 'ERROR' | 'WARNING'
  rule: string
  elementCode: string | null
  message: string
}

export interface ValidationReport {
  issues: ValidationIssue[]
}

export interface SaveDraftResult {
  revision: number
  validation: ValidationReport
}

// ------------------------------------------------------------------ storage and carriers (rcs-resource)

export type LocationStatus = 'EMPTY' | 'OCCUPIED' | 'LOCKED_IN' | 'LOCKED_OUT' | 'DISABLED'
export type LocationType = 'GROUND' | 'RACK' | 'STATION' | 'BUFFER'

export interface Zone {
  code: string
  name: string
  type: string
  description: string | null
}

export interface StorageLocation {
  code: string
  zoneCode: string
  type: LocationType
  mapCode: string
  accessPoint: string
  accessTheta: number | null
  level: number
  height: number
  allowedCarrierTypes: string[]
  status: LocationStatus
  lockOwner: string | null
  carrierCode: string | null
  updatedAt: string
}

export type CarrierCategory = 'PALLET' | 'SHELF' | 'CART' | 'BIN'

export interface CarrierType {
  code: string
  name: string
  category: CarrierCategory
  length: number
  width: number
  height: number
  maxLoad: number
  compatibleVehicleTypes: string[]
}

export interface Carrier {
  code: string
  typeCode: string
  status: 'IDLE' | 'IN_TRANSIT' | 'DISABLED'
  locationType: 'STORAGE' | 'VEHICLE' | 'NONE'
  locationRef: string | null
  lastMovedAt: string | null
}

export interface CarrierMovement {
  carrierCode: string
  fromType: string
  fromRef: string | null
  toType: string
  toRef: string | null
  orderRef: string | null
  movedAt: string
}

// ------------------------------------------------------------------ vehicle registry (rcs-resource)

export type VehicleCategory = 'JACKING' | 'FORKLIFT' | 'ROLLER' | 'TOWING'

export interface VehicleType {
  code: string
  name: string
  category: VehicleCategory
  length: number
  width: number
  maxSpeed: number
  maxLoad: number
  supportedActions: string[]
}

export interface RegisteredVehicle {
  code: string
  name: string
  typeCode: string
  manufacturer: string
  serialNumber: string
  simulated: boolean
  enabled: boolean
  initialPoint: string | null
  chargeThresholdLow: number
  chargeThresholdHigh: number
}

// ------------------------------------------------------------------ live vehicles (rcs-fleet)

export type ConnectionStatus = 'ONLINE' | 'OFFLINE' | 'CONNECTION_BROKEN'

export interface VehicleStatus {
  code: string
  manufacturer: string
  serialNumber: string
  connection: ConnectionStatus
  lastSeen: string
  mapCode: string | null
  x: number
  y: number
  theta: number
  positionInitialized: boolean
  velocity: number
  driving: boolean
  paused: boolean
  operatingMode: string | null
  orderId: string | null
  orderUpdateId: number
  lastNodeId: string | null
  lastNodeSequenceId: number
  remainingNodes: { nodeId: string; sequenceId: number; released: boolean }[]
  battery: number
  charging: boolean
  loadIds: (string | null)[]
  actions: { actionId: string; actionType: string; status: string; resultDescription: string | null }[]
  errors: { errorType: string; level: string; description: string | null; orderId: string | null }[]
}

// ------------------------------------------------------------------ traffic (rcs-traffic)

export type RouteStatus = 'WAITING' | 'MOVING' | 'ARRIVED' | 'CANCELLED' | 'FAILED'

export interface RouteView {
  routeId: string
  vehicleCode: string
  mapCode: string
  targetPoint: string
  status: RouteStatus
  nodes: string[]
  passedIndex: number
  releasedIndex: number
  blockedBy: string | null
  blockedResource: string | null
  estimatedSeconds: number
  createdAt: string
}

export interface Reservation {
  mapCode: string
  resource: string
  vehicleCode: string
}

// ------------------------------------------------------------------ dispatch (rcs-dispatch)

export type OrderType = 'TRANSPORT' | 'MOVE' | 'CHARGE'
export type OrderStatus = 'QUEUED' | 'EXECUTING' | 'SUSPENDED' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'REJECTED'

export interface OrderView {
  orderNo: string
  externalNo: string | null
  type: OrderType
  priority: number
  status: OrderStatus
  carrierCode: string | null
  sourceLocation: string | null
  targetLocation: string | null
  mapCode: string | null
  assignedVehicle: string | null
  currentLeg: number
  legs: { point: string; action: string | null; status: 'PENDING' | 'RUNNING' | 'DONE' }[]
  reason: string | null
  createdAt: string
  updatedAt: string
  finishedAt: string | null
  deadline: string | null
}

export interface CreateOrder {
  externalNo?: string
  type: 'TRANSPORT' | 'MOVE'
  priority?: number
  carrierCode?: string
  targetLocation?: string
  targetZone?: string
  mapCode?: string
  targetPoint?: string
  vehicleCode?: string
  /** ISO instant; an alarm is raised if the order is not finished by then */
  deadline?: string
  /** TRANSPORT: order template code; the standard pick / drop if absent */
  template?: string
}

/** How a transport is broken into legs (DSP-03). */
export interface OrderTemplate {
  code: string
  name: string
  pickAction: string | null
  dropAction: string | null
  viaPoints: string[]
  viaAction: string | null
  description: string | null
}

export interface DispatchVehicle {
  code: string
  registeredAs: string | null
  accepting: boolean
  connection: ConnectionStatus | null
  battery: number
  charging: boolean
  currentOrder: string | null
  unavailable: string | null
}

// ------------------------------------------------------------------ users (rcs-resource) and push (rcs-gateway)

export type Role = 'ADMIN' | 'OPERATOR' | 'VIEWER'

export interface UserView {
  username: string
  displayName: string
  role: Role
  enabled: boolean
  createdAt: string
}

export interface LoginResult {
  token: string
  expiresAt: string
  user: UserView
}

/** A frame on the gateway's /ws push channel. */
export interface PushMessage<T = unknown> {
  type: string
  occurredAt: string
  payload: T
}

// ------------------------------------------------------------------ alarms (rcs-resource)

export type AlarmLevel = 'WARNING' | 'ERROR' | 'FATAL'
export type AlarmStatus = 'ACTIVE' | 'ACKNOWLEDGED' | 'CLEARED'
export type AlarmCategory =
  | 'VEHICLE_ERROR'
  | 'VEHICLE_OFFLINE'
  | 'TRAFFIC_BLOCKED'
  | 'DEADLOCK'
  | 'ORDER_SUSPENDED'
  | 'ORDER_FAILED'
  | 'ORDER_OVERDUE'

export interface Alarm {
  id: number
  alarmKey: string
  category: AlarmCategory
  level: AlarmLevel
  vehicleCode: string | null
  orderNo: string | null
  message: string
  status: AlarmStatus
  occurrences: number
  raisedAt: string
  lastSeenAt: string
  acknowledgedBy: string | null
  acknowledgedAt: string | null
  clearedBy: string | null
  clearedAt: string | null
}

export interface AlarmSummary {
  open: number
  unacknowledged: number
  byLevel: Partial<Record<AlarmLevel, number>>
}

// ------------------------------------------------------------------ simulator (rcs-simulator)

export interface SimVehicle {
  serialNumber: string
  x: number
  y: number
  theta: number
  velocity: number
  driving: boolean
  orderId: string
  lastNodeId: string
  battery: number
  charging: boolean
  faults: string[]
  connectionDropped: boolean
}

// ------------------------------------------------------------------ statistics (rcs-dispatch)

export interface HourStats {
  hour: string | null
  completed: number
  failed: number
  cancelled: number
  avgSeconds: number | null
}

export interface OrderStatistics {
  since: string
  hours: HourStats[]
  total: HourStats
  open: Partial<Record<OrderStatus, number>>
}

export interface AuditEntry {
  id: number
  at: string
  username: string | null
  role: Role | null
  method: string
  path: string
  query: string | null
  status: number
  durationMs: number
  clientIp: string | null
}

/** One VDA 5050 message exchanged with a vehicle (FLT-07). */
export interface VehicleMessage {
  id: string
  at: string
  vehicleCode: string
  direction: 'IN' | 'OUT'
  topic: string
  payload: string
}

/** A sampled position and battery level of a vehicle (VEH-09). */
export interface VehicleSample {
  vehicleCode: string
  at: string
  mapCode: string | null
  x: number
  y: number
  theta: number
  battery: number
  charging: boolean
  driving: boolean
  lastNodeId: string | null
  connection: string
}

/** Runtime dispatch parameters (WEB-09). */
export interface DispatchTunables {
  defaultPriority: number
  parkAfterIdleSeconds: number
}

/** Runtime traffic parameters (WEB-09). */
export interface TrafficTunables {
  lookaheadNodes: number
  lookaheadDistance: number
  occupiedPenalty: number
  blockedEventAfterSeconds: number
  replanAfterSeconds: number
}

/** One line of goods on a carrier (CAR-05). */
export interface Goods {
  sku: string
  name: string | null
  quantity: number
  unit: string | null
  batchNo: string | null
}

export interface StockedGoods {
  carrierCode: string
  locationType: 'STORAGE' | 'VEHICLE' | 'NONE' | null
  locationRef: string | null
  goods: Goods
}

/** Progress and result of a scenario run (SIM-12). */
export interface ScenarioReport {
  name: string | null
  state: 'RUNNING' | 'FINISHED' | 'STOPPED'
  startedAt: string
  finishedAt: string | null
  planned: number
  sent: number
  rejected: number
  completed: number
  failed: number
  cancelled: number
  open: number
  avgSeconds: number | null
  p95Seconds: number | null
  maxSeconds: number | null
  perHour: number | null
  streams?: ScenarioStream[]
  errors: string[]
}

/** The counts of one order stream of a scenario run; return trips have a row of their own. */
export interface ScenarioStream {
  name: string
  planned: number
  sent: number
  rejected: number
  completed: number
  failed: number
  cancelled: number
  open: number
  waited: number
  departed: number
  avgSeconds: number | null
  p95Seconds: number | null
}
