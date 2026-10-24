<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { dispatchApi, trafficApi } from '@/api'
import type { DispatchTunables, TrafficTunables } from '@/api/types'
import { act } from '@/composables/notify'

/** Dispatch and traffic parameters (WEB-09); they apply at once and survive restarts. */
const dispatch = reactive<DispatchTunables>({ defaultPriority: 5, parkAfterIdleSeconds: 30 })
const traffic = reactive<TrafficTunables>({ lookaheadNodes: 3, lookaheadDistance: 5, occupiedPenalty: 30, blockedEventAfterSeconds: 5, replanAfterSeconds: 10 })
const unavailable = ref<string[]>([])

onMounted(async () => {
  const [d, t] = await Promise.allSettled([dispatchApi.settings(), trafficApi.settings()])
  if (d.status === 'fulfilled') Object.assign(dispatch, d.value)
  else unavailable.value.push('调度服务')
  if (t.status === 'fulfilled') Object.assign(traffic, t.value)
  else unavailable.value.push('交通管制服务')
})

async function saveDispatch() {
  const saved = await act(() => dispatchApi.saveSettings({ ...dispatch }), '调度参数已保存')
  if (saved) Object.assign(dispatch, saved)
}

async function saveTraffic() {
  const saved = await act(() => trafficApi.saveSettings({ ...traffic }), '交通参数已保存')
  if (saved) Object.assign(traffic, saved)
}
</script>

<template>
  <div class="page settings">
    <el-alert v-if="unavailable.length" type="warning" :closable="false" :title="`暂时读不到：${unavailable.join('、')}，显示的是默认值`" />
    <p class="hint">修改立即生效并保存，服务重启后仍然有效。车辆的充电阈值在「车辆 → 车辆档案」里按车设置。</p>

    <el-card shadow="never" header="调度">
      <el-form label-width="200px" data-testid="dispatch-settings">
        <el-form-item label="默认订单优先级">
          <el-input-number v-model="dispatch.defaultPriority" :min="1" :max="9" />
          <span class="unit">1 最低，9 最高；下单时未指定优先级的订单使用它</span>
        </el-form-item>
        <el-form-item label="空闲多久后去停靠点">
          <el-input-number v-model="dispatch.parkAfterIdleSeconds" :min="0" :max="86400" />
          <span class="unit">秒；0 = 不自动停靠</span>
        </el-form-item>
        <el-form-item><el-button type="primary" @click="saveDispatch">保存调度参数</el-button></el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" header="交通管制">
      <el-form label-width="200px" data-testid="traffic-settings">
        <el-form-item label="前方至少预约点数">
          <el-input-number v-model="traffic.lookaheadNodes" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="前方至少预约距离">
          <el-input-number v-model="traffic.lookaheadDistance" :min="0" :max="100" :step="0.5" />
          <span class="unit">米；越大车辆越少减速，但占用的路段越多</span>
        </el-form-item>
        <el-form-item label="规划时被占资源的代价">
          <el-input-number v-model="traffic.occupiedPenalty" :min="0" :max="3600" />
          <span class="unit">秒；越大越倾向绕开别的车</span>
        </el-form-item>
        <el-form-item label="等待多久报告受阻">
          <el-input-number v-model="traffic.blockedEventAfterSeconds" :min="1" :max="3600" />
          <span class="unit">秒；受阻会产生告警，并让挡路的空闲车让开</span>
        </el-form-item>
        <el-form-item label="等待多久尝试绕行">
          <el-input-number v-model="traffic.replanAfterSeconds" :min="1" :max="3600" />
          <span class="unit">秒</span>
        </el-form-item>
        <el-form-item><el-button type="primary" @click="saveTraffic">保存交通参数</el-button></el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 900px;
}
.hint,
.unit {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.hint {
  margin: 0;
}
.unit {
  margin-left: 12px;
}
</style>
