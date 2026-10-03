<template>
  <div class="vc-grid" style="grid-template-columns: 380px 1fr;">
    <!-- 左：受影响车辆 -->
    <section class="vc-card">
      <div class="vc-card-title">
        故障工位 · 在检待处置车辆
        <el-button size="small" style="margin-left: auto;" @click="load">刷新</el-button>
      </div>
      <el-alert
        v-if="board.affectedTasks && board.affectedTasks.length"
        type="error" :closable="false" show-icon
        :title="'共 ' + board.affectedTasks.length + ' 辆车卡在故障工位，已完成的工位结果转线后原样保留'"
        style="margin-bottom: 10px;"
      />
      <el-alert
        v-else
        type="success" :closable="false" show-icon
        title="当前没有在检车辆卡在故障工位"
        style="margin-bottom: 10px;"
      />

      <el-scrollbar max-height="560px">
        <div
          v-for="t in board.affectedTasks || []"
          :key="t.taskId"
          class="vc-recover-item"
          :class="{ active: current && current.taskId === t.taskId, resolved: t.resolved }"
          @click="selectTask(t)"
        >
          <div class="vc-recover-plate">
            {{ t.plateNo }}
            <el-tag size="small" :type="t.status === 'RETEST' ? 'warning' : 'primary'">
              {{ t.statusName }}
            </el-tag>
            <el-tag v-if="t.resolved" size="small" type="success">已转 {{ t.resolvedToLineNo }}</el-tag>
          </div>
          <div class="vc-recover-meta">
            <span>{{ t.taskNo }}</span>
            <span v-if="t.retestTimes > 0">复检 × {{ t.retestTimes }}（原报告 {{ t.originReportNo || '-' }}）</span>
          </div>
          <div class="vc-recover-foot">
            <el-tag size="small" type="danger">{{ t.fromLineNo }} · {{ t.faultStationName }}故障</el-tag>
            <span class="vc-muted">已完成 {{ t.finishedCount }}/{{ t.totalCount }}，卡在第 {{ t.faultSeqNo }} 工位</span>
          </div>
        </div>
      </el-scrollbar>
    </section>

    <!-- 右：车辆详情 + 候选线 + 处置记录 -->
    <section v-if="current" style="display: grid; gap: 14px; align-content: start;">
      <div v-if="current" class="vc-card">
        <div class="vc-card-title">
          {{ current.plateNo }} · 跨线恢复评估
          <span class="vc-muted" style="font-weight: 400; font-size: 12px;">
            {{ current.ownerName }} ｜ {{ current.vehicleTypeName }} ｜ 引车员 {{ current.driverName }}
          </span>
        </div>

        <el-descriptions :column="4" size="small" border style="margin-bottom: 12px;">
          <el-descriptions-item label="检测任务">{{ current.taskNo }}</el-descriptions-item>
          <el-descriptions-item label="故障线">{{ current.fromLineNo }} {{ current.fromLineName }}</el-descriptions-item>
          <el-descriptions-item label="故障工位">
            <span class="vc-tag-fail">第 {{ current.faultSeqNo }} 工位 · {{ current.faultStationName }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">{{ current.statusName }}</el-descriptions-item>
          <el-descriptions-item v-if="current.originReportNo" label="原报告关联" :span="4">
            <el-tag size="small" type="warning">{{ current.originReportNo }}</el-tag>
            <span class="vc-muted" style="margin-left: 8px;">复检车辆转线仍关联原报告，剩余工位全部完成前不签发新报告</span>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 已完成检测链（转线后原样保留，含不合格） -->
        <div class="vc-chain-title">故障前检测链（已完成 {{ current.finishedCount }} 个工位，转线不重测、不洗白）</div>
        <div class="vc-chain">
          <div
            v-for="r in current.records"
            :key="r.id"
            class="vc-chain-node"
            :class="r.result === 'PASS' ? 'pass' : 'fail'"
          >
            <div class="vc-chain-name">{{ r.seqNo }}. {{ r.stationName }}</div>
            <div class="vc-chain-result">{{ r.resultName }}</div>
            <div class="vc-chain-val">{{ r.valueText }}</div>
            <div class="vc-chain-op">{{ r.operatorName }} ｜ {{ fmtTime(r.recordTime) }}</div>
          </div>
          <div class="vc-chain-node doing">
            <div class="vc-chain-name">{{ current.faultSeqNo }}. {{ current.faultStationName }}</div>
            <div class="vc-chain-result">故障待转线</div>
            <div class="vc-chain-val">从目标线第 {{ current.faultSeqNo }} 工位继续，后续 {{ current.totalCount - current.faultSeqNo + 1 }} 个工位在新线完成</div>
          </div>
        </div>
      </div>

      <!-- 候选检测线 -->
      <div v-if="current" class="vc-card">
        <div class="vc-card-title">
          候选检测线
          <span class="vc-muted" style="font-weight: 400; font-size: 12px;">
            硬闸门：线运行 → 承接车型 → 剩余工位顺序一致 → 接续工位实时空闲 → 设备标定有效；负载只在全部通过后排优先级
          </span>
          <el-button size="small" style="margin-left: auto;" @click="refreshCurrent">重新评估</el-button>
        </div>

        <el-alert
          v-if="eligibleLines.length === 0"
          type="warning" :closable="false" show-icon
          title="没有可用目标线：车辆保持待处置，保留故障前进度；故障解除后可回原线继续"
          style="margin-bottom: 10px;"
        />

        <div
          v-for="c in current.candidates"
          :key="c.lineId"
          class="vc-cand"
          :class="{ ok: c.eligible, ban: !c.eligible, rec: c.recommended }"
        >
          <div class="vc-cand-head">
            <div class="vc-cand-line">
              <b>{{ c.lineNo }} · {{ c.lineName }}</b>
              <el-tag size="small" type="info" effect="plain">{{ c.lineTypeName }}</el-tag>
              <el-tag size="small" :type="c.lineStatus === 'RUNNING' ? 'success' : 'danger'">
                {{ c.lineStatusName }}
              </el-tag>
              <el-tag v-if="c.recommended" size="small" type="success">★ 系统推荐</el-tag>
            </div>
            <div class="vc-cand-side">
              <template v-if="c.eligible">
                <el-tag size="small" type="success">可承接</el-tag>
                <el-button
                  size="small" type="primary"
                  :disabled="current.resolved"
                  @click="openConfirm(c)"
                >转至 {{ c.lineNo }}</el-button>
              </template>
              <el-tag v-else size="small" type="danger">已排除</el-tag>
            </div>
          </div>

          <div class="vc-cand-metrics">
            <span>承接车型：{{ c.acceptVehicleTypeNames }}</span>
            <span>接续工位：{{ c.targetStationId ? '第 ' + current.faultSeqNo + ' 工位 · ' + deviceStationName(c) + '（' + c.targetStationStatusName + '）' : '无' }}</span>
            <span>接续后排队：<b :class="c.eligible ? 'vc-tag-pass' : ''">{{ c.targetQueueCount ?? '-' }} 台</b></span>
            <span>整线在检负载：<b>{{ c.lineLoad }} 台</b></span>
          </div>

          <!-- 逐工位设备适配/标定 -->
          <el-table :data="c.deviceChecks" size="small" border style="margin: 8px 0;">
            <el-table-column label="序" prop="seqNo" width="46" />
            <el-table-column label="工位" prop="stationName" width="70" />
            <el-table-column label="设备" min-width="150">
              <template #default="{ row }">
                {{ row.deviceName ? row.deviceName + '（' + row.model + '）' : (row.stationType === 'LOGIN' || row.stationType === 'FINAL' ? '无需设备' : '未配置') }}
              </template>
            </el-table-column>
            <el-table-column label="下次标定" width="110">
              <template #default="{ row }">{{ row.nextCalDate || '-' }}</template>
            </el-table-column>
            <el-table-column label="标定" width="118">
              <template #default="{ row }">
                <el-tag size="small" :type="levelType(row.level)">{{ row.levelName }}</el-tag>
              </template>
            </el-table-column>
          </el-table>

          <div class="vc-cand-reasons">
            <div v-for="(r, i) in c.reasons" :key="i" class="vc-reason"
                 :class="c.eligible ? 'good' : 'bad'">
              <span>{{ c.eligible ? '✓' : '✕' }}</span>{{ r }}
            </div>
          </div>
        </div>
      </div>

      <!-- 处置记录 -->
      <div v-if="current" class="vc-card">
        <div class="vc-card-title">本车处置痕迹</div>
        <el-timeline>
          <el-timeline-item
            v-for="h in transfersOfCurrent"
            :key="h.id"
            :type="h.result === 'SUCCESS' ? 'success' : (h.result === 'FAILED' ? 'danger' : 'warning')"
            :timestamp="fmtTime(h.transferTime)"
            placement="top"
          >
            <el-tag size="small"
              :type="h.result === 'SUCCESS' ? 'success' : (h.result === 'FAILED' ? 'danger' : 'warning')">
              {{ h.resultName }}
            </el-tag>
            <span style="margin-left: 6px;">
              {{ h.fromLineNo }} → {{ h.toLineNo || '未转线' }}
              <span v-if="h.targetStationName">（接续：{{ h.targetStationName }}）</span>
              ｜处置人 {{ h.operatorName }}
            </span>
            <div v-if="h.failReason" class="vc-muted">{{ h.failReason }}</div>
          </el-timeline-item>
          <el-timeline-item v-if="!transfersOfCurrent.length" type="info" placement="top">
            暂无处置记录
          </el-timeline-item>
        </el-timeline>
        <el-button size="small" :disabled="current.resolved" @click="openPending">
          无可用目标 · 保持待处置（回原线等故障解除）
        </el-button>
      </div>
    </section>

    <el-empty v-else description="请在左侧选择一辆故障工位上的在检车辆" />

    <!-- 确认转线 -->
    <el-dialog v-model="confirmVisible"
      :title="'确认跨线恢复 → ' + (target?.lineNo || '') + ' · ' + (target?.lineName || '')"
      width="520px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px;"
        title="提交瞬间会再次复核目标工位实时占用与设备标定；若另一值班人员已先行处置或工位刚被占用，本次将失败并提示最新去向。" />
      <el-form label-width="100px">
        <el-form-item label="车辆">
          <b>{{ current?.plateNo }}</b>（{{ current?.taskNo }}）
        </el-form-item>
        <el-form-item label="接续方式">
          从目标线第 <b>{{ current?.faultSeqNo }}</b> 工位「{{ current?.faultStationName }}」继续，
          已完成 {{ current?.finishedCount }} 个工位结果原样保留
        </el-form-item>
        <el-form-item label="目标负载">
          接续后排队 {{ target?.targetQueueCount }} 台 / 整线在检 {{ target?.lineLoad }} 台
        </el-form-item>
        <el-form-item label="处置值班人">
          <el-input v-model="operatorName" placeholder="如 值班甲" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="confirmVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitTransfer">确认转线</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchRecoveryBoard,
  fetchAffectedTask,
  confirmTransfer,
  keepPending
} from '../api'

