package com.vehicle.check.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 改派请求：尚未开始检测的任务改到另一名符合全部约束的引车员（可同时改小时格）。
 */
@Data
public class ReassignRequest {

    @NotNull(message = "请选择改派后的引车员")
    private Long driverId;

    /** 不传表示沿用原小时格 */
    private Integer scheduleHour;

    /** 操作调度员，留痕用，可空 */
    private String operatorName;
}
