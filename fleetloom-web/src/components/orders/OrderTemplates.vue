<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { dispatchApi } from '@/api'
import type { OrderTemplate } from '@/api/types'
import { act, confirm } from '@/composables/notify'

/** Order templates (DSP-03), edited in a drawer next to the order list. */
const open = defineModel<boolean>({ required: true })
const emit = defineEmits<{ changed: [] }>()

const templates = ref<OrderTemplate[]>([])
const ACTIONS = ['pick', 'drop', 'lift', 'lower', 'finePositioning', 'detectObject']

async function load() {
  templates.value = await dispatchApi.templates()
}

watch(open, (o) => o && load())

const dialog = reactive({ open: false, editing: false })
const form = reactive<OrderTemplate>({ code: '', name: '', pickAction: 'pick', dropAction: 'drop', viaPoints: [], viaAction: null, description: null })

function edit(t?: OrderTemplate) {
  Object.assign(dialog, { open: true, editing: !!t })
  Object.assign(form, t ? { ...t, viaPoints: [...t.viaPoints] }
    : { code: '', name: '', pickAction: 'pick', dropAction: 'drop', viaPoints: [], viaAction: null, description: null })
}

async function save() {
  if (await act(() => dispatchApi.saveTemplate({ ...form, code: form.code.trim().toUpperCase() }), '已保存')) {
    dialog.open = false
    await load()
    emit('changed')
  }
}

async function remove(t: OrderTemplate) {
  if (await confirm(`删除订单模板 ${t.code}？已创建的订单不受影响。`)) {
    await act(() => dispatchApi.deleteTemplate(t.code), '已删除')
    await load()
    emit('changed')
  }
}

function steps(t: OrderTemplate): string {
  const parts = [`源库位${t.pickAction ? ' ' + t.pickAction : ''}`]
  t.viaPoints.forEach((p) => parts.push(`${p}${t.viaAction ? ' ' + t.viaAction : ''}`))
  parts.push(`目标库位${t.dropAction ? ' ' + t.dropAction : ''}`)
  return parts.join(' → ')
}
</script>

<template>
  <el-drawer v-model="open" title="订单模板" size="640px">
    <div class="toolbar">
      <span class="hint">搬运订单按模板拆分步骤：在源库位做什么动作、途经哪些点、在目标库位做什么动作。</span>
      <span class="spacer" />
      <el-button type="primary" @click="edit()">新建模板</el-button>
    </div>
    <el-table :data="templates" size="small" stripe>
      <el-table-column prop="code" label="编码" width="110" />
      <el-table-column prop="name" label="名称" width="110" />
      <el-table-column label="步骤" min-width="220">
        <template #default="{ row }">
          {{ steps(row) }}
          <div v-if="row.description" class="hint">{{ row.description }}</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button link type="primary" @click="edit(row)">编辑</el-button>
          <el-button v-if="row.code !== 'STANDARD'" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog.open" :title="dialog.editing ? '编辑订单模板' : '新建订单模板'" width="460px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="编码"><el-input v-model="form.code" :disabled="dialog.editing" placeholder="大写字母、数字、_ -" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="取货动作">
          <el-select v-model="form.pickAction" allow-create filterable clearable placeholder="无动作，只停靠" style="width: 100%">
            <el-option v-for="a in ACTIONS" :key="a" :label="a" :value="a" />
          </el-select>
        </el-form-item>
        <el-form-item label="途经点位">
          <el-select v-model="form.viaPoints" multiple allow-create filterable placeholder="可选，按顺序经过" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="form.viaPoints.length" label="途经动作">
          <el-select v-model="form.viaAction" allow-create filterable clearable placeholder="无动作" style="width: 100%">
            <el-option v-for="a in ACTIONS" :key="a" :label="a" :value="a" />
          </el-select>
        </el-form-item>
        <el-form-item label="放货动作">
          <el-select v-model="form.dropAction" allow-create filterable clearable placeholder="无动作，只停靠" style="width: 100%">
            <el-option v-for="a in ACTIONS" :key="a" :label="a" :value="a" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.open = false">取消</el-button>
        <el-button type="primary" :disabled="!form.code || !form.name" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
