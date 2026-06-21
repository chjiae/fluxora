package io.fluxora.platform.auth;

import java.util.List;

/**
 * 登录响应 DTO，包含用户信息和权限编码列表。
 * 前端根据 permissions 数组控制菜单和按钮的显示/禁用。
 */
public record LoginResponse(Long userId, String username, String displayName, String scopeType,
                            Long tenantId, List<String> permissions) {}
