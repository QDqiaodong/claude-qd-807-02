package com.vehicle.check.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vehicle.check.common.BizException;
import com.vehicle.check.domain.Line;
import com.vehicle.check.domain.Station;
import com.vehicle.check.dto.StationQueueDTO;
import com.vehicle.check.dto.StationVO;
import com.vehicle.check.repository.DeviceRepository;
import com.vehicle.check.repository.LineRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;

/**
 * 底部流水线导航选线规则单测（纯 Mockito，不依赖数据库）：
 * 默认取第一条运行线；指定 lineId 时必须返回该线工位（即使已停用），
 * 保证多线并行时底部导航节点与当前任务所属线路一致。
 */
class LineServiceTest {

    private LineRepository lineRepository;
    private StationRepository stationRepository;
    private TaskRepository taskRepository;
    private DeviceRepository deviceRepository;
    private LineService service;

    @BeforeEach
    void setUp() {
        lineRepository = Mockito.mock(LineRepository.class);
        stationRepository = Mockito.mock(StationRepository.class);
        taskRepository = Mockito.mock(TaskRepository.class);
        deviceRepository = Mockito.mock(DeviceRepository.class);
        service = new LineService(lineRepository, stationRepository, taskRepository, deviceRepository);
        when(taskRepository.countQueueGroupByStation()).thenReturn(List.of());
        when(deviceRepository.findAll()).thenReturn(List.of());
    }

    private Line line(long id, String lineNo, String status) {
        Line line = new Line();
        line.setId(id);
        line.setLineNo(lineNo);
        line.setLineName(lineNo + "线");
        line.setStatus(status);
        return line;
    }

    private Station station(long id, long lineId, int seqNo) {
        Station station = new Station();
        station.setId(id);
        station.setLineId(lineId);
        station.setStationType("LOGIN");
        station.setStationName("工位" + seqNo);
        station.setSeqNo(seqNo);
        station.setStatus("IDLE");
        return station;
    }

    @Test
    void defaultPipelinePicksFirstRunningLine() {
        // L01 停用不参与；运行中的线是 L02、L03，默认必须取 L02 的工位
        Line l02 = line(2L, "L02", "RUNNING");
        Line l03 = line(3L, "L03", "RUNNING");
        when(lineRepository.findByStatus("RUNNING")).thenReturn(List.of(l02, l03));
        when(lineRepository.findById(2L)).thenReturn(Optional.of(l02));
        when(stationRepository.findByLineIdOrderBySeqNoAsc(2L))
                .thenReturn(List.of(station(20L, 2L, 1), station(21L, 2L, 2)));
        when(taskRepository.countQueueGroupByStation())
                .thenReturn(List.of(new StationQueueDTO(21L, 3L)));

        List<StationVO> result = service.pipeline();

        assertEquals(2, result.size());
        assertEquals("L02", result.get(0).getLineNo());
        assertEquals(2L, result.get(0).getLineId());
        assertEquals(0L, result.get(0).getQueueCount());
        assertEquals(3L, result.get(1).getQueueCount());
        verify(stationRepository, never()).findByLineIdOrderBySeqNoAsc(3L);
    }

    @Test
    void pipelineWithExplicitLineIdServesThatLineEvenIfStopped() {
        // 选中任务的所属线即使已停用，也必须返回该线工位（在检任务仍要继续录入），
        // 不能回退到第一条运行线——这正是「底部导航始终显示第一条线路」的错配根因
        Line stopped = line(5L, "L05", "STOPPED");
        when(lineRepository.findById(5L)).thenReturn(Optional.of(stopped));
        when(stationRepository.findByLineIdOrderBySeqNoAsc(5L))
                .thenReturn(List.of(station(50L, 5L, 1)));

        List<StationVO> result = service.pipeline(5L);

        assertEquals(1, result.size());
        assertEquals("L05", result.get(0).getLineNo());
        assertEquals(5L, result.get(0).getLineId());
        verify(lineRepository, never()).findByStatus("RUNNING");
    }

    @Test
    void pipelineWithNullLineIdFallsBackToDefaultRunningLine() {
        Line l01 = line(1L, "L01", "RUNNING");
        when(lineRepository.findByStatus("RUNNING")).thenReturn(List.of(l01));
        when(lineRepository.findById(1L)).thenReturn(Optional.of(l01));
        when(stationRepository.findByLineIdOrderBySeqNoAsc(1L))
                .thenReturn(List.of(station(10L, 1L, 1)));

        List<StationVO> result = service.pipeline(null);

        assertEquals(1, result.size());
        assertEquals("L01", result.get(0).getLineNo());
    }

    @Test
    void pipelineWithUnknownLineIdThrows() {
        when(lineRepository.findById(99L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.pipeline(99L));
        assertTrue(ex.getMessage().contains("检测线不存在"));
    }

    @Test
    void defaultPipelineThrowsWhenNoRunningLine() {
        when(lineRepository.findByStatus("RUNNING")).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class, () -> service.pipeline());
        assertTrue(ex.getMessage().contains("暂无运行中的检测线"));
    }
}
