<template>
  <div class="vc-grid" style="grid-template-columns: 440px 1fr;">
    <!-- 左：A4 比例报告卡 -->
    <section class="vc-card">
      <div class="vc-card-title">
        检测报告预览
        <el-select
          v-model="reportId"
          size="small"
          style="width: 200px; margin-left: auto;"
          @change="loadReport"
        >
          <el-option v-for="r in reports" :key="r.id" :label="r.reportNo + ' · ' + r.plateNo" :value="r.id" />
        </el-select>
      </div>

      <div class="vc-a4">
        <div class="vc-a4-head">
          <div class="vc-a4-org">机动车安全技术检验报告</div>
          <div class="vc-a4-no">{{ report?.reportNo || '-' }}</div>
        </div>

        <div class="vc-a4-plate">{{ report?.plateNo || '-' }}</div>
        <div class="vc-a4-sub">
          {{ report?.ownerName || '-' }} ｜ {{ report?.vehicleTypeName || '-' }} ｜ 检测线
          {{ report?.lineNo || '-' }}
        </div>

        <div class="vc-a4-stamp" :class="report?.conclusion === 'PASS' ? 'pass' : 'fail'">
          {{ report?.conclusionName || '-' }}
        </div>

        <div class="vc-a4-rows">
          <div class="vc-a4-row"><span>任务号</span><b>{{ report?.taskNo || '-' }}</b></div>
          <div class="vc-a4-row"><span>签发人</span><b>{{ report?.signer || '-' }}</b></div>
          <div class="vc-a4-row"><span>签发日期</span><b>{{ report?.signDate || '-' }}</b></div>
          <div class="vc-a4-row"><span>复检关联</span><b>{{ report?.retestOf || '无' }}</b></div>
        </div>

        <div class="vc-a4-snap">
          <div class="vc-a4-snap-title">各分项结果快照</div>
          <div v-for="s in snapshotLines" :key="s" class="vc-a4-snap-line">{{ s }}</div>
        </div>

        <div class="vc-a4-fail" v-if="report?.failItems?.length">
          <b>不合格项：</b>
          <span v-for="f in report.failItems" :key="f" class="vc-a4-fail-item">{{ f }}</span>
        </div>
      </div>
    </section>

    <!-- 右：判定徽章墙 + 待签发任务 -->
    <section style="display: grid; gap: 14px; align-content: start;">
      <div class="vc-card">
        <div class="vc-card-title">
          判定徽章墙
          <span class="vc-muted" style="font-weight: 400; font-size: 12px;">
            合格绿 / 不合格红，点击不合格徽章可安排复检
          </span>
        </div>
        <div class="vc-badges">
          <div
            v-for="b in report?.badges || []"
            :key="b.code"
            class="vc-badge"
            :class="b.result === 'PASS' ? 'pass' : (b.result === 'FAIL' ? 'fail' : 'pending')"
            @click="onBadge(b)"
          >
            <div class="vc-badge-ring">
              <span class="vc-badge-mark">{{ b.result === 'PASS' ? '✓' : (b.result === 'FAIL' ? '✕' : '?') }}</span>
            </div>
            <div class="vc-badge-name">{{ b.name }}</div>
            <div class="vc-badge-val">{{ b.valueText }}</div>
            <div v-if="b.keyItem" class="vc-badge-key">关键项</div>
          </div>
        </div>
      </div>

      <div class="vc-card">
        <div class="vc-card-title">待签发任务（已完成全部工位）</div>
        <el-table :data="issuable" size="small" height="220">
          <el-table-column prop="taskNo" label="任务号" width="150" />
          <el-table-column prop="plateNo" label="车牌" width="120" />
          <el-table-column prop="lineNo" label="线号" width="70" />
          <el-table-column label="结论" width="90">
            <template #default="{ row }">
              <span :class="row.status === 'PASS' ? 'vc-tag-pass' : 'vc-tag-fail'">{{ row.statusName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click="issue(row)">签发报告</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="vc-card">
        <div class="vc-card-title">不合格项排行</div>
        <el-table :data="failRank" size="small" height="180">
          <el-table-column prop="stationName" label="工位" width="80" />
          <el-table-column prop="itemName" label="不合格项" />
          <el-table-column prop="failCount" label="次数" width="70" />
        </el-table>
      </div>
    </section>
  </div>

  <!-- 安排复检 -->
  <el-dialog v-model="retestVisible" title="安排复检" width="460px">
    <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px;">
      {{ selectedBadge?.name }} 不合格：{{ selectedBadge?.valueText }}。提交后该车辆将被送回流水线起点重新检测。
    </el-alert>
    <el-form label-width="110px">
      <el-form-item label="引车员">
        <el-select v-model="retestForm.driverId" clearable style="width: 100%;">
          <el-option v-for="d in drivers" :key="d.id" :label="d.driverName" :value="d.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="排班时段">
        <el-select v-model="retestForm.scheduleHour" clearable style="width: 100%;">
          <el-option v-for="h in HOURS" :key="h" :label="h + ':00'" :value="h" />
        </el-select>
      </el-form-item>
      <el-form-item label="复检日期">
        <el-date-picker v-model="retestForm.checkDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="retestVisible = false">取消</el-button>
      <el-button type="danger" @click="submitRetest">安排复检</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fetchReports,
  fetchReport,
  issueReport,
  retestReport,
  fetchTasks,
  fetchFailRank,
  fetchDrivers
} from '../api'

const HOURS = [8, 9, 10, 11, 12, 13, 14, 15, 16, 17]

const reports = ref([])
const report = ref(null)
const reportId = ref(null)
const issuable = ref([])
const failRank = ref([])
const drivers = ref([])

const retestVisible = ref(false)
const selectedBadge = ref(null)
const retestForm = reactive({ driverId: null, scheduleHour: 9, checkDate: null })

const snapshotLines = computed(() =>
  (report.value?.snapshot || '').split(' | ').filter(Boolean)
)

async function loadReports () {
  reports.value = await fetchReports()
  if (!reportId.value && reports.value.length) {
    reportId.value = reports.value[0].id
  }
  if (reportId.value) {
    await loadReport()
  }
}

async function loadReport () {
  if (!reportId.value) return
  report.value = await fetchReport(reportId.value)
}

async function loadIssuable () {
  const pass = await fetchTasks('PASS')
  const fail = await fetchTasks('FAIL')
  issuable.value = [...pass, ...fail]
}

function onBadge (badge) {
  if (badge.result !== 'FAIL') return
  selectedBadge.value = badge
  retestForm.checkDate = new Date().toISOString().slice(0, 10)
  retestVisible.value = true
}

async function submitRetest () {
  try {
    const task = await retestReport(report.value.id, { ...retestForm })
    retestVisible.value = false
    ElMessage.success('已安排复检：' + task.taskNo + '，车辆 ' + task.plateNo + ' 已送回流水线起点')
    await loadIssuable()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function issue (row) {
  try {
    const created = await issueReport(row.id, { signer: '授权签字人 何工' })
    ElMessage.success('报告已签发：' + created.reportNo)
    await loadReports()
    await loadIssuable()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  drivers.value = await fetchDrivers()
  failRank.value = await fetchFailRank()
  await loadReports()
  await loadIssuable()
})
</script>

<style scoped>
.vc-a4 {
  position: relative;
  width: 100%;
  aspect-ratio: 1 / 1.414;
  border: 1px solid #c9d6e2;
  border-radius: 4px;
  background: #fff;
  padding: 26px 28px;
  box-shadow: 0 10px 26px rgba(20, 45, 75, 0.14);
  overflow: hidden;
}

.vc-a4::before {
  content: '';
  position: absolute;
  inset: 10px;
  border: 1px solid #e3ebf2;
  pointer-events: none;
}

.vc-a4-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  border-bottom: 2px solid var(--vc-primary);
  padding-bottom: 8px;
}

.vc-a4-org {
  font-size: 15px;
  font-weight: 800;
  color: var(--vc-primary-deep);
  letter-spacing: 2px;
}

.vc-a4-no {
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 13px;
  color: #54677d;
}

.vc-a4-plate {
  margin-top: 18px;
  font-size: 30px;
  font-weight: 800;
  letter-spacing: 2px;
  color: #17324a;
}

.vc-a4-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--vc-muted);
}

