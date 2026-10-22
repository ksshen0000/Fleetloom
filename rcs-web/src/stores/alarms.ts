import { ref } from 'vue'
import { defineStore } from 'pinia'
import { alarmsApi } from '@/api'
import type { AlarmSummary } from '@/api/types'

/** Open alarm counts for the menu badge; refreshed whenever an alarm changes. */
export const useAlarms = defineStore('alarms', () => {
  const summary = ref<AlarmSummary>({ open: 0, unacknowledged: 0, byLevel: {} })

  async function refresh() {
    summary.value = await alarmsApi.summary()
  }

  return { summary, refresh }
})
