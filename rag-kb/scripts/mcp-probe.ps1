# MCP Server E2E 探针（Streamable HTTP 传输，PRO-01 迁移后版本）：模拟一个最小 MCP 客户端
# ① POST /mcp initialize → 从响应头拿 Mcp-Session-Id  ② notifications/initialized  ③ tools/list 发现工具  ④ tools/call 真执行
# 与旧 SSE 版差异：不再需要"长连接收流+URL 里带 sessionId"——会话由响应头 Mcp-Session-Id 维系，
# 每条 JSON-RPC 请求 POST 到同一端点，响应体按规范可能是 application/json 或 SSE 帧（正则对原文匹配，两种通吃）
$ErrorActionPreference = "Stop"
$base = "http://localhost:8000"
$mcpUrl = "$base/mcp"

function Rpc($bodyObj, $sessionId) {
    $h = @{ "Content-Type" = "application/json"; "Accept" = "application/json, text/event-stream" }
    if ($sessionId) { $h["Mcp-Session-Id"] = [string]$sessionId }
    $json = $bodyObj | ConvertTo-Json -Depth 8 -Compress
    Invoke-WebRequest -UseBasicParsing -Uri $mcpUrl -Method Post -Headers $h -Body $json
}

# ① initialize：服务端分配会话（Streamable 协议版本 2025-03-26）
$resp = Rpc @{ jsonrpc = "2.0"; id = 1; method = "initialize"; params = @{
    protocolVersion = "2025-03-26"; capabilities = @{}
    clientInfo = @{ name = "ps-probe"; version = "0.0.2" } } } $null
$sid = $resp.Headers["Mcp-Session-Id"] | Select-Object -First 1
if (-not $sid) { throw "initialize 响应缺 Mcp-Session-Id 头——检查 protocol 是否已切 STREAMABLE" }
"sessionId: $sid"

Rpc @{ jsonrpc = "2.0"; method = "notifications/initialized" } $sid | Out-Null

# ③ tools/list：客户端能自动发现全部工具
$tl = (Rpc @{ jsonrpc = "2.0"; id = 2; method = "tools/list"; params = @{} } $sid).Content
"--- tools/list result ---"
if ($tl -match '"tools":\[(.{0,600})') { $Matches[1] + " ..." }
$toolCount = ([regex]::Matches($tl, '"name":"(kb_|eval_|judge_)')).Count
"tool names seen: $toolCount"

# ④ tools/call：挑一个不依赖 LLM 的工具真执行——评测机守门校验
$call = (Rpc @{ jsonrpc = "2.0"; id = 3; method = "tools/call"; params = @{
    name = "eval_dataset_validate"; arguments = @{} } } $sid).Content
"--- tools/call eval_dataset_validate result ---"
if ($call -match '"content":\[(.{0,500})') { $Matches[1] }
