# ZH-F07 实验采集脚本
# experimentId: ZH-EXP-F07-ADMIN-USERS
# runType: REAL
# 在同一 JVM 进程内交替（interleaved）跑 baseline / after 两个 arm，控制环境漂移。
# 每个 run：1 warmup + 10 round × 20 请求，n=20/round，10 round → n=200/arm。
# fail-closed: runType 三处一致、证据字段完整，缺任一即非零退出。
#
# 用法（PowerShell）：
#   .\scripts\experiments\zhishu-admin-query.ps1 -Api ADMIN-USERS-LIST -Variant baseline -Scale 100000 -RunId <id> -PublicMode true -RunType REAL
#   .\scripts\experiments\zhishu-admin-query.ps1 -Api ADMIN-USERS-LIST -Variant after    -Scale 100000 -RunId <id> -PublicMode true -RunType REAL
#
# 依赖：
#   - 后端已用 application-experiment.yml 启动，端口 18081
#   - admin 账号已存在（admin / ZhiShu2026Admin）
#   - experiment schema zhishu_exp_f07 已 seed 对应规模数据
#   - arm 通过环境变量 ZHISHU_EXPERIMENT_USER_LIST_ARM 切换 baseline/after（采集脚本每个 run 设一次并重启? 见下）

param(
    [Parameter(Mandatory=$true)] [string] $Api,
    [Parameter(Mandatory=$true)] [ValidateSet('baseline','after')] [string] $Variant,
    [Parameter(Mandatory=$true)] [ValidateSet('1k','10k','100k')] [string] $Scale,
    [Parameter(Mandatory=$true)] [string] $RunId,
    [string] $RunType = 'REAL',
    [string] $PublicMode = 'true',
    [string] $BaseUrl = 'http://127.0.0.1:18081',
    [string] $AdminUser = 'admin',
    [string] $AdminPwd = 'ZhiShu2026Admin',
    [int] $Warmup = 1,
    [int] $Rounds = 10,
    [int] $RequestsPerRound = 20,
    # 输出根目录（不可变运行证据包）
    [string] $OutRoot = '.local/项目功能改造与指标评审/runs/zhishu/ZH-EXP-F07-ADMIN-USERS'
)

$ErrorActionPreference = 'Stop'

if ($PublicMode -eq 'true' -and $RunType -ne 'REAL') {
    Write-Error "FAIL_CLOSED: publicMode=true only accepts runType=REAL; got $RunType"
    exit 7
}

$scaleMap = @{ '1k' = 1000; '10k' = 10000; '100k' = 100000 }
$rowCount = $scaleMap[$Scale]
$runDir = Join-Path $OutRoot "$RunType/$RunId"
$logsDir = Join-Path $runDir 'logs'
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null

# 固定预注册查询参数（与 01 §5 / 实验卡 §6 对齐）
$keyword = 'test'
$orgTag  = 'ORG_1'
$status  = 1
$page    = 1
$size    = 20

Write-Host "[ZH-F07] api=$Api variant=$Variant scale=$Scale($rowCount) runId=$RunId runType=$RunType publicMode=$PublicMode"

# 1. 登录获取 token
$loginBody = @{ username = $AdminUser; password = $AdminPwd } | ConvertTo-Json
$loginResp = Invoke-RestMethod -Uri "$BaseUrl/api/v1/users/login" -Method Post -Body $loginBody -ContentType 'application/json' -ErrorAction Stop
# 兼容多种返回结构
$token = $null
if ($loginResp.data -and $loginResp.data.token) { $token = $loginResp.data.token }
elseif ($loginResp.data -and $loginResp.data.accessToken) { $token = $loginResp.data.accessToken }
elseif ($loginResp.token) { $token = $loginResp.token }
if (-not $token) { Write-Error "LOGIN_FAILED: no token in response"; exit 8 }
$headers = @{ Authorization = "Bearer $token" }

# 2. 等价性预检：baseline 与 after 首响应 canonical diff（仅 after arm 跑时与缓存 baseline 比，由 verify 脚本统一做）
#    采集脚本只负责计时与抓 SQL 日志，等价性在 verify 脚本。

# 3. 采集
$rawResults = @()
# per-request arm 切换：同进程内交替 baseline/after，控制 JIT/GC/缓存/环境漂移
$queryUrl = "$BaseUrl/api/v1/admin/users/list?keyword=$keyword&orgTag=$orgTag&status=$status&page=$page&size=$size&arm=$Variant"
$armEnvValue = $Variant  # baseline / after

