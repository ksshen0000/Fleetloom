import { call, http } from './http'
import type {
  Alarm,
  AlarmLevel,
  AlarmSummary,
  AuditEntry,
  Carrier,
  CarrierMovement,
  CarrierType,
  CreateOrder,
  LoginResult,
  DispatchTunables,
  DispatchVehicle,
  Goods,
  MapContent,
  MapTopology,
  MapVersionSummary,
  MapVersionView,
  MapView,
  OrderStatistics,
  OrderTemplate,
  OrderStatus,
  OrderView,
  RegisteredVehicle,
  Role,
  Reservation,
  RouteView,
  SaveDraftResult,
  ScenarioReport,
  SimVehicle,
  StockedGoods,
  StorageLocation,
  TrafficTunables,
  UserView,
  ValidationReport,
  VehicleMessage,
  VehicleSample,
  VehicleStatus,
  VehicleType,
  Zone,
} from './types'

const R = '/api/resource/v1'

export const mapsApi = {
  list: () => call<MapView[]>(http.get(`${R}/maps`)),
  create: (body: { code: string; name: string; description?: string }) => call<MapView>(http.post(`${R}/maps`, body)),
  versions: (code: string) => call<MapVersionSummary[]>(http.get(`${R}/maps/${code}/versions`)),
  version: (code: string, no: number) => call<MapVersionView>(http.get(`${R}/maps/${code}/versions/${no}`)),
  createDraft: (code: string, copyFromVersionNo?: number) =>
    call<MapVersionView>(http.post(`${R}/maps/${code}/versions`, copyFromVersionNo ? { copyFromVersionNo } : {})),
  saveContent: (code: string, no: number, revision: number, content: MapContent) =>
    call<SaveDraftResult>(http.put(`${R}/maps/${code}/versions/${no}/content`, { revision, ...content })),
  validate: (code: string, no: number) => call<ValidationReport>(http.post(`${R}/maps/${code}/versions/${no}/validate`)),
  publish: (code: string, no: number) => call<ValidationReport>(http.post(`${R}/maps/${code}/versions/${no}/publish`)),
  deleteDraft: (code: string, no: number) => call<void>(http.delete(`${R}/maps/${code}/versions/${no}`)),
  active: (code: string) => call<MapTopology>(http.get(`${R}/maps/${code}/active`)),
}

export const storageApi = {
  zones: () => call<Zone[]>(http.get(`${R}/zones`)),
  createZone: (z: Omit<Zone, 'description'> & { description?: string }) => call<Zone>(http.post(`${R}/zones`, z)),
  updateZone: (z: Zone) => call<Zone>(http.put(`${R}/zones/${z.code}`, z)),
  deleteZone: (code: string) => call<void>(http.delete(`${R}/zones/${code}`)),
  locations: (params: { zone?: string; status?: string; map?: string } = {}) =>
    call<StorageLocation[]>(http.get(`${R}/storage-locations`, { params })),
  createLocation: (l: LocationInput) => call<StorageLocation>(http.post(`${R}/storage-locations`, l)),
  updateLocation: (l: LocationInput) => call<StorageLocation>(http.put(`${R}/storage-locations/${l.code}`, l)),
  deleteLocation: (code: string) => call<void>(http.delete(`${R}/storage-locations/${code}`)),
  disable: (code: string) => call<StorageLocation>(http.post(`${R}/storage-locations/${code}/disable`)),
  enable: (code: string) => call<StorageLocation>(http.post(`${R}/storage-locations/${code}/enable`)),
}

export interface LocationInput {
  code: string
  zoneCode: string
  type: string
  mapCode: string
  accessPoint: string
  accessTheta?: number | null
  level?: number
  height?: number
  allowedCarrierTypes?: string[]
}

