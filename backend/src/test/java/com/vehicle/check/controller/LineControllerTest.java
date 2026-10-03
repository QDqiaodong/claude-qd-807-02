package com.vehicle.check.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vehicle.check.common.BizException;
import com.vehicle.check.common.GlobalExceptionHandler;
import com.vehicle.check.dto.StationVO;
import com.vehicle.check.service.LineService;

/**
 * 底部流水线导航接口测试（MockMvc 切片）：
 * 不传 lineId 时给默认运行线工位；传 lineId 时必须按该线返回，
 * 多线并行时底部导航节点与选中任务所属线路保持一致。
 */
class LineControllerTest {

    private final LineService lineService = org.mockito.Mockito.mock(LineService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new LineController(lineService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private StationVO station(long id, long lineId, String lineNo, String name, int seqNo) {
        return new StationVO(id, lineId, lineNo, "LOGIN", name, seqNo, "IDLE", null, null);
    }

    @Test
    void pipelineWithoutLineIdServesDefaultRunningLine() throws Exception {
        when(lineService.pipeline(null)).thenReturn(List.of(
                station(1L, 1L, "L01", "登录", 1),
                station(2L, 1L, "L01", "外观", 2)));

        mockMvc.perform(get("/api/lines/stations/pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].lineNo").value("L01"))
                .andExpect(jsonPath("$.data[0].stationName").value("登录"));
        verify(lineService).pipeline(null);
    }

    @Test
    void pipelineWithLineIdServesStationsOfThatLine() throws Exception {
        // 回归：多线并行时底部导航不能再始终显示第一条线路的工位
        when(lineService.pipeline(2L)).thenReturn(List.of(
                station(8L, 2L, "L02", "登录", 1),
                station(9L, 2L, "L02", "外观", 2)));

        mockMvc.perform(get("/api/lines/stations/pipeline").param("lineId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].lineId").value(2))
                .andExpect(jsonPath("$.data[0].lineNo").value("L02"))
                .andExpect(jsonPath("$.data[1].id").value(9));
        verify(lineService).pipeline(2L);
    }

    @Test
    void pipelineWithUnknownLineIdReturns400() throws Exception {
        when(lineService.pipeline(99L)).thenThrow(new BizException("检测线不存在：id=99"));

        mockMvc.perform(get("/api/lines/stations/pipeline").param("lineId", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("检测线不存在")));
    }
}
