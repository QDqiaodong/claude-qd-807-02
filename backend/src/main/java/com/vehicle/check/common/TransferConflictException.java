package com.vehicle.check.common;

/**
 * 跨线恢复冲突：两人同时处置、或确认瞬间目标工位被占用/停用时抛出。
 * 语义是「本次确认没有成功」，前端必须刷新页面查看最新去向，而不是按旧数据重试。
 */
public class TransferConflictException extends BizException {

    public TransferConflictException(String message) {
        super(message);
    }
}
