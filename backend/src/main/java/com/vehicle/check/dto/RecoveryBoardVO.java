package com.vehicle.check.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 跨线恢复处置台总视图：受影响车辆（含候选线评估）+ 最近处置记录。
 */
@Data
public class RecoveryBoardVO {

    /** 卡在故障工位、仍在检测中的车辆 */
    private List<AffectedTaskVO> affectedTasks = new ArrayList<>();
    /** 最近处置记录（成功/失败/保持待处置都留痕） */
    private List<TransferVO> recentTransfers = new ArrayList<>();
    private int faultStationCount;
}
