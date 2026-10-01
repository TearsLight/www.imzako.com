[CmdletBinding()]
param(
    [string]$EnvironmentFile = (Join-Path $PSScriptRoot '..\.env')
)

$ErrorActionPreference = 'Stop'

function Read-DotEnv([string]$Path) {
    $values = [ordered]@{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $name, $value = $line -split '=', 2
        $values[$name.Trim()] = $value.Trim()
    }
    return $values
}

function Set-DotEnvValue([string]$Path, [string]$Name, [string]$Value) {
    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) { $lines.Add($line) }
    $prefix = "$Name="
    $index = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].StartsWith($prefix)) { $index = $i; break }
    }
    if ($index -ge 0) { $lines[$index] = "$prefix$Value" } else { $lines.Add("$prefix$Value") }
    [System.IO.File]::WriteAllLines($Path, $lines, [System.Text.UTF8Encoding]::new($false))
}

$config = Read-DotEnv $EnvironmentFile
$baseUrl = ([string]$config.BILLIONMAIL_API_URL).TrimEnd('/')
$adminToken = [string]$config.BILLIONMAIL_API_TOKEN
if ([string]::IsNullOrWhiteSpace($baseUrl) -or [string]::IsNullOrWhiteSpace($adminToken)) {
    throw 'BILLIONMAIL_API_URL and BILLIONMAIL_API_TOKEN must be configured in .env.'
}

$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(30)
$client.DefaultRequestHeaders.Authorization =
    [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $adminToken)

function Invoke-BillionMail([System.Net.Http.HttpMethod]$Method, [string]$Path, $Payload = $null) {
    $request = [System.Net.Http.HttpRequestMessage]::new($Method, "$baseUrl$Path")
    if ($null -ne $Payload) {
        $json = $Payload | ConvertTo-Json -Depth 12 -Compress
        $request.Content = [System.Net.Http.StringContent]::new(
            $json,
            [System.Text.Encoding]::UTF8,
            'application/json')
    }
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw "BillionMail returned HTTP $([int]$response.StatusCode)."
    }
    $result = $body | ConvertFrom-Json
    if (-not $result.success -or [int]$result.code -ne 0) {
        throw "BillionMail returned application code $($result.code)."
    }
    return $result
}

$templateName = 'imzako-verification-code'
$templatePath = Join-Path $PSScriptRoot '..\src\main\resources\mail\verification-code.html'
$html = Get-Content -LiteralPath $templatePath -Raw -Encoding UTF8
$encodedTemplateName = [Uri]::EscapeDataString($templateName)
$templates = Invoke-BillionMail ([System.Net.Http.HttpMethod]::Get) "/api/email_template/list?page=1&page_size=100&add_type=-1&keyword=$encodedTemplateName"
$template = @($templates.data.list | Where-Object { $_.temp_name -eq $templateName }) | Select-Object -First 1

if ($template) {
    Invoke-BillionMail ([System.Net.Http.HttpMethod]::Post) '/api/email_template/update' @{
        id = [int]$template.id
        temp_name = $templateName
        html_content = $html
        drag_data = ''
    } | Out-Null
    $templateId = [int]$template.id
    $templateAction = 'updated'
} else {
    $created = Invoke-BillionMail ([System.Net.Http.HttpMethod]::Post) '/api/email_template/create' @{
        temp_name = $templateName
        add_type = 0
        html_content = $html
        drag_data = ''
        chat_id = ''
    }
    $templateId = [int]$created.data.id
    $templateAction = 'created'
}

$apiName = 'imzako-verification-code-api'
$encodedApiName = [Uri]::EscapeDataString($apiName)
$apis = Invoke-BillionMail ([System.Net.Http.HttpMethod]::Get) "/api/batch_mail/api/list?page=1&page_size=100&active=-1&keyword=$encodedApiName"
$api = @($apis.data.list | Where-Object { $_.api_name -eq $apiName }) | Select-Object -First 1
$apiPayload = @{
    api_name = $apiName
    template_id = $templateId
    group_id = 0
    subject = '{{.API.code}} 是你的 imzako 验证码'
    addresser = 'noreply@imzako.com'
    full_name = 'imzako'
    unsubscribe = 0
    track_open = 0
    track_click = 0
    active = 1
    expire_time = 0
    ip_whitelist = @()
}

if ($api) {
    $apiPayload.id = [int]$api.id
    $apiPayload.reset_key = $false
    Invoke-BillionMail ([System.Net.Http.HttpMethod]::Post) '/api/batch_mail/api/update' $apiPayload | Out-Null
    $apiAction = 'updated'
} else {
    Invoke-BillionMail ([System.Net.Http.HttpMethod]::Post) '/api/batch_mail/api/create' $apiPayload | Out-Null
    $apiAction = 'created'
}

$apis = Invoke-BillionMail ([System.Net.Http.HttpMethod]::Get) "/api/batch_mail/api/list?page=1&page_size=100&active=-1&keyword=$encodedApiName"
$api = @($apis.data.list | Where-Object { $_.api_name -eq $apiName }) | Select-Object -First 1
if (-not $api -or [string]::IsNullOrWhiteSpace([string]$api.api_key)) {
    throw 'BillionMail did not return the send API key.'
}

Set-DotEnvValue $EnvironmentFile 'BILLIONMAIL_API_KEY' ([string]$api.api_key)
[pscustomobject]@{
    Template = $templateAction
    TemplateId = $templateId
    SendApi = $apiAction
    Sender = 'noreply@imzako.com'
    ApiKeyStored = $true
}
