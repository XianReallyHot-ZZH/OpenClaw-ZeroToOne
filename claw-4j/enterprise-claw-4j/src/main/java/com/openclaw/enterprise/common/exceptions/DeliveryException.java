package com.openclaw.enterprise.common.exceptions;

import com.openclaw.enterprise.common.Claw4jException;

/**
 * 消息投递异常 — 投递队列中的消息投递失败
 *
 * <p>适用场景：</p>
 * <ul>
 *   <li>消息发送到渠道失败</li>
 *   <li>投递队列文件读写错误</li>
 *   <li>重试次数超限</li>
 * </ul>
 *
 * <p>错误码: DELIVERY_ERROR</p>
 *
 * <p>claw0 参考: s08_delivery.py 中投递重试相关的异常处理</p>
 */
public class DeliveryException extends Claw4jException {

    /** 关联的投递记录 ID */
    private final String deliveryId;

    /**
     * 构造投递异常
     *
     * @param deliveryId 投递记录 ID
     * @param message    错误描述
     */
    public DeliveryException(String deliveryId, String message) {
        super("DELIVERY_ERROR", message);
        this.deliveryId = deliveryId;
    }

    /**
     * 构造投递异常（带原始原因）
     *
     * @param deliveryId 投递记录 ID
     * @param message    错误描述
     * @param cause      原始异常
     */
    public DeliveryException(String deliveryId, String message, Throwable cause) {
        super("DELIVERY_ERROR", message, cause);
        this.deliveryId = deliveryId;
    }

    /**
     * 获取关联的投递记录 ID
     *
     * @return 投递 ID
     */
    public String getDeliveryId() {
        return deliveryId;
    }
}
