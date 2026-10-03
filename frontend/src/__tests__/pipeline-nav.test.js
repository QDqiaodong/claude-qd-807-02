import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import App from '../App.vue'
import { fetchPipeline } from '../api'

// ---- 线路工位夹具：L01 七工位 / L02 七工位（id 与 lineId 不同，模拟多线并行） ----
const TYPES = ['LOGIN', 'APPEARANCE', 'EMISSION', 'BRAKE', 'LIGHT', 'CHASSIS', 'FINAL']
const NAMES = ['登录', '外观', '尾气', '制动', '灯光', '底盘', '总检']

function stationsOf (lineId, lineNo, idBase) {
  return TYPES.map((type, i) => ({
    id: idBase + i,
    lineId,
    lineNo,
    stationType: type,
    stationName: NAMES[i],
    seqNo: i + 1,
    status: 'IDLE',
    statusName: '空闲',
    queueCount: 0
  }))
}

const LINE1 = stationsOf(1, 'L01', 100)
const LINE2 = stationsOf(2, 'L02', 200)
const LINE3 = stationsOf(3, 'L03', 300)

// 捕获 FlowView 注入的 pipeline 对象，测试里直接模拟「选中任务」动作
const captured = vi.hoisted(() => ({ pipeline: null }))

vi.mock('../api', () => ({
  fetchPipeline: vi.fn(),
  fetchOverview: vi.fn().mockResolvedValue({}),
  fetchRecoveryBoard: vi.fn().mockResolvedValue({ affectedTasks: [] })
}))

vi.mock('../router', () => ({
  default: { push: vi.fn(), afterEach: vi.fn() }
}))

vi.mock('../views/FlowView.vue', async () => {
  const { inject } = await import('vue')
  return {
    default: {
      name: 'FlowView',
      template: '<div class="flow-stub" />',
      setup () {
        captured.pipeline = inject('pipeline')
        return {}
      }
    }
  }
})
vi.mock('../views/RecoveryView.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/ReportView.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/ScheduleView.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/DeviceView.vue', () => ({ default: { template: '<div />' } }))

function mountApp () {
  return mount(App, { global: { plugins: [ElementPlus] } })
}

describe('底部流水线导航 · 多线路同步', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    captured.pipeline = null
    fetchPipeline.mockImplementation((lineId) =>
      Promise.resolve(lineId === 2 ? LINE2 : LINE1))
  })

  it('未选中任务时加载默认线路节点', async () => {
    const wrapper = mountApp()
    await flushPromises()

    expect(fetchPipeline).toHaveBeenCalledWith(null)
    expect(wrapper.find('.vc-pipe-line-tag').text()).toBe('L01')
    const nodes = wrapper.findAll('.vc-pipe-node')
    expect(nodes).toHaveLength(7)
    expect(nodes[0].text()).toContain('登录')
    wrapper.unmount()
  })

  it('选中其他线路任务后导航节点切换到该线路，且 8 秒轮询不跳回第一条线', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = mountApp()
      await flushPromises()
      expect(wrapper.find('.vc-pipe-line-tag').text()).toBe('L01')

      // FlowView 选中 L02 上的任务 → 钉住线路 2
      await captured.pipeline.setPipelineLine(2)
      await flushPromises()

      expect(fetchPipeline).toHaveBeenLastCalledWith(2)
      expect(wrapper.find('.vc-pipe-line-tag').text()).toBe('L02')
      expect(wrapper.findAll('.vc-pipe-node')[0].text()).toContain('登录')

      // 定时刷新（8 秒轮询）后仍停留在 L02，不得回退默认线
      fetchPipeline.mockClear()
      await vi.advanceTimersByTimeAsync(8000)
      await flushPromises()

      expect(fetchPipeline).toHaveBeenCalledWith(2)
      expect(wrapper.find('.vc-pipe-line-tag').text()).toBe('L02')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('钉住的线路加载失败时回退默认线路，导航不空白', async () => {
    fetchPipeline.mockImplementation((lineId) => {
      if (lineId === 99) return Promise.reject(new Error('检测线不存在：id=99'))
      return Promise.resolve(LINE1)
    })
    const wrapper = mountApp()
    await flushPromises()

    await captured.pipeline.setPipelineLine(99)
    await flushPromises()

    // 第一次按钉住线路请求失败 → 自动回退默认线
    expect(fetchPipeline).toHaveBeenCalledWith(99)
    expect(fetchPipeline).toHaveBeenLastCalledWith(null)
    expect(wrapper.find('.vc-pipe-line-tag').text()).toBe('L01')
    expect(wrapper.findAll('.vc-pipe-node')).toHaveLength(7)
    wrapper.unmount()
  })

  it('连续切换任务时，慢响应不得覆盖最新线路节点', async () => {
    let resolveL2
    let resolveL3
    fetchPipeline.mockImplementation((lineId) => {
      if (lineId === 2) return new Promise((resolve) => { resolveL2 = () => resolve(LINE2) })
      if (lineId === 3) return new Promise((resolve) => { resolveL3 = () => resolve(LINE3) })
      return Promise.resolve(LINE1)
    })
    const wrapper = mountApp()
    await flushPromises()

    // 先后选中 L02、L03 上的任务；L02 的响应比 L03 慢
    const p2 = captured.pipeline.setPipelineLine(2)
    const p3 = captured.pipeline.setPipelineLine(3)
    resolveL3()
    await p3
    resolveL2()
    await p2
    await flushPromises()

    expect(wrapper.find('.vc-pipe-line-tag').text()).toBe('L03')
    expect(wrapper.findAll('.vc-pipe-node')).toHaveLength(7)
    wrapper.unmount()
  })

  it('胶囊位置按当前线路的节点数定位', async () => {
    const wrapper = mountApp()
    await flushPromises()

    captured.pipeline.moveCapsule('京A·Q8801', 4)
    await wrapper.vm.$nextTick()

    // 7 个工位时第 4 工位：left = (3.5 / 7) * 100% = 50%
    expect(wrapper.find('.vc-pipe-capsule').attributes('style')).toContain('left: 50%')
    expect(wrapper.find('.vc-pipe-capsule').text()).toBe('京A·Q8801')
    wrapper.unmount()
  })

  it('点击节点把该工位交给流水线页面（只能来自当前导航线路）', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = mountApp()
      await flushPromises()
      await captured.pipeline.setPipelineLine(2)
      await flushPromises()

      await wrapper.findAll('.vc-pipe-node')[2].trigger('click')
      await vi.advanceTimersByTimeAsync(40)
      await flushPromises()

      // activeStation 必须是 L02 的节点（id 200 段），不是 L01 的同序号工位
      expect(captured.pipeline.activeStation.value).toMatchObject({ id: 202, lineId: 2, lineNo: 'L02' })
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })
})
