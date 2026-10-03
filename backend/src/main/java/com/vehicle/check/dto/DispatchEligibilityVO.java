package com.vehicle.check.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 派发 / 改派资格预检结果：调度员选定车辆、检测线、引车员与小时格后，
 * 落库前逐条看到阻断原因（非运行检测线 / 无工位 / 车辆当天已建档 /
 * 引车员不在岗 / 小时段不在班 / 同格已有未结束任务 / 时段越界）。
 */
@Data
public class DispatchEligibilityVO {

    private Long vehicleId;
    private String plateNo;
    private Long lineId;
    private String lineNo;
    private Long driverId;
    private String driverName;
    private Integer scheduleHour;
    private String checkDate;

    /** 全部硬性闸门是否通过；通过才可点派发 */
    private Boolean eligible;

    /** 阻断原因（逐条人话，eligible=true 时为空） */
    private List<String> blockReasons = new ArrayList<>();
}
