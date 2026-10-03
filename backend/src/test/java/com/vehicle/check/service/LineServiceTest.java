package com.vehicle.check.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vehicle.check.common.BizException;
import com.vehicle.check.domain.Line;
import com.vehicle.check.domain.Station;
import com.vehicle.check.dto.StationVO;
import com.vehicle.check.repository.DeviceRepository;
import com.vehicle.check.repository.LineRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;

/**
 * 底部流水线导航选线规则单测（纯 Mockito）：
 * 修复点——多运行线路时 pipeline() 旧实现永远返回第一条运行线，导致选中其他线路任务后
 * 底部节点错配。新规则：指定 lineId（当前任务/刷新恢复的最近线路）时无视运行状态按该线取节点；
 * 不指定才回落第一条运行线（单线路场景行为不变）。
 */
class LineServiceTest {

    private LineRepository lineRepository;
    private StationRepository stationRepository;
    private TaskRepository taskRepository;
    private DeviceRepository deviceRepository;
    private LineService service;

    @BeforeEach
    void setUp() {
        lineRepository = mock(LineRepository.class);
        stationRepository = mock(StationRepository.class);
        taskRepository = mock(TaskRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        service = new LineService(lineRepository, stationRepository, taskRepository, deviceRepository);

        when(taskRepository.countQueueGroupByStation()).thenReturn(List.of());
        when(deviceRepository.findAll()).thenReturn(List.of());
    }

    private Line line(long id, String no, String status) {
        Line l = new Line();
        l.setId(id);
        l.setLineNo(no);
        l.setLineName(no + "线");
        l.setLineType("SECURITY");
        l.setStatus(status);
        l.setAcceptVehicleTypes("SMALL_BUS,TRUCK,HAZMAT");
        return l;
    }

    private Station station(long id, long lineId, int seqNo, String status) {
        Station s = new Station();
        s.setId(id);
        s.setLineId(lineId);
        s.setStationType("T" + seqNo);
        s.setStationName("工位" + seqNo);
        s.setSeqNo(seqNo);
        s.setStatus(status);
        return s;
    }

    @Test
    void pipelineWithoutLineIdFallsBackToFirstRunningLine() {
        Line l01 = line(1, "L01", "RUNNING");
        Line l02 = line(2, "L02", "RUNNING");
        when(lineRepository.findByStatus("RUNNING")).thenReturn(List.of(l01, l02));
        when(lineRepository.findById(1L)).thenReturn(Optional.of(l01));
        when(stationRepository.findByLineIdOrderBySeqNoAsc(1L))
                .thenReturn(List.of(station(1, 1, 1, "OCCUPIED"), station(2, 1, 2, "IDLE")));

        List<StationVO> result = service.pipeline();

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getLineId());
        assertEquals("L01", result.get(0).getLineNo());
        // 默认线只在「无当前任务」时使用：绝不该去碰 L02
        verify(stationRepository, never()).findByLineIdOrderBySeqNoAsc(2L);
    }

    @Test
    void pipelineWithLineIdReturnsThatLineEvenWhenItIsNotFirstRunningLine() {
        Line l02 = line(2, "L02", "RUNNING");
        when(lineRepository.findById(2L)).thenReturn(Optional.of(l02));
        when(stationRepository.findByLineIdOrderBySeqNoAsc(2L))
                .thenReturn(List.of(station(8, 2, 1, "IDLE")));

        List<StationVO> result = service.pipeline(2L);

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).getLineId());
        assertEquals("L02", result.get(0).getLineNo());
        // 指定线路直接按 id 取，不再扫描运行线列表挑第一条
        verify(lineRepository, never()).findByStatus("RUNNING");
    }

    @Test
    void pipelineWithLineIdStillServesStoppedLineSoFinishedTaskLineStaysStable() {
        // 任务结束/刷新恢复时其线路可能刚被停用：节点仍按该任务线路返回，保证不错配到别的线
        Line stopped = line(5, "L05", "STOPPED");
        when(lineRepository.findById(5L)).thenReturn(Optional.of(stopped));
        when(stationRepository.findByLineIdOrderBySeqNoAsc(5L))
                .thenReturn(List.of(station(30, 5, 1, "IDLE")));

        List<StationVO> result = service.pipeline(5L);

        assertEquals(5L, result.get(0).getLineId());
        verify(lineRepository, never()).findByStatus("RUNNING");
    }

    @Test
    void pipelineWithUnknownLineIdRejectsWithBizException() {
        when(lineRepository.findById(404L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.pipeline(404L));
        assertEquals("检测线不存在：id=404", ex.getMessage());
    }

    @Test
    void pipelineDefaultWithoutAnyRunningLineRejectsWithBizException() {
        when(lineRepository.findByStatus("RUNNING")).thenReturn(List.of());

        assertThrows(BizException.class, () -> service.pipeline());
    }
}
