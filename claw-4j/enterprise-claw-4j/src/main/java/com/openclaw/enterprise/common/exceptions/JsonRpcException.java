package com.openclaw.enterprise.common.exceptions;

/**
 * JSON-RPC 协议异常 — WebSocket JSON-RPC 2.0 协议层的错误
 *
 * <p>此异常不继承 {@link com.openclaw.enterprise.common.Claw4jException}，
 * 因为 JSON-RPC 错误是协议层错误，不携带业务 errorCode，
 * 而是携带 JSON-RPC 标准错误码 (code)。</p>
 *
 * <p>标准 JSON-RPC 2.0 错误码：</p>
 * <ul>
 *   <li>-32700: Parse error — 解析错误</li>
 *   <li>-32600: Invalid Request — 无效请求</li>
 *   <li>-32601: Method not found — 方法未找到</li>
 *   <li>-32602: Invalid params — 无效参数</li>
 *   <li>-32603: Internal error — 内部错误</li>
 * </ul>
 *
 * <p>claw0 参考: s05_gateway_routing.py 中 WebSocket JSON-RPC 错误响应</p>
 */
public class JsonRpcException extends RuntimeException {

    /** JSON-RPC 2.0 标准错误码 */
    private final int code;

    /**
     * 构造 JSON-RPC 异常
     *
     * @param code    JSON-RPC 错误码 (如 -32600)
     * @param message 错误描述
     */
    public JsonRpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 构造 JSON-RPC 异常（带原始原因）
     *
     * @param code    JSON-RPC 错误码
     * @param message 错误描述
     * @param cause   原始异常
     */
    public JsonRpcException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 获取 JSON-RPC 错误码
     *
     * @return 错误码
     */
    public int getCode() {
        return code;
    }
}
