import { beforeEach, describe, expect, it, vi } from 'vitest'

// 拦截 axios：fetchPipeline 必须按钉住线路透传 lineId 查询参数
const http = vi.hoisted(() => ({
  get: vi.fn(),
  interceptors: { response: { use: vi.fn() } }
}))

vi.mock('axios', () => ({
  default: { create: vi.fn(() => http) }
}))

import { fetchPipeline } from '../api'

describe('api · fetchPipeline 线路参数', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    http.get.mockResolvedValue({ data: [] })
  })

  it('不传线路：不带 lineId 参数，后端给默认第一条运行线', async () => {
    await fetchPipeline()
    expect(http.get).toHaveBeenCalledWith('/lines/stations/pipeline', { params: {} })
  })

  it('传 null（未钉线）：同样不带 lineId 参数', async () => {
    await fetchPipeline(null)
    expect(http.get).toHaveBeenCalledWith('/lines/stations/pipeline', { params: {} })
  })

  it('钉住线路后：带上 lineId 查询参数', async () => {
    await fetchPipeline(2)
    expect(http.get).toHaveBeenCalledWith('/lines/stations/pipeline', { params: { lineId: 2 } })
  })
})
