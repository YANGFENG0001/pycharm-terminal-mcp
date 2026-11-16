#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][uri]$ServerUrl,
    [string]$ProjectPath,
    [switch]$FollowCurrentProject,
    [string]$ExpectedProjectPath,
    [string]$ExpectedVersion = '0.1.53'
)

$ErrorActionPreference = 'Stop'
if ([bool]$FollowCurrentProject -eq ![string]::IsNullOrWhiteSpace($ProjectPath)) {
    throw 'Choose exactly one route: -FollowCurrentProject or -ProjectPath.'
}
if (!$ServerUrl.IsLoopback -or $ServerUrl.Scheme -ne 'http' -or $ServerUrl.AbsolutePath -ne '/stream') {
    throw 'Use the current local IDE Streamable HTTP URL from the connection menu; do not supply a remote server.'
}
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$headers = @{
    Accept = 'application/json, text/event-stream'
    IJ_MCP_SERVER_PROJECT_PATH = $(if ($FollowCurrentProject) { 'terminal-mcp:current-project' } else { $ProjectPath })
}
$script:requestId = 0

function Invoke-McpRequest([string]$Method, [hashtable]$Params) {
    $script:requestId++
    $body = @{ jsonrpc = '2.0'; id = $script:requestId; method = $Method; params = $Params } |
        ConvertTo-Json -Depth 12 -Compress
    $response = Invoke-WebRequest -NoProxy -WebSession $session -Uri $ServerUrl -Method Post `
        -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 20
    if ($response.Headers['Mcp-Session-Id']) {
        $headers['Mcp-Session-Id'] = [string]($response.Headers['Mcp-Session-Id'] | Select-Object -First 1)
    }
    $json = $response.Content | ConvertFrom-Json
    if ($json.error) { throw "MCP $Method failed: $($json.error.message)" }
    if ($json.result.isError) {
        throw "MCP $Method returned isError=true; stop and inspect the IDE project route and idea.log."
    }
    return $json.result
}

try {
    $null = Invoke-McpRequest 'initialize' @{
        protocolVersion = '2025-03-26'
        capabilities = @{}
        clientInfo = @{ name = 'terminal-bridge-readonly-diagnostic'; version = '0.1.53' }
    }
    $null = Invoke-WebRequest -NoProxy -WebSession $session -Uri $ServerUrl -Method Post `
        -Headers $headers -ContentType 'application/json' `
        -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' -TimeoutSec 20
    $tools = (Invoke-McpRequest 'tools/list' @{}).tools
    foreach ($name in @('get_terminal_mcp_capabilities', 'get_terminal_mcp_settings', 'ensure_saved_ssh_terminal')) {
        if ($name -notin $tools.name) { throw "Required tool not discoverable: $name" }
    }
    $routeArguments = @{}
    if (!$FollowCurrentProject) { $routeArguments.projectPath = $ProjectPath }
    $result = Invoke-McpRequest 'tools/call' @{ name = 'get_terminal_mcp_capabilities'; arguments = $routeArguments }
    $capabilities = $result.structuredContent
    if (!$capabilities) { $capabilities = $result.content[0].text | ConvertFrom-Json }
    if ($capabilities.pluginVersion -ne $ExpectedVersion) {
        throw "Runtime version $($capabilities.pluginVersion) differs from expected $ExpectedVersion; stop acceptance."
    }
    if ($ExpectedProjectPath -and (!$capabilities.projectPath -or
        [IO.Path]::GetFullPath($capabilities.projectPath).TrimEnd('/','\') -ine
        [IO.Path]::GetFullPath($ExpectedProjectPath).TrimEnd('/','\'))) {
        throw "Actual IDE project $($capabilities.projectPath) differs from expected $ExpectedProjectPath."
    }
    $null = Invoke-McpRequest 'tools/call' @{
        name = 'get_terminal_mcp_settings'; arguments = $routeArguments
    }
    [pscustomobject]@{
        pluginVersion = $capabilities.pluginVersion
        projectPath = $capabilities.projectPath
        toolCount = $tools.Count
        capabilities = 'passed'
        settings = 'passed'
        projectRouting = 'passed'
        scope = 'read-only MCP connection; no terminal commands, transfers, credentials, or settings changes'
    }
} finally {
    if ($headers['Mcp-Session-Id']) {
        try {
            $null = Invoke-WebRequest -NoProxy -WebSession $session -Uri $ServerUrl -Method Delete `
                -Headers $headers -TimeoutSec 5
        } catch { Write-Verbose 'The IDE did not accept session cleanup; no request was retried.' }
    }
}
