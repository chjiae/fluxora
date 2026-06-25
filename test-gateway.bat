@echo off
REM Fluxora Gateway 本地测试
REM 用法：双击运行，或在终端执行 test-gateway.bat
REM 流式测试需在 cmd.exe 中运行（PowerShell 的 curl 别名不支持 -N）

echo ============================================
echo OpenAI 非流式
echo ============================================
curl -s http://localhost:8081/v1/chat/completions -H "Content-Type: application/json" -H "Authorization: Bearer flx_sSez5v3G_hrVei-l3CY0xVFzdUH5t6LprWksS-Ez0" -d "{\"model\":\"Qwen3.5-4B\",\"messages\":[{\"role\":\"user\",\"content\":\"say hi\"}]}"

echo.
echo ============================================
echo OpenAI 流式 (SSE)
echo ============================================
curl -s -N http://localhost:8081/v1/chat/completions -H "Content-Type: application/json" -H "Authorization: Bearer flx_sSez5v3G_hrVei-l3CY0xVFzdUH5t6LprWksS-Ez0" -d "{\"model\":\"Qwen3.5-4B\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"say hi\"}]}"

echo.
echo ============================================
echo Anthropic 非流式
echo ============================================
curl -s http://localhost:8081/v1/messages -H "Content-Type: application/json" -H "x-api-key: flx_sSez5v3G_hrVei-l3CY0xVFzdUH5t6LprWksS-Ez0" -H "anthropic-version: 2023-06-01" -d "{\"model\":\"Qwen3.5-4B\",\"max_tokens\":128,\"messages\":[{\"role\":\"user\",\"content\":\"say hi\"}]}"

echo.
echo ============================================
echo Anthropic 流式 (SSE)
echo ============================================
curl -s -N http://localhost:8081/v1/messages -H "Content-Type: application/json" -H "x-api-key: flx_sSez5v3G_hrVei-l3CY0xVFzdUH5t6LprWksS-Ez0" -H "anthropic-version: 2023-06-01" -d "{\"model\":\"Qwen3.5-4B\",\"max_tokens\":128,\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"say hi\"}]}"
