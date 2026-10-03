package com.vehicle.check.common;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 检测站词典：工位顺序、判定阈值、业务参数。
 * 规则集中在此，便于调整阈值或扩展工位。
 */
public final class StationDict {

    private StationDict() {
    }

    /** 工位流转顺序：登录 → 外观 → 尾气 → 制动 → 灯光 → 底盘 → 总检 */
    public static final List<String> STATION_ORDER = List.of(
            "LOGIN", "APPEARANCE", "EMISSION", "BRAKE", "LIGHT", "CHASSIS", "FINAL");

    private static final Map<String, String> STATION_NAME = Map.of(
            "LOGIN", "登录",
            "APPEARANCE", "外观",
            "EMISSION", "尾气",
            "BRAKE", "制动",
            "LIGHT", "灯光",
            "CHASSIS", "底盘",
            "FINAL", "总检");

    /** 各工位的检测项名称 */
    private static final Map<String, String> STATION_ITEM = Map.of(
            "LOGIN", "车辆唯一性确认",
            "APPEARANCE", "外观与灯具查验",
            "EMISSION", "尾气 CO/HC 检测",
            "BRAKE", "制动率检测",
            "LIGHT", "前照灯发光强度",
            "CHASSIS", "底盘间隙与转向系",
            "FINAL", "总检复核");

    /** 关键项：任一不合格则报告结论必须为不合格 */
    private static final List<String> KEY_ITEMS = List.of("EMISSION", "BRAKE", "LIGHT", "CHASSIS");

    /** 判定阈值 */
    public static final BigDecimal CO_LIMIT = BigDecimal.valueOf(0.300);   // CO 限值 %
    public static final int HC_LIMIT = 50;                                 // HC 限值 ppm
    public static final BigDecimal BRAKE_MIN = BigDecimal.valueOf(60.00);  // 制动率下限 %
    public static final int LIGHT_MIN = 15000;                             // 灯光强度下限 cd
    public static final int APPEARANCE_DEFECT_MAX = 2;                     // 外观缺陷项上限

    private static final Map<String, String> LINE_TYPE = Map.of(
            "SECURITY", "安检",
            "ENVIRONMENT", "环保",
            "COMPREHENSIVE", "综检");

    private static final Map<String, String> LINE_STATUS = Map.of(
            "RUNNING", "运行",
            "STOPPED", "停用");

    private static final Map<String, String> STATION_STATUS = Map.of(
            "IDLE", "空闲",
            "OCCUPIED", "占用",
            "FAULT", "故障");

    private static final Map<String, String> VEHICLE_TYPE = Map.of(
            "SMALL_BUS", "小型客车",
            "TRUCK", "货车",
            "HAZMAT", "危化品车");

    private static final Map<String, String> FUEL_TYPE = Map.of(
            "GASOLINE", "汽油",
            "DIESEL", "柴油",
            "LNG", "液化天然气",
            "ELECTRIC", "纯电");

    private static final Map<String, String> TASK_STATUS = Map.of(
            "CHECKING", "在检",
            "PASS", "合格",
            "FAIL", "不合格",
            "RETEST", "复检");

    private static final Map<String, String> DEVICE_STATUS = Map.of(
            "NORMAL", "正常",
            "DUE", "临近标定",
            "OVERDUE", "已超期");

    /** 标定临期预警天数 */
    public static final int CAL_DUE_DAYS = 7;

    /** 排班小时段 8:00 - 18:00 */
    public static final int SHIFT_START_HOUR = 8;
    public static final int SHIFT_END_HOUR = 18;

    public static int seqOf(String stationType) {
        int index = STATION_ORDER.indexOf(stationType);
        return index < 0 ? 0 : index + 1;
    }

    public static String stationName(String stationType) {
        return stationType == null ? "" : STATION_NAME.getOrDefault(stationType, stationType);
    }

    public static String stationItem(String stationType) {
        return stationItemOrDefault(stationType, stationType);
    }

    private static String stationItemOrDefault(String stationType, String fallback) {
        return stationType == null ? fallback : STATION_ITEM.getOrDefault(stationType, fallback);
    }

    public static boolean isKeyItem(String stationType) {
        return stationType != null && KEY_ITEMS.contains(stationType);
    }

    public static boolean isValidStationType(String stationType) {
        return stationType != null && STATION_ORDER.contains(stationType);
    }

    public static String lineTypeName(String type) {
        return type == null ? "" : LINE_TYPE.getOrDefault(type, type);
    }

    public static String lineStatusName(String status) {
        return status == null ? "" : LINE_STATUS.getOrDefault(status, status);
    }

    public static String stationStatusName(String status) {
        return status == null ? "" : STATION_STATUS.getOrDefault(status, status);
    }

    public static String vehicleTypeName(String type) {
        return type == null ? "" : VEHICLE_TYPE.getOrDefault(type, type);
    }

    public static String fuelTypeName(String type) {
        return type == null ? "" : FUEL_TYPE.getOrDefault(type, type);
    }

    public static String taskStatusName(String status) {
        return status == null ? "" : TASK_STATUS.getOrDefault(status, status);
    }

    public static String deviceStatusName(String status) {
        return status == null ? "" : DEVICE_STATUS.getOrDefault(status, status);
    }
}