export const carriersApi = {
  types: () => call<CarrierType[]>(http.get(`${R}/carrier-types`)),
  createType: (t: CarrierType) => call<CarrierType>(http.post(`${R}/carrier-types`, t)),
  updateType: (t: CarrierType) => call<CarrierType>(http.put(`${R}/carrier-types/${t.code}`, t)),
  deleteType: (code: string) => call<void>(http.delete(`${R}/carrier-types/${code}`)),
  list: () => call<Carrier[]>(http.get(`${R}/carriers`)),
  create: (c: { code: string; typeCode: string }) => call<Carrier>(http.post(`${R}/carriers`, c)),
  remove: (code: string) => call<void>(http.delete(`${R}/carriers/${code}`)),
  place: (code: string, locationCode: string) => call<Carrier>(http.post(`${R}/carriers/${code}/place`, { locationCode })),
  takeOut: (code: string) => call<Carrier>(http.post(`${R}/carriers/${code}/take-out`)),
  disable: (code: string) => call<Carrier>(http.post(`${R}/carriers/${code}/disable`)),
  enable: (code: string) => call<Carrier>(http.post(`${R}/carriers/${code}/enable`)),
  movements: (code: string) => call<CarrierMovement[]>(http.get(`${R}/carriers/${code}/movements`)),
  goods: (code: string) => call<Goods[]>(http.get(`${R}/carriers/${code}/goods`)),
  saveGoods: (code: string, goods: Goods[]) => call<Goods[]>(http.put(`${R}/carriers/${code}/goods`, goods)),
  findGoods: (sku: string) => call<StockedGoods[]>(http.get(`${R}/goods`, { params: { sku } })),
}

export const registryApi = {
  types: () => call<VehicleType[]>(http.get(`${R}/vehicle-types`)),
  createType: (t: VehicleType) => call<VehicleType>(http.post(`${R}/vehicle-types`, t)),
  deleteType: (code: string) => call<void>(http.delete(`${R}/vehicle-types/${code}`)),
  vehicles: () => call<RegisteredVehicle[]>(http.get(`${R}/vehicles`)),
  register: (v: Partial<RegisteredVehicle>) => call<RegisteredVehicle>(http.post(`${R}/vehicles`, v)),
  update: (v: Partial<RegisteredVehicle> & { code: string }) => call<RegisteredVehicle>(http.put(`${R}/vehicles/${v.code}`, v)),
  remove: (code: string) => call<void>(http.delete(`${R}/vehicles/${code}`)),
  history: (code: string, params: { from?: string; to?: string; limit?: number } = {}) =>
    call<VehicleSample[]>(http.get(`${R}/vehicles/${code}/history`, { params })),
}

const F = '/api/fleet/v1/vehicles'

export const fleetApi = {
  vehicles: () => call<VehicleStatus[]>(http.get(F)),
  pause: (code: string) => call<string>(http.post(`${F}/${code}/pause`)),
  resume: (code: string) => call<string>(http.post(`${F}/${code}/resume`)),
  cancelOrder: (code: string) => call<string>(http.post(`${F}/${code}/cancel-order`)),
  relocate: (code: string, body: { x: number; y: number; theta?: number; mapCode?: string; pointCode?: string }) =>
    call<string>(http.post(`${F}/${code}/relocate`, body)),
  forget: (code: string) => call<void>(http.delete(`${F}/${code}`)),
  messages: (code: string, params: { from?: string; to?: string; limit?: number } = {}) =>
    call<VehicleMessage[]>(http.get(`${F}/${code}/messages`, { params })),
}

const T = '/api/traffic/v1'

export const trafficApi = {
  routes: () => call<RouteView[]>(http.get(`${T}/routes`)),
  move: (vehicleCode: string, targetPoint: string) => call<RouteView>(http.post(`${T}/routes`, { vehicleCode, targetPoint })),
  cancel: (vehicleCode: string) => call<RouteView>(http.delete(`${T}/routes/${vehicleCode}`)),
  reservations: () => call<Reservation[]>(http.get(`${T}/reservations`)),
  settings: () => call<TrafficTunables>(http.get(`${T}/settings`)),
  saveSettings: (s: TrafficTunables) => call<TrafficTunables>(http.put(`${T}/settings`, s)),
}

const D = '/api/dispatch/v1'

