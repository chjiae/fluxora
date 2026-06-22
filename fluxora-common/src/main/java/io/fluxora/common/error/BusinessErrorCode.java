package io.fluxora.common.error;

/**
 * 业务错误码枚举。
 * 每个错误码关联默认的非技术中文提示，前后端通过错误码（非消息文本）进行程序化识别。
 * 用户界面不得直接展示错误码名称（如 AUTH_INVALID_CREDENTIALS）。
 *
 * 错误码增减必须同步更新：
 *   - 前端 fluxora-web/src/services/http.ts 的 USER_MESSAGES 映射表
 *   - 后端 GlobalExceptionHandler 中的 HTTP 状态码映射
 *   - 若涉及新的页面交互，需更新 README 与 AGENT.md「用户可理解的错误提示规范」章节
 */
public enum BusinessErrorCode {

    AUTH_INVALID_CREDENTIALS("用户名或密码错误，请重新输入"),
    AUTH_ACCOUNT_DISABLED("当前账号已停用，请联系管理员"),
    AUTH_TENANT_DISABLED("所属租户已停用，暂时无法使用"),
    AUTH_TENANT_EXPIRED("所属租户已到期，请联系管理员续期"),
    AUTH_TENANT_DELETED("当前账号所属租户不可用，请联系管理员"),
    AUTH_SESSION_EXPIRED("登录已失效，请重新登录"),
    ACCESS_DENIED("当前账号没有此操作权限"),
    TENANT_CODE_DUPLICATE("该租户码已被使用，请更换后重试"),
    /** 格式化字符串，调用时使用 String.format 填入具体操作描述 */
    SELF_OPERATED_TENANT_PROTECTED("自营租户受保护，无法%s"),
    VALIDATION_ERROR("输入内容不符合要求，请检查后重试"),
    RESOURCE_NOT_FOUND("请求的资源不存在"),
    INTERNAL_ERROR("服务暂时不可用，请稍后重试"),

    // ---------------- 成员管理相关业务错误码 ----------------

    /** 用户名重复（全局唯一约束命中未删除记录） */
    USERNAME_DUPLICATE("该用户名已被使用，请更换后重试"),
    /** 触发最后管理员保护：租户至少需要保留一名启用状态的管理员 */
    LAST_TENANT_ADMIN_PROTECTED("该租户至少需要保留一名启用状态的租户管理员，无法继续操作"),
    /** 当前操作者无权分配目标角色（跨作用域、平台角色保护或租户管理员升级保护） */
    ROLE_NOT_ASSIGNABLE("当前账号无权分配该角色"),
    /** 成员不存在或已被软删除 */
    MEMBER_NOT_FOUND("成员不存在或已被删除"),
    /** 租户管理员尝试访问/操作其他租户的成员；与 ACCESS_DENIED 文案一致，独立编码便于日志区分 */
    CROSS_TENANT_ACCESS_DENIED("当前账号没有此操作权限"),
    /** 密码强度不符合要求（长度、字母+数字组合） */
    PASSWORD_WEAK("密码强度不符合要求，请使用至少 8 位且包含字母与数字"),

    // ---------------- API Key 相关业务错误码 ----------------

    /** Key 名称不合法（长度或字符不符合） */
    API_KEY_NAME_INVALID("请填写符合要求的 Key 名称"),
    /** API Key 不存在或已被删除（含跨权限场景的隐式不存在响应） */
    API_KEY_NOT_FOUND("API Key 不存在或已被删除"),
    /** API Key 已停用，应在调用方主动恢复 enabled=TRUE 前拒绝使用 */
    API_KEY_DISABLED_STATE("该 API Key 已停用，暂时无法使用"),
    /** API Key 已过期，需通过更新 expire_at 或创建新的 Key 解决 */
    API_KEY_EXPIRED("该 API Key 已过期，请更新过期时间或创建新的 Key"),
    /** API Key 已被软删除，所有后续操作拒绝 */
    API_KEY_DELETED_STATE("该 API Key 已删除，无法继续操作"),

    // ---------------- 额度账户与流水相关业务错误码 ----------------

    /** 扣减额度时余额不足；在原子 UPDATE…WHERE balance+delta>=0 返回 0 行时抛出 */
    CREDIT_INSUFFICIENT("当前可用额度不足，无法完成扣减"),
    /** 目标用户没有额度账户（PLATFORM 作用域用户不创建账户） */
    CREDIT_ACCOUNT_NOT_FOUND("当前账号没有可用的额度账户"),
    /** 调整金额不合法（必须为正数；方向由 direction 表达） */
    CREDIT_AMOUNT_INVALID("调整金额必须为正数"),
    /** 调整原因为空（流水必须有可审计的原因） */
    CREDIT_REASON_REQUIRED("请填写调整原因");

    private final String defaultUserMessage;

    BusinessErrorCode(String defaultUserMessage) {
        this.defaultUserMessage = defaultUserMessage;
    }

    public String getDefaultUserMessage() {
        return defaultUserMessage;
    }
}
