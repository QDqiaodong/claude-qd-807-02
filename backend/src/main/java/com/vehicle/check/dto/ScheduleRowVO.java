package com.vehicle.check.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 排班格子表一行：一名引车员 + 各小时段格子
 */
@Data
public class ScheduleRowVO {

    private Long driverId;
    private String driverName;
    private String certLevel;
    private String certNo;
    private String shiftHours;
    private Integer checkedCount;
    private String status;
    private List<ScheduleCellVO> cells = new ArrayList<>();
}