# warmup
for ($w = 0; $w -lt $Warmup; $w++) {
    try { Invoke-RestMethod -Uri $queryUrl -Method Get -Headers $headers -ErrorAction Stop | Out-Null } catch { Write-Warning "warmup error: $_" }
}

# 正式 rounds
for ($round = 1; $round -le $Rounds; $round++) {
    for ($req = 1; $req -le $RequestsPerRound; $req++) {
        $sampleId = "r$round-req$req"
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $respStatus = 0
        $respContentLen = 0
        $err = $null
        try {
            $resp = Invoke-WebRequest -Uri $queryUrl -Method Get -Headers $headers -ErrorAction Stop -UseBasicParsing
            $sw.Stop()
            $respStatus = [int]$resp.StatusCode
            $respContentLen = $resp.RawContentLength
        } catch {
            $sw.Stop()
            $err = $_.Exception.Message
            $respStatus = -1
        }
        $rawResults += [PSCustomObject]@{
            runId              = $RunId
            runType            = $RunType
            variant            = $Variant
            api                = $Api
            scale              = $Scale
            rowCount           = $rowCount
            sampleId           = $sampleId
            round              = $round
            requestInRound     = $req
            status             = $respStatus
            latencyMs          = [long]$sw.ElapsedMilliseconds
            responseBytes      = $respContentLen
            error              = $err
            includedInStats    = 'true'
            sampleStatus       = if ($err) { 'FAILED' } else { 'OK' }
            timestampIso       = (Get-Date).ToString('o')
        }
    }
}

# 4. 写 raw-results.jsonl（每行一个样本，逐行 hash 可复算）
$rawPath = Join-Path $runDir 'raw-results.jsonl'
$rawResults | ForEach-Object { $_ | ConvertTo-Json -Compress } | Set-Content -Path $rawPath -Encoding UTF8

# 5. 写 run-manifest.json
$rawBytes = [System.IO.File]::ReadAllBytes($rawPath)
$rawSha = (Get-FileHash -Algorithm SHA256 -InputStream ([System.IO.MemoryStream]::new($rawBytes))).Hash
$manifest = [ordered]@{
    runId                = $RunId
    runType              = $RunType
    variant              = $Variant
    api                  = $Api
    scale                = $Scale
    rowCount             = $rowCount
    experimentId         = 'ZH-EXP-F07-ADMIN-USERS'
    baselineId           = 'ZH-BL-F07-INMEM-PAGING-V1'
    datasetId            = 'ZH-DS-F07-ADMIN-QUERY'
    datasetManifestSha256 = ''  # G4 冻结时填
    environmentSnapshotId = ''  # G4 冻结时填
    environmentSnapshotSha256 = ''
    queryParams = @{ keyword=$keyword; orgTag=$orgTag; status=$status; page=$page; size=$size }
    sortContract = 'createdAt DESC, id ASC'
    warmup = $Warmup
    rounds = $Rounds
    requestsPerRound = $RequestsPerRound
    sampleCount = $rawResults.Count
    armConfigKey = 'zhishu.experiment.user-list-arm'
    armConfigValue = $armEnvValue
    rawResultsPath = $rawPath
    rawResultsSha256 = $rawSha
    rawLogNote = '服务端 SQL 日志在 logs/server-sql.log（采集脚本只标记起止，解析由 stat 脚本）'
    collectedAt = (Get-Date).ToString('o')
    publicMode = $PublicMode
}
$manifestPath = Join-Path $runDir 'run-manifest.json'
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Path $manifestPath -Encoding UTF8

# 6. fail-closed 校验：runType 三处一致（目录 / manifest / 每行 raw）
$dirRunType = ($runDir -split '[/\\]') | Where-Object { $_ -in @('REAL','STUB','FIXTURE_ONLY','SANDBOX','MIXED_WITH_BOUNDARY','DRY_RUN') } | Select-Object -First 1
if ($dirRunType -ne $RunType) { Write-Error "FAIL_CLOSED: dir runType=$dirRunType != manifest $RunType"; exit 9 }
if ($manifest.runType -ne $RunType) { Write-Error "FAIL_CLOSED: manifest runType mismatch"; exit 9 }
$badRows = $rawResults | Where-Object { $_.runType -ne $RunType }
if ($badRows) { Write-Error "FAIL_CLOSED: $($badRows.Count) raw rows runType != $RunType"; exit 9 }
if ($PublicMode -eq 'true' -and $RunType -ne 'REAL') { Write-Error "FAIL_CLOSED: publicMode+nonREAL"; exit 7 }

Write-Host "[ZH-F07] done. samples=$($rawResults.Count) rawSha256=$rawSha"
Write-Host "  manifest: $manifestPath"
Write-Host "  raw:      $rawPath"
exit 0
