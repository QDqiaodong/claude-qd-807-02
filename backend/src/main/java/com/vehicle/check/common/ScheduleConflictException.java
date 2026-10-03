package com.vehicle.check.common;

/**
 * 排班并发冲突：并发改派 / 重复点击时本次操作没有成功，但任务已有明确最新归属。
 * 语义是「不要按旧数据重试」，控制器按 {@link #taskId} 回读任务当前归属一并返回，
 * 前端按返回的格子刷新，而不是再弹错误。
 */
public class ScheduleConflictException extends BizException {

    private final transient Long taskId;

    public ScheduleConflictException(String message, Long taskId) {
        super(message);
        this.taskId = taskId;
    }

    public Long getTaskId() {
        return taskId;
    }
}
