package com.vehicle.check.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vehicle.check.common.BizException;
import com.vehicle.check.dto.StationVO;
import com.vehicle.check.service.LineService;

/**
 * 底部流水线导航接口测试（MockMvc 切片）：
 * - 不传 lineId：走默认线路（第一条运行线），保持单线路使用方式不变；
 * - 传 lineId：固定返回指定任务所属线路节点 —— 多线路下导航节点必须随任务切换，
 *   定时刷新/任务结束/刷新页面后都按当前/最近线路取节点，不再始终返回第一条线；
 * - 指定线路不存在：400 中文提示。
 */
class LineControllerTest {

    private LineService lineService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        lineService = mock(LineService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new LineController(lineService))
                .setControllerAdvice(new com.vehicle.check.common.GlobalExceptionHandler())
                .build();
    }

    private StationVO station(long id, long lineId, String lineNo, int seqNo) {
        StationVO vo = new StationVO(id, lineId, lineNo, "T" + seqNo, "工位" + seqNo,
                seqNo, "IDLE", null, null);
        vo.setLineNo(lineNo);
        vo.setStatusName("空闲");
        vo.setQueueCount(seqNo == 1 ? 2L : 0L);
        return vo;
    }

    @Test
    void pipelineWithoutLineIdReturnsDefaultRunningLine() throws Exception {
        // 默认线 L01
        when(lineService.pipeline(eq(null)))
                .thenReturn(List.of(station(1, 1L, "L01", 1), station(2, 1L, "L01", 2)));

        mockMvc.perform(get("/api/lines/stations/pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].lineId").value(1))
                .andExpect(jsonPath("$.data[0].lineNo").value("L01"))
                .andExpect(jsonPath("$.data[0].queueCount").value(2));

        verify(lineService).pipeline(eq(null));
    }

    @Test
    void pipelineWithLineIdReturnsThatLineNotTheFirstOne() throws Exception {
        // 选中了 L02（id=2）上的任务：节点必须是 L02 的（id 8/9…），而不是默认 L01
        when(lineService.pipeline(eq(2L)))
                .thenReturn(List.of(station(8, 2L, "L02", 1), station(9, 2L, "L02", 2)));

        mockMvc.perform(get("/api/lines/stations/pipeline").param("lineId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(8))
                .andExpect(jsonPath("$.data[0].lineId").value(2))
                .andExpect(jsonPath("$.data[0].lineNo").value("L02"));

        verify(lineService).pipeline(eq(2L));
        // 绝不能回落去取默认线
        verify(lineService, never()).pipeline(eq(null));
    }

    @Test
    void pipelineOfMissingLineReturns400BizMessage() throws Exception {
        when(lineService.pipeline(eq(404L)))
                .thenThrow(new BizException("检测线不存在：id=404"));

        mockMvc.perform(get("/api/lines/stations/pipeline").param("lineId", "404"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("检测线不存在")));
    }
}