.vc-a4-stamp {
  position: absolute;
  right: 26px;
  top: 92px;
  width: 96px;
  height: 96px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 800;
  letter-spacing: 2px;
  transform: rotate(-12deg);
}

.vc-a4-stamp.pass {
  border: 4px solid var(--vc-pass);
  color: var(--vc-pass);
  background: rgba(26, 156, 91, 0.07);
}

.vc-a4-stamp.fail {
  border: 4px solid var(--vc-fail);
  color: var(--vc-fail);
  background: rgba(216, 52, 44, 0.07);
}

.vc-a4-rows {
  margin-top: 26px;
  border-top: 1px solid #e3ebf2;
  padding-top: 10px;
}

.vc-a4-row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  padding: 4px 0;
}

.vc-a4-row span {
  color: var(--vc-muted);
}

.vc-a4-snap {
  margin-top: 14px;
  border: 1px dashed #cfdae5;
  border-radius: 6px;
  padding: 10px 12px;
  background: #fafcfe;
}

.vc-a4-snap-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--vc-primary-deep);
  margin-bottom: 6px;
}

.vc-a4-snap-line {
  font-size: 12px;
  color: #40566d;
  padding: 2px 0;
}

.vc-a4-fail {
  margin-top: 12px;
  font-size: 12px;
  color: var(--vc-fail);
}

