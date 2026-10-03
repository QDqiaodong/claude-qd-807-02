package com.vehicle.check.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 排班格子：某引车员某小时段的服务端状态。
 * 排班页重新打开时按此恢复：待派空格 / 在检 / 合格 / 不合格 / 复检，以及点格时的阻断原因。
 */
@Data
public class ScheduleCellVO {

    private Integer hour;
    private String hourLabel;

    /** 该小时是否在引车员当日排班小时段内 */
    private Boolean inShift;

    /** 引车员是否在岗 */
    private Boolean onDuty;

    private Long taskId;
    private String taskNo;
    private String plateNo;
    private Long lineId;
    private String lineNo;
    private String status;
    private String statusName;
    private String stationName;

    /**
     * 格子派单状态：EMPTY_SHIFT 可派空格 / WAITING 任务尚未开始检测（可改派）/
     * CHECKING 在检 / RETEST 复检 / PASS 合格 / FAIL 不合格 / OFF_SHIFT 不在班 / OFF_DUTY 不在岗。
     */
    private String cellState;

    /** 该格当前不可派 / 不可改派的阻断原因（可派时为 null） */
    private List<String> blockReasons = new ArrayList<>();
}
