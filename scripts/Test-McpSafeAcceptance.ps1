#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][uri]$ServerUrl,
    [Parameter(Mandatory)][string]$ProjectPath,
    [string]$ExpectedVersion = '0.1.53',
    [string]$SshServerHost,
    [string]$EvidencePath = "$PSScriptRoot/../build/reports/mcp-live-acceptance.json"
)

$ErrorActionPreference = 'Stop'
if (!$ServerUrl.IsLoopback -or $ServerUrl.Scheme -ne 'http' -or $ServerUrl.AbsolutePath -ne '/stream') {
    throw 'Only the discovered local IDE /stream URL is accepted.'
}
$headers = @{ Accept = 'application/json, text/event-stream'; IJ_MCP_SERVER_PROJECT_PATH = $ProjectPath }
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$records = [Collections.Generic.List[object]]::new()
$requestId = 0
$null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($EvidencePath)))

function Save-Evidence {
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($EvidencePath),
        (ConvertTo-Json -InputObject @($records.ToArray()) -Depth 60), [Text.UTF8Encoding]::new($false))
}
function Request([string]$Method, [hashtable]$Params = @{}, [int]$Timeout = 30) {
    $script:requestId++
    $request = @{ jsonrpc = '2.0'; id = $script:requestId; method = $Method; params = $Params }
    $record = [ordered]@{ time = [DateTimeOffset]::Now.ToString('o'); request = $request; response = $null }
    try {
        $response = Invoke-WebRequest -NoProxy -WebSession $session -Uri $ServerUrl -Method Post -Headers $headers `
            -ContentType 'application/json' -Body ($request | ConvertTo-Json -Depth 30 -Compress) -TimeoutSec $Timeout
        if ($response.Headers['Mcp-Session-Id']) {
            $headers['Mcp-Session-Id'] = [string]($response.Headers['Mcp-Session-Id'] | Select-Object -First 1)
        }
        $record.response = $response.Content | ConvertFrom-Json -Depth 60
        return $record.response
    } catch {
        $record['transportError'] = $_.Exception.Message
        throw
    } finally {
        $records.Add($record)
        Save-Evidence
    }
}
function Call([string]$Name, [hashtable]$Arguments = @{}, [int]$Timeout = 30) {
    Request 'tools/call' @{ name = $Name; arguments = $Arguments } $Timeout
}
function Value($Response) {
    if ($Response.error -or $Response.result.isError) { throw ($Response | ConvertTo-Json -Depth 10 -Compress) }
    if ($Response.result.structuredContent) { return $Response.result.structuredContent }
    $Response.result.content[0].text | ConvertFrom-Json -Depth 40
}
function Refused([string]$Name, [hashtable]$Arguments) {
    $r = Call $Name $Arguments
    $v = if (!$r.error -and !$r.result.isError) { Value $r } else { $null }
    if (!$r.error -and !$r.result.isError -and $v.success -ne $false) { throw "Unexpected non-refusal: $Name" }
    Write-Output "REFUSAL PASS: $Name"
}

try {
    $null = Request 'initialize' @{ protocolVersion = '2025-03-26'; capabilities = @{};
        clientInfo = @{ name = 'terminal-bridge-safe-acceptance'; version = '1' } }
    $null = Invoke-WebRequest -NoProxy -WebSession $session -Uri $ServerUrl -Method Post -Headers $headers `
        -ContentType 'application/json' -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' -TimeoutSec 10
    $tools = (Request 'tools/list').result.tools
    $capabilities = Value (Call 'get_terminal_mcp_capabilities')
    if ($capabilities.pluginVersion -ne $ExpectedVersion) { throw "Version mismatch: $($capabilities.pluginVersion)" }
    if ([IO.Path]::GetFullPath($capabilities.projectPath).TrimEnd('/','\') -ine
        [IO.Path]::GetFullPath($ProjectPath).TrimEnd('/','\')) { throw 'Project route mismatch.' }
    $settings = Value (Call 'get_terminal_mcp_settings')
    $servers = Value (Call 'list_selected_deployment_servers')
    $null = Value (Call 'list_remote_servers')
    $null = Value (Call 'list_predefined_terminals')
    $null = Value (Call 'list_deployment_configurations')
    $terminals = Value (Call 'list_global_terminals')
    Write-Output "DISCOVERY PASS: version=$($capabilities.pluginVersion), tools=$($tools.Count), project=$($capabilities.projectPath)"
    Refused 'upload_to_selected_deployment_server' @{}
    Refused 'upload_entire_project_to_deployment_server' @{ allow_full_project = $false }
    Refused 'upload_selected_files_to_deployment_server' @{ local_paths = @() }
    Refused 'upload_selected_files_to_deployment_server' @{ local_paths = @($ProjectPath) }
    Refused 'transfer_selected_deployment_server' @{ mode = 'upload' }
    Refused 'invoke_selected_deployment_action' @{ action = 'sync' }
    Refused 'transfer_remote_directory' @{ endpoint_selector = 'acceptance-nonexistent-endpoint'; local_path = $ProjectPath;
        remote_path = '/tmp/acceptance-never-written'; mode = 'upload' }
    Refused 'execute_global_terminal_command' @{ terminal_id = 'acceptance-nonexistent-terminal'; command = 'true' }
    Refused 'read_global_terminal' @{ terminal_id = 'acceptance-nonexistent-terminal' }
    Refused 'send_global_terminal_text' @{ terminal_id = 'acceptance-nonexistent-terminal'; text = '' }
    Refused 'interrupt_global_terminal' @{ terminal_id = 'acceptance-nonexistent-terminal' }
    Refused 'create_predefined_terminal' @{ selector = 'acceptance-nonexistent-preset' }
    Refused 'ensure_saved_ssh_terminal' @{ server_selector = 'acceptance-nonexistent-server'; timeout_ms = 1000 }
    Refused 'select_deployment_server' @{ server_selector = 'acceptance-nonexistent-server' }
    Refused 'run_deployment_configuration' @{ selector = 'acceptance-nonexistent-configuration' }
    if ($SshServerHost) {
        $matched = @($servers.targets | Where-Object { $_.host -eq $SshServerHost -and $_.valid })
        if ($matched.Count -ne 1) { throw 'Requested host must uniquely match a discovered valid server.' }
        $ready = Value (Call 'ensure_saved_ssh_terminal' @{ server_selector = $matched[0].id; timeout_ms = 20000 } 45)
        if (!$ready.ready) { Write-Output "SSH BLOCKED: $($ready.status): $($ready.message)"; return }
        $id = $ready.terminal.logical_id
        foreach ($case in @(
            @{ command = "printf 'MCP_ACCEPT_OK\n'"; code = 0; output = 'MCP_ACCEPT_OK' },
            @{ command = "printf 'MCP_ACCEPT_EXIT\n'; exit 7"; code = 7; output = 'MCP_ACCEPT_EXIT' },
            @{ command = 'false'; code = 1; output = '' },
            @{ command = "printf 'MCP_ACCEPT_ALIVE\n'"; code = 0; output = 'MCP_ACCEPT_ALIVE' }
        )) {
            $r = Value (Call 'execute_global_terminal_command' @{ terminal_id = $id; command = $case.command; timeout_ms = 10000 })
            if (!$r.completed -or $r.exit_code -ne $case.code -or $r.success -ne ($case.code -eq 0)) { throw 'Execution contract failed.' }
            if ($null -eq $r.output -or $r.output.Trim() -cne $case.output) { throw 'Execution output isolation failed.' }
        }
        Write-Output 'SSH EXECUTION PASS: completion, output, nonzero exit, shell survives'
    }
} finally {
    Save-Evidence
    if ($headers['Mcp-Session-Id']) {
        try { $null = Invoke-WebRequest -NoProxy -WebSession $session -Uri $ServerUrl -Method Delete -Headers $headers -TimeoutSec 3 } catch {}
    }
    Write-Output "Evidence: $([IO.Path]::GetFullPath($EvidencePath))"
}
