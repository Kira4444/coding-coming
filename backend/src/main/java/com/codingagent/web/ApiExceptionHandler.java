package com.codingagent.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一错误响应。
 *
 * <p>把异常原文直接吐给前端是最常见的安全事故之一——
 * 这里只暴露必要信息，完整堆栈进日志。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage() == null ? "参数不合法" : e.getMessage(),
                "status", 400));
    }

    /**
     * 工作区初始化失败基本都是配置问题（路径不存在、仓库地址错、凭据缺失）。
     * 归到 400 并把原因原样告诉用户——返回「服务内部错误」会让人去查服务，
     * 而真正要改的只是项目设置里的一个输入框。
     */
    @ExceptionHandler(com.codingagent.web.WorkspaceInitializer.InitException.class)
    public ResponseEntity<Map<String, Object>> initFailed(
            com.codingagent.web.WorkspaceInitializer.InitException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage() == null ? "工作区初始化失败" : e.getMessage(),
                "status", 400));
    }

    @ExceptionHandler(com.codingagent.auth.CurrentUser.UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> unauthorized(
            com.codingagent.auth.CurrentUser.UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", e.getMessage(), "status", 401));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", e.getMessage() == null ? "状态冲突" : e.getMessage(),
                "status", 409));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst()
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(Map.of("error", msg, "status", 400));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "服务内部错误", "status", 500));
    }
}
