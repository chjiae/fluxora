package io.fluxora.platform.auth;

import java.util.List;

public record LoginResponse(Long userId, String username, String displayName, String scopeType,
                            Long tenantId, List<String> permissions) {}
