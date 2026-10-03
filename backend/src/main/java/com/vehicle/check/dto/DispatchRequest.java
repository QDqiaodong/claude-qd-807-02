package com.vehicle.check.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 派发请求：待检车辆进入运行中的检测线，选定引车员与预定小时格。
 * 任务、小时格、车辆队列（起点工位占用）在同一事务内绑定完成。
 */
@Data
public class DispatchRequest {

    @NotNull(message = "请选择待检车辆")
    private Long vehicleId;

    @NotNull(message = "请选择检测线")
    private Long lineId;

    @NotNull(message = "请选择引车员")
    private Long driverId;

    @NotNull(message = "请选择排班小时段")
    private Integer scheduleHour;

    /** 可空，默认当天 */
    private String checkDate;
}