export const dispatchApi = {
  orders: (status?: OrderStatus, limit = 100) => call<OrderView[]>(http.get(`${D}/orders`, { params: { status, limit } })),
  create: (order: CreateOrder) => call<OrderView>(http.post(`${D}/orders`, order)),
  cancel: (no: string) => call<OrderView>(http.post(`${D}/orders/${no}/cancel`)),
  suspend: (no: string) => call<OrderView>(http.post(`${D}/orders/${no}/suspend`)),
  resume: (no: string) => call<OrderView>(http.post(`${D}/orders/${no}/resume`)),
  vehicles: () => call<DispatchVehicle[]>(http.get(`${D}/vehicles`)),
  statistics: (hours = 24) => call<OrderStatistics>(http.get(`${D}/statistics`, { params: { hours } })),
  templates: () => call<OrderTemplate[]>(http.get(`${D}/templates`)),
  settings: () => call<DispatchTunables>(http.get(`${D}/settings`)),
  saveSettings: (s: DispatchTunables) => call<DispatchTunables>(http.put(`${D}/settings`, s)),
  saveTemplate: ({ code, ...body }: OrderTemplate) => call<OrderTemplate>(http.put(`${D}/templates/${code}`, body)),
  deleteTemplate: (code: string) => call<void>(http.delete(`${D}/templates/${code}`)),
  setAccepting: (code: string, accepting: boolean) => call<void>(http.put(`${D}/vehicles/${code}/accepting`, { accepting })),
}

export const authApi = {
  login: (username: string, password: string) => call<LoginResult>(http.post(`${R}/auth/login`, { username, password })),
  me: () => call<UserView>(http.get(`${R}/auth/me`)),
  changePassword: (currentPassword: string, newPassword: string) =>
    call<void>(http.put(`${R}/auth/me/password`, { currentPassword, newPassword })),
}

export const usersApi = {
  list: () => call<UserView[]>(http.get(`${R}/users`)),
  create: (u: { username: string; displayName: string; password: string; role: Role }) => call<UserView>(http.post(`${R}/users`, u)),
  update: (username: string, u: { displayName: string; role: Role; enabled: boolean }) =>
    call<UserView>(http.put(`${R}/users/${username}`, u)),
  resetPassword: (username: string, password: string) => call<void>(http.put(`${R}/users/${username}/password`, { password })),
  remove: (username: string) => call<void>(http.delete(`${R}/users/${username}`)),
}

export const alarmsApi = {
  list: (params: { open?: boolean; level?: AlarmLevel; vehicle?: string; limit?: number } = {}) =>
    call<Alarm[]>(http.get(`${R}/alarms`, { params })),
  summary: () => call<AlarmSummary>(http.get(`${R}/alarms/summary`)),
  acknowledge: (id: number) => call<Alarm>(http.post(`${R}/alarms/${id}/acknowledge`)),
  close: (id: number) => call<Alarm>(http.post(`${R}/alarms/${id}/close`)),
}

const S = '/api/simulator/v1'

/** Only present when fleetloom-simulator runs (development, demos, tests). */
export const simulatorApi = {
  vehicles: () => call<SimVehicle[]>(http.get(`${S}/vehicles`)),
  timeScale: (value: number) => call<number>(http.put(`${S}/time-scale`, { value })),
  injectError: (serial: string, body: { errorType: string; level: 'WARNING' | 'FATAL'; description?: string; seconds?: number }) =>
    call<SimVehicle>(http.post(`${S}/vehicles/${serial}/faults/error`, body)),
  obstacle: (serial: string, seconds: number) => call<SimVehicle>(http.post(`${S}/vehicles/${serial}/faults/obstacle`, { seconds })),
  disconnect: (serial: string, seconds: number) =>
    call<SimVehicle>(http.post(`${S}/vehicles/${serial}/faults/disconnect`, { seconds })),
  clearFaults: (serial: string) => call<SimVehicle>(http.delete(`${S}/vehicles/${serial}/faults`)),
  add: (vehicles: { serialNumber: string; initialPoint?: string; battery?: number; maxSpeed?: number }[]) =>
    call<SimVehicle[]>(http.post(`${S}/vehicles`, vehicles)),
  remove: (serial: string) => call<void>(http.delete(`${S}/vehicles/${serial}`)),
  mapPoints: () => call<string[]>(http.get(`${S}/map/points`)),
  startScenario: (yaml: string) =>
    call<ScenarioReport>(http.post(`${S}/scenarios`, yaml, { headers: { 'Content-Type': 'application/yaml' } })),
  scenario: () => call<ScenarioReport | null>(http.get(`${S}/scenarios/current`)),
  stopScenario: () => call<ScenarioReport>(http.delete(`${S}/scenarios/current`)),
}

export const auditApi = {
  find: (params: { user?: string; path?: string; failedOnly?: boolean; from?: string; to?: string; limit?: number }) =>
    call<AuditEntry[]>(http.get(`${R}/audit-logs`, { params })),
}
