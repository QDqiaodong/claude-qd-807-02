import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { nextTick, ref } from 'vue'
import FlowView from '../views/FlowView.vue'
import { fetchTask, submitStationResult } from '../api'

// ---- 任务夹具：任务 1 在 L01，任务 2 在 L02（多线并行场景） ----
function makeTask (id, lineId, lineNo, plate, seq) {
  return {
    id,
    taskNo: 'TASK-20261003-00' + id,
    vehicleId: 10 + id,
    plateNo: plate,
    lineId,
    lineNo,
    currentStationId: (lineId - 1) * 7 + seq,
    currentStationType: 'LOGIN',
    currentStationName: '登录',
    currentSeqNo: seq,
    status: 'CHECKING',
    statusName: '在检',
    driverName: '张引车',
    ownerName: '车主' + id,
    vehicleTypeName: '小型客车',
    records: []
  }
}

let task1
let task2

const api = vi.hoisted(() => ({
  fetchTasks: vi.fn(),
  fetchTask: vi.fn(),
  createTask: vi.fn(),
  submitStationResult: vi.fn(),
  fetchStationStats: vi.fn().mockResolvedValue([]),
  fetchFailRank: vi.fn().mockResolvedValue([]),
  fetchVehicles: vi.fn().mockResolvedValue([]),
  fetchVehicle: vi.fn().mockResolvedValue({}),
  createVehicle: vi.fn(),
  fetchLines: vi.fn().mockResolvedValue([]),
  fetchDrivers: vi.fn().mockResolvedValue([]),
  fetchAllStations: vi.fn().mockResolvedValue([])
}))

vi.mock('../api', () => api)

vi.mock('../router', () => ({
  default: { push: vi.fn(), afterEach: vi.fn() }
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal()
  return {
    ...actual,
    ElMessage: Object.assign(vi.fn(), {
      success: vi.fn(),
      warning: vi.fn(),
      error: vi.fn()
    })
  }
})

// 模拟 App.vue 提供的底部导航：记录钉线与胶囊移动
function makePipeline () {
  return {
    stations: ref([]),
    capsule: { plate: '', seq: 0 },
    activeStation: ref(null),
    moveCapsule: vi.fn(),
    setPipelineLine: vi.fn().mockResolvedValue(),
    reloadAll: vi.fn(),
    clearStation: vi.fn()
  }
}

let pipeline

function mountFlow () {
  pipeline = makePipeline()
  return mount(FlowView, {
    global: {
      plugins: [ElementPlus],
      provide: { pipeline }
    }
  })
}

describe('工位流水线页 · 任务线路同步', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    task1 = makeTask(1, 1, 'L01', '京A·Q8801', 1)
    task2 = makeTask(2, 2, 'L02', '京B·T8845', 3)
    api.fetchTasks.mockImplementation((status) =>
      Promise.resolve(status === 'CHECKING' ? [task1, task2] : []))
    api.fetchTask.mockImplementation((id) =>
      Promise.resolve(id === 2 ? task2 : task1))
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('选中其他线路的任务：底部导航钉到任务所属线路，胶囊移到对应序号', async () => {
    const wrapper = mountFlow()
    await flushPromises()

    // 首屏自动选中第一条任务（L01）
    expect(pipeline.setPipelineLine).toHaveBeenCalledWith(1)
    expect(pipeline.moveCapsule).toHaveBeenCalledWith('京A·Q8801', 1)

    // 点击左侧 L02 的任务
    await wrapper.findAll('.vc-queue-item')[1].trigger('click')
    await flushPromises()

    expect(pipeline.setPipelineLine).toHaveBeenCalledWith(2)
    expect(pipeline.moveCapsule).toHaveBeenCalledWith('京B·T8845', 3)
    expect(wrapper.find('.vc-flow-plate').text()).toBe('京B·T8845')
    wrapper.unmount()
  })

  it('刷新时任务已被跨线处置到其他线路：重新钉线，节点不得与当前任务错配', async () => {
    const wrapper = mountFlow()
    await flushPromises()
    await wrapper.findAll('.vc-queue-item')[1].trigger('click')
    await flushPromises()
    pipeline.setPipelineLine.mockClear()

    // 跨线恢复把任务 2 从 L02 迁到 L04
    task2 = { ...task2, lineId: 4, lineNo: 'L04' }
    api.fetchTask.mockImplementation((id) =>
      Promise.resolve(id === 2 ? task2 : task1))

    const refreshBtn = wrapper.findAll('button').find((b) => b.text() === '刷新')
    await refreshBtn.trigger('click')
    await flushPromises()

    expect(pipeline.setPipelineLine).toHaveBeenCalledWith(4)
    wrapper.unmount()
  })

  it('点击不属于当前线路的节点：拒绝打开录入弹窗并提示', async () => {
    const wrapper = mountFlow()
    await flushPromises()
    // 当前任务在 L01；模拟底部导航残留了 L02 的节点被点击
    pipeline.activeStation.value = {
      id: 203, lineId: 2, lineNo: 'L02',
      stationType: 'EMISSION', stationName: '尾气'
    }
    await nextTick()
    await flushPromises()

    expect(ElMessage.warning).toHaveBeenCalledWith(
      expect.stringContaining('只能录入当前线路工位'))
    expect(wrapper.find('.el-dialog').exists()).toBe(false)
    expect(pipeline.clearStation).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('点击当前线路的节点：打开该工位录入弹窗', async () => {
    const wrapper = mountFlow()
    await flushPromises()
    pipeline.activeStation.value = {
      id: 100, lineId: 1, lineNo: 'L01',
      stationType: 'LOGIN', stationName: '登录'
    }
    await nextTick()
    await flushPromises()

    const dialog = wrapper.find('.el-dialog')
    expect(dialog.exists()).toBe(true)
    expect(dialog.text()).toContain('工位录入 · 登录')
    wrapper.unmount()
  })

  it('提交工位检测值：按当前任务提交，成功后刷新胶囊与底部徽章', async () => {
    api.submitStationResult.mockResolvedValue({ id: 1 })
    const wrapper = mountFlow()
    await flushPromises()
    pipeline.activeStation.value = {
      id: 100, lineId: 1, lineNo: 'L01',
      stationType: 'LOGIN', stationName: '登录'
    }
    await nextTick()
    await flushPromises()

    const submitBtn = wrapper.findAll('.el-dialog__footer button')
      .find((b) => b.text().includes('提交并推进到下一工位'))
    await submitBtn.trigger('click')
    await flushPromises()

    expect(submitStationResult).toHaveBeenCalledWith(1,
      expect.objectContaining({ stationId: 100 }))
    expect(fetchTask).toHaveBeenCalledWith(1)
    expect(pipeline.moveCapsule).toHaveBeenCalledWith('京A·Q8801', 1)
    expect(pipeline.reloadAll).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('未选择车辆时点击节点：提示先选择车辆', async () => {
    api.fetchTasks.mockResolvedValue([])
    const wrapper = mountFlow()
    await flushPromises()
    pipeline.activeStation.value = {
      id: 100, lineId: 1, lineNo: 'L01',
      stationType: 'LOGIN', stationName: '登录'
    }
    await nextTick()
    await flushPromises()

    expect(ElMessage.warning).toHaveBeenCalledWith('请先选择一辆在检车辆')
    expect(wrapper.find('.el-dialog').exists()).toBe(false)
    wrapper.unmount()
  })
})
