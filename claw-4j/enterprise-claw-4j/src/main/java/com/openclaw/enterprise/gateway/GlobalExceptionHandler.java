package com.openclaw.enterprise.gateway;

import com.openclaw.enterprise.common.Claw4jException;
import com.openclaw.enterprise.common.exceptions.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * 全局异常处理器 — 将自定义异常映射为标准 HTTP 错误响应
 *
 * <p>异常到 HTTP 状态码的映射：</p>
 * <table>
 *   <tr><th>异常</th><th>HTTP 状态码</th></tr>
 *   <tr><td>{@link AgentException}</td><td>404 NOT_FOUND</td></tr>
 *   <tr><td>{@link ToolExecutionException}</td><td>500 INTERNAL_ERROR</td></tr>
 *   <tr><td>{@link ContextOverflowException}</td><td>500 INTERNAL_ERROR</td></tr>
 *   <tr><td>{@link ProfileExhaustedException}</td><td>503 SERVICE_UNAVAILABLE</td></tr>
 *   <tr><td>{@link ChannelException}</td><td>502 BAD_GATEWAY</td></tr>
 *   <tr><td>{@link DeliveryException}</td><td>500 INTERNAL_ERROR</td></tr>
 *   <tr><td>{@link JsonRpcException}</td><td>400 BAD_REQUEST</td></tr>
 * </table>
 *
 * <p>响应格式：</p>
 * <pre>
 * {
 *   "error": {"code": "...", "message": "..."},
 *   "timestamp": "2026-04-06T...",
 *   "path": "/api/v1/..."
 * }
 * </pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AgentException.class)
    public ResponseEntity<Map<String, Object>> handleAgentException(
            AgentException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getErrorCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(ToolExecutionException.class)
    public ResponseEntity<Map<String, Object>> handleToolException(
            ToolExecutionException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, ex.getErrorCode(),
            ex.getMessage(), request);
    }

    @ExceptionHandler(ContextOverflowException.class)
    public ResponseEntity<Map<String, Object>> handleContextOverflow(
            ContextOverflowException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, ex.getErrorCode(),
            ex.getMessage(), request);
    }

    @ExceptionHandler(ProfileExhaustedException.class)
    public ResponseEntity<Map<String, Object>> handleProfileExhausted(
            ProfileExhaustedException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getErrorCode(),
            ex.getMessage(), request);
    }

    @ExceptionHandler(ChannelException.class)
    public ResponseEntity<Map<String, Object>> handleChannelException(
            ChannelException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_GATEWAY, ex.getErrorCode(),
            ex.getMessage(), request);
    }

    @ExceptionHandler(DeliveryException.class)
    public ResponseEntity<Map<String, Object>> handleDeliveryException(
            DeliveryException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, ex.getErrorCode(),
            ex.getMessage(), request);
    }

    @ExceptionHandler(JsonRpcException.class)
    public ResponseEntity<Map<String, Object>> handleJsonRpcException(
            JsonRpcException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "JSON_RPC_ERROR",
            ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            ex.getMessage(), request);
    }

    /**
     * 构建统一的错误响应
     */
    private ResponseEntity<Map<String, Object>> buildResponse(
            HttpStatus status, String code, String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(Map.of(
            "error", Map.of("code", code, "message", message != null ? message : ""),
            "timestamp", Instant.now().toString(),
            "path", request.getRequestURI()
        ));
    }
}