const pipeline = inject('pipeline', null)

const board = ref({ affectedTasks: [], recentTransfers: [] })
const current = ref(null)
const confirmVisible = ref(false)
const target = ref(null)
const operatorName = ref('值班甲')
const submitting = ref(false)
let timer = null

const eligibleLines = computed(() =>
  current.value ? current.value.candidates.filter((c) => c.eligible) : [])

const transfersOfCurrent = computed(() =>
  (board.value.recentTransfers || []).filter((h) => h.taskId === current.value?.taskId))

function fmtTime (t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : ''
}

function deviceStationName (c) {
  const station = c.deviceChecks && c.deviceChecks[0]
  return station ? station.stationName : current.value.faultStationName
}

function levelType (level) {
  if (level === 'OK') return 'success'
  if (level === 'DUE') return 'warning'
  if (level === 'OVERDUE') return 'danger'
  return 'info'
}

async function load () {
  const fresh = await fetchRecoveryBoard()
  const prevId = current.value?.taskId
  board.value = fresh
  if (prevId && fresh.affectedTasks.some((t) => t.taskId === prevId)) {
    current.value = fresh.affectedTasks.find((t) => t.taskId === prevId)
  } else if (prevId) {
    // 车辆可能已故障解除离台 —— 拉详情会告知最新情况；失败则清空
    try {
      current.value = await fetchAffectedTask(prevId)
    } catch (e) {
      current.value = null
    }
  }
  // 处置台数据变化（转线/待处置/故障解除）同步刷新底部流水线占用徽章
  pipeline?.reloadAll?.()
}

