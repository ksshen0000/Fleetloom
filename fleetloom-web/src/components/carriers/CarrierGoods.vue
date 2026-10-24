<script setup lang="ts">
import { ref, watch } from 'vue'
import { carriersApi } from '@/api'
import type { Goods } from '@/api/types'
import { act } from '@/composables/notify'

/** What is on one carrier (CAR-05); recorded for lookup, dispatching does not use it. */
const open = defineModel<boolean>({ required: true })
const props = defineProps<{ carrierCode: string; readonly?: boolean }>()

const lines = ref<Goods[]>([])

watch([open, () => props.carrierCode], async ([o]) => {
  if (o) lines.value = await carriersApi.goods(props.carrierCode)
})

function add() {
  lines.value.push({ sku: '', name: null, quantity: 1, unit: null, batchNo: null })
}

async function save() {
  const valid = lines.value.filter((l) => l.sku.trim())
  const saved = await act(() => carriersApi.saveGoods(props.carrierCode, valid), '已保存')
  if (saved) lines.value = saved
}
</script>

<template>
  <el-drawer v-model="open" :title="`${carrierCode} 上的货物`" size="720px">
    <el-table :data="lines" size="small" data-testid="goods-lines">
      <el-table-column label="SKU" min-width="130">
        <template #default="{ row }"><el-input v-model="row.sku" size="small" :disabled="readonly" placeholder="必填" /></template>
      </el-table-column>
      <el-table-column label="名称" min-width="130">
        <template #default="{ row }"><el-input v-model="row.name" size="small" :disabled="readonly" /></template>
      </el-table-column>
      <el-table-column label="数量" width="130">
        <template #default="{ row }">
          <el-input-number v-model="row.quantity" size="small" :min="0" :precision="3" :step="1" controls-position="right" :disabled="readonly" style="width: 110px" />
        </template>
      </el-table-column>
      <el-table-column label="单位" width="80">
        <template #default="{ row }"><el-input v-model="row.unit" size="small" :disabled="readonly" /></template>
      </el-table-column>
      <el-table-column label="批次" width="110">
        <template #default="{ row }"><el-input v-model="row.batchNo" size="small" :disabled="readonly" /></template>
      </el-table-column>
      <el-table-column v-if="!readonly" width="50">
        <template #default="{ $index }"><el-button link type="danger" @click="lines.splice($index, 1)">删</el-button></template>
      </el-table-column>
    </el-table>
    <div v-if="!readonly" class="toolbar actions">
      <el-button size="small" @click="add">添加一行</el-button>
      <span class="spacer" />
      <el-button size="small" type="primary" @click="save">保存</el-button>
    </div>
  </el-drawer>
</template>

<style scoped>
.actions {
  margin-top: 12px;
}
</style>
