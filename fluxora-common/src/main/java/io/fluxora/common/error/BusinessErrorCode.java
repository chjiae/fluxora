package io.fluxora.common.error;

public enum BusinessErrorCode {

    AUTH_INVALID_CREDENTIALS("用户名或密码错误，请重新输入"),
    AUTH_ACCOUNT_DISABLED("当前账号已停用，请联系管理员"),
    AUTH_TENANT_DISABLED("所属租户已停用，暂时无法使用"),
    AUTH_TENANT_EXPIRED("所属租户已到期，请联系管理员续期"),
    AUTH_TENANT_DELETED("当前账号所属租户不可用，请联系管理员"),
    AUTH_SESSION_EXPIRED("登录已失效，请重新登录"),
    ACCESS_DENIED("当前账号没有此操作权限"),
    TENANT_CODE_DUPLICATE("该租户码已被使用，请更换后重试"),
    SELF_OPERATED_TENANT_PROTECTED("自营租户受保护，无法%s"),
    VALIDATION_ERROR(null),
    RESOURCE_NOT_FOUND(null),
    INTERNAL_ERROR("服务暂时不可用，请稍后重试");

    private final String defaultUserMessage;

    BusinessErrorCode(String defaultUserMessage) {
        this.defaultUserMessage = defaultUserMessage;
    }

    public String getDefaultUserMessage() {
        return defaultUserMessage;
    }
}
