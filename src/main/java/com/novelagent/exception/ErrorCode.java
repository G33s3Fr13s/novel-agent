package com.novelagent.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    USERNAME_ALREADY_EXISTS(1001, "用户名已存在"),
    EMAIL_ALREADY_EXISTS(1002, "邮箱已被注册"),
    INVALID_CREDENTIALS(1003, "用户名或密码错误"),
    ACCOUNT_DISABLED(1004, "账户已被禁用"),
    TOKEN_EXPIRED(1005, "令牌已过期，请重新登录"),
    TOKEN_INVALID(1006, "无效的令牌"),
    TOKEN_MISSING(1007, "缺少认证令牌"),
    REFRESH_TOKEN_INVALID(1008, "无效的刷新令牌"),
    REFRESH_TOKEN_EXPIRED(1009, "刷新令牌已过期"),
    REFRESH_TOKEN_REVOKED(1010, "刷新令牌已被撤销"),

    INTERNAL_ERROR(9001, "服务器内部错误"),
    VALIDATION_ERROR(9002, "请求参数校验失败"),
    RESOURCE_NOT_FOUND(9003, "资源不存在"),
    ACCESS_DENIED(9004, "无权访问该资源");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