.vc-a4-fail-item {
  display: inline-block;
  background: #fdeceb;
  border: 1px solid #f5c6c3;
  border-radius: 10px;
  padding: 1px 8px;
  margin: 2px 4px 0 0;
}

/* 徽章墙 */
.vc-badges {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
}

.vc-badge {
  border: 1px solid var(--vc-border);
  border-radius: 10px;
  padding: 14px 8px 10px;
  text-align: center;
  cursor: default;
  transition: all 0.2s ease;
  background: #fff;
}

.vc-badge.fail {
  cursor: pointer;
  border-color: #f0b8b4;
  background: #fff7f6;
}

.vc-badge.fail:hover {
  transform: translateY(-3px);
  box-shadow: 0 10px 18px rgba(216, 52, 44, 0.18);
}

.vc-badge-ring {
  width: 58px;
  height: 58px;
  border-radius: 50%;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
}

.vc-badge.pass .vc-badge-ring {
  border: 3px solid var(--vc-pass);
  background: radial-gradient(circle, #eafaf1 0%, #d6f2e3 100%);
}

.vc-badge.fail .vc-badge-ring {
  border: 3px solid var(--vc-fail);
  background: radial-gradient(circle, #fdeceb 0%, #f9d5d2 100%);
  animation: vc-shake 2.4s infinite;
}

.vc-badge.pending .vc-badge-ring {
  border: 3px solid #c2cedb;
  background: #f4f6f8;
}

.vc-badge-mark {
  font-size: 26px;
  font-weight: 800;
}

.vc-badge.pass .vc-badge-mark {
  color: var(--vc-pass);
}

.vc-badge.fail .vc-badge-mark {
  color: var(--vc-fail);
}

.vc-badge.pending .vc-badge-mark {
  color: #9fb0c2;
}

.vc-badge-name {
  margin-top: 8px;
  font-size: 15px;
  font-weight: 700;
}

.vc-badge-val {
  margin-top: 3px;
  font-size: 11px;
  color: var(--vc-muted);
  min-height: 28px;
}

.vc-badge-key {
  display: inline-block;
  margin-top: 4px;
  font-size: 11px;
  color: #fff;
  background: var(--vc-primary);
  border-radius: 8px;
  padding: 0 8px;
}

@keyframes vc-shake {
  0%, 92%, 100% { transform: rotate(0deg); }
  94% { transform: rotate(-6deg); }
  96% { transform: rotate(6deg); }
  98% { transform: rotate(-3deg); }
}
</style>