async function selectTask (t) {
  try {
    current.value = await fetchAffectedTask(t.taskId)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function refreshCurrent () {
  if (!current.value) return
  try {
    current.value = await fetchAffectedTask(current.value.taskId)
    ElMessage.success('已按最新工位占用/排队/标定情况重新评估')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openConfirm (c) {
  target.value = c
  confirmVisible.value = true
}

async function submitTransfer () {
  if (!current.value || !target.value) return
  submitting.value = true
  try {
    const res = await confirmTransfer(current.value.taskId, {
      targetLineId: target.value.lineId,
      operatorName: operatorName.value || '值班员'
    })
    ElMessage.success(`转线成功：${res.fromLineNo} → ${res.toLineNo}，自${res.targetStationName}继续`)
    confirmVisible.value = false
    await load()
    if (current.value) {
      current.value = await fetchAffectedTask(current.value.taskId).catch(() => current.value)
    }
  } catch (e) {
    // 并发失败 / 确认瞬间被占用：强提示最新去向，随后强制刷新
    ElMessageBox.alert(e.message, '本次转线未生效', { type: 'warning' })
    await load()
  } finally {
    submitting.value = false
  }
}

async function openPending () {
  try {
    await ElMessageBox.confirm(
      '车辆将保持待处置，故障前进度与检测结果全部保留；故障解除后可直接回原线从故障工位继续。确认登记？',
      '保持待处置', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await keepPending(current.value.taskId, { operatorName: operatorName.value || '值班员', remark: '' })
    ElMessage.success('已登记待处置')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
    await load()
  }
}

onMounted(async () => {
  try {
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
  // 处置台常驻：每 8 秒拉一次最新占用/排队/去向（其他人可能正在处置）
  timer = setInterval(load, 8000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.vc-recover-item {
  border: 1px solid var(--vc-border);
  border-radius: 6px;
  padding: 8px 10px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.18s ease;
}
.vc-recover-item:hover { border-color: var(--vc-primary); background: #f5f9fd; }
.vc-recover-item.active {
  border-color: var(--vc-primary); background: #eaf2fa;
  box-shadow: inset 3px 0 0 var(--vc-primary);
}
.vc-recover-item.resolved { opacity: 0.75; }
.vc-recover-plate { font-size: 16px; font-weight: 700; color: var(--vc-primary-deep); display: flex; gap: 6px; align-items: center; }
.vc-recover-meta { display: flex; justify-content: space-between; font-size: 12px; color: var(--vc-muted); margin: 3px 0; }
.vc-recover-foot { display: flex; align-items: center; gap: 8px; font-size: 12px; }

.vc-chain-title { font-weight: 700; color: var(--vc-primary-deep); margin: 6px 0 8px; font-size: 13px; }
.vc-chain { display: flex; gap: 8px; flex-wrap: wrap; }
.vc-chain-node {
  flex: 1 1 150px; min-width: 150px; border: 1px solid var(--vc-border);
  border-radius: 6px; padding: 8px 10px; background: #f8fafc;
}
.vc-chain-node.pass { border-left: 3px solid var(--vc-pass); }
.vc-chain-node.fail { border-left: 3px solid var(--vc-fail); background: #fdf3f2; }
.vc-chain-node.doing { border-left: 3px dashed var(--vc-fail); background: #fff6ec; }
.vc-chain-name { font-weight: 700; font-size: 13px; }
.vc-chain-result { font-size: 12px; margin: 2px 0; }
.vc-chain-node.pass .vc-chain-result { color: var(--vc-pass); }
.vc-chain-node.fail .vc-chain-result { color: var(--vc-fail); }
.vc-chain-node.doing .vc-chain-result { color: var(--vc-warn); }
.vc-chain-val { font-size: 12px; color: #42576d; }
.vc-chain-op { font-size: 11px; color: var(--vc-muted); margin-top: 2px; }

.vc-cand {
  border: 1px solid var(--vc-border); border-radius: 8px; padding: 10px 12px;
  margin-bottom: 10px; background: #fafbfd;
}
.vc-cand.ok { border-color: #b7dcc9; background: #f6fbf8; }
.vc-cand.ban { border-color: #e6c4c2; background: #fdf7f6; }
.vc-cand.rec { box-shadow: 0 0 0 2px rgba(26, 156, 91, 0.18); }
.vc-cand-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.vc-cand-line { display: flex; align-items: center; gap: 8px; }
.vc-cand-side { display: flex; align-items: center; gap: 8px; }
.vc-cand-metrics { display: flex; flex-wrap: wrap; gap: 14px; font-size: 12px; color: #42576d; margin-top: 6px; }
.vc-cand-reasons { margin-top: 4px; }
.vc-reason { font-size: 12px; margin: 2px 0; }
.vc-reason.good { color: var(--vc-pass); }
.vc-reason.bad { color: var(--vc-fail); }
</style>
