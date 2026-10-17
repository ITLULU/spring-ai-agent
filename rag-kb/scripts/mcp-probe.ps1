# MCP Server E2E 探针：模拟一个最小 MCP 客户端
# ① 打开 SSE 通道拿到 sessionId  ② initialize 握手  ③ tools/list 发现工具  ④ tools/call 真执行
$ErrorActionPreference = "Stop"
$base = "http://localhost:8000"
$sseFile = "output\mcp-sse.txt"
Remove-Item $sseFile -ErrorAction SilentlyContinue

# ① SSE 监听（后台 curl，保持长连接，服务器把 JSON-RPC 响应推回这条流）
$curl = Start-Process curl.exe -ArgumentList "-N","-s","--max-time","40","$base/sse","-o",$sseFile -PassThru -WindowStyle Hidden

# 等 endpoint 事件出现并提取 sessionId
$sid = $null
for ($i = 0; $i -lt 40 -and -not $sid; $i++) {
    Start-Sleep -Milliseconds 500
    if (Test-Path $sseFile) {
        $m = Select-String -Path $sseFile -Pattern "sessionId=([0-9a-f-]+)" | Select-Object -First 1
        if ($m) { $sid = $m.Matches[0].Groups[1].Value }
    }
}
if (-not $sid) { throw "no sessionId from /sse" }
"sessionId: $sid"

$msg = "$base/mcp/message?sessionId=$sid"
$h = @{ "Content-Type" = "application/json"; "Accept" = "application/json, text/event-stream" }

function Rpc($bodyObj) {
    $json = $bodyObj | ConvertTo-Json -Depth 8 -Compress
    Invoke-WebRequest -UseBasicParsing -Uri $msg -Method Post -Headers $h -Body $json | Out-Null
}

# ② initialize 握手
Rpc @{ jsonrpc = "2.0"; id = 1; method = "initialize"; params = @{
    protocolVersion = "2024-11-05"; capabilities = @{}
    clientInfo = @{ name = "ps-probe"; version = "0.0.1" } } }
Rpc @{ jsonrpc = "2.0"; method = "notifications/initialized" }

# ③ tools/list：客户端能自动发现全部工具
Rpc @{ jsonrpc = "2.0"; id = 2; method = "tools/list"; params = @{} }

# ④ tools/call：挑一个不依赖 LLM 的工具真执行——评测机守门校验
Rpc @{ jsonrpc = "2.0"; id = 3; method = "tools/call"; params = @{
    name = "eval_dataset_validate"; arguments = @{} } }

# 等响应推流
Start-Sleep -Seconds 4
Stop-Process -Id $curl.Id -Force -ErrorAction SilentlyContinue

$transcript = Get-Content $sseFile -Raw
"--- tools/list result ---"
if ($transcript -match '"tools":\[(.{0,600})') { $Matches[1] + " ..." }
"--- tools/call eval_dataset_validate result ---"
if ($transcript -match '"content":\[(.{0,500})') { $Matches[1] }
$toolCount = ([regex]::Matches($transcript, '"name":"(kb_|eval_|judge_)')).Count
"tool names seen: $toolCount"
