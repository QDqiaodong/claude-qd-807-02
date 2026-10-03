package com.vehicle.check.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 派发 / 改派结果：格子最新状态 + 任务详情 + 引车员台次统计，
 * 前端一次拿回排班页、任务详情、统计区三处需要同步的数据，避免二次拼装看到中间态。
 */
@Data
public class DispatchResultVO {

    /** 本次落定（或并发下任务当前实际归属）的小时格 */
    private ScheduleCellVO cell;

    /** 任务详情（任务号 / 当前工位 / 引车员 / 检测结果一律以后端为准） */
    private TaskVO task;

    /** 引车员台次统计（有效占格 = 未结束台次） */
    private List<DriverStatDTO> driverStats = new ArrayList<>();

    /** 并发改派 / 重复点击时为 true：本次请求未改动，数据是任务当前归属 */
    private boolean currentOwnership;
}
