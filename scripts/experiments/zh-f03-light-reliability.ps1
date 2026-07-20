# ZH-F03 轻量可靠闭环故障实验编排器
# experimentId: ZH-EXP-F03-LIGHT-RELIABILITY
# baselineId: ZH-BL-F03-CURRENT-DIRECT-KAFKA-V2
#
# REAL 运行职责：
# 1. 在样本开始前探测应用、MySQL、Kafka、MinIO、Elasticsearch；
# 2. 每个 case/attempt 建立独立不可变目录；
# 3. 只调用随源码冻结的 Python driver 执行实际故障注入并写 raw-state.json；
# 4. 冻结 command/environment/raw/log 的 SHA-256 manifest。
#
# Driver 只读取受限 JSON 配置，不接受任意命令或 hook。DriverScript 参数仅用于
# 兼容调用方，若它不是同目录的固定 driver，编排器会在采样前 fail closed。

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('REAL', 'FIXTURE_ONLY', 'STUB')]
    [string] $RunType,

    [Parameter(Mandatory = $true)]
    [ValidateSet('all', 'F03-L-001', 'F03-L-002', 'F03-L-003', 'F03-L-004', 'F03-L-005', 'F03-L-006')]
    [string] $Case,

    [ValidateSet('true', 'false')]
    [string] $PublicMode = 'true',

    [int] $Attempts = 0,
    [string] $DriverScript = '',
    [string] $DriverConfigPath = '',
    [string] $BuildArtifactPath = '',
    [string] $DatasetManifestPath = '',
    [ValidateSet('DOCKER', 'WINDOWS_LOCAL')]
    [string] $RuntimeMode = 'DOCKER',
    [string] $ApplicationEnvPath = '',
    [string] $KafkaHome = 'D:\kafka',
    [string] $KafkaConfigPath = '',
    [string] $NativeStatePath = '',
    [string] $RuntimeLogDirectory = '',
    [int] $ExperimentLeaseSeconds = 5,
    [int] $ExperimentReaperInitialDelayMs = 1000,
    [int] $ExperimentReaperScanDelayMs = 1000,
    [ValidateSet('COLD', 'HOT', 'RESET', 'NOT_APPLICABLE')]
    [string] $CacheState = 'NOT_APPLICABLE',
    [string] $OutRoot = '.local/项目功能改造与指标评审/runs/zhishu/ZH-EXP-F03-LIGHT-RELIABILITY',
    [string] $ApplicationHealthUrl = 'http://127.0.0.1:8081/api/v1/health',
    [string] $MySqlHost = '127.0.0.1',
    [int] $MySqlPort = 3306,
    [string] $MySqlDatabase = 'zhishu_f03',
    [int] $RedisDatabase = 13,
    [string] $KafkaHost = '127.0.0.1',
    [int] $KafkaPort = 9092,
    [string] $MinioHost = '127.0.0.1',
    [int] $MinioPort = 9000,
    [string] $MinioBucket = 'zhishu-f03',
    [string] $ElasticsearchHost = '127.0.0.1',
    [int] $ElasticsearchPort = 9200,
    [switch] $ValidateOnly
)

$ErrorActionPreference = 'Stop'
$experimentId = 'ZH-EXP-F03-LIGHT-RELIABILITY'
$baselineId = 'ZH-BL-F03-CURRENT-DIRECT-KAFKA-V2'
$featureId = 'ZH-F03'
$cardSectionId = 'ZH-CARD-F03-LIGHT-V3'
$experimentDesignCardSha256 = '0F20A9F7A0FDD16E2DD1F1E5442EAAC35C0D7CEC9B72934DB2FFC58369249474'
$cardSectionSha256 = '1FE1594C2DC1405F3205EA716FE2D48BA3ECB3254EC044910C0AACC4A5DBCB8F'
$plannedHarnessSpecHash = $cardSectionSha256
$executionProtocolVersion = 'ZH-F03-LIGHT/1'
$caseIds = @('F03-L-001', 'F03-L-002', 'F03-L-003', 'F03-L-004', 'F03-L-005', 'F03-L-006')
$fixedDriverPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'zh_f03_real_fault_driver.py')).Path
$fixedNativeControllerPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'zh_f03_native_process_control.py')).Path
$fixedAuthPreparationPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'zh_f03_auth_prepare.py')).Path
$driverRequirementsPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'requirements-zh-f03-real.txt')).Path
$metricContractPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'zh-f03-light-metric-contract.ps1')).Path
. $metricContractPath
if ($DriverScript) {
    $requestedDriverPath = (Resolve-Path -LiteralPath $DriverScript).Path
    if ($requestedDriverPath -ne $fixedDriverPath) {
        Write-Error "FAIL_CLOSED: REAL fault logic is frozen to $fixedDriverPath; arbitrary DriverScript is forbidden"
        exit 11
    }
}
$runTypeDirectory = switch ($RunType) {
    'REAL' { 'real' }
    'FIXTURE_ONLY' { 'fixture-only' }
    'STUB' { 'stub' }
}

if ($PublicMode -eq 'true' -and $RunType -ne 'REAL') {
    Write-Error "FAIL_CLOSED: publicMode=true only accepts runType=REAL; got $RunType"
    exit 7
}
if ($Attempts -le 0) {
    if ($RunType -eq 'REAL') { $Attempts = 3 } else { $Attempts = 1 }
}
if ($Attempts -lt 1) {
    Write-Error 'FAIL_CLOSED: Attempts must be >= 1'
    exit 8
}

if ($ExperimentLeaseSeconds -le 0 -or
        $ExperimentReaperInitialDelayMs -le 0 -or
        $ExperimentReaperScanDelayMs -le 0) {
    Write-Error 'FAIL_CLOSED: experiment lease/reaper timing values must all be > 0'
    exit 16
}
$leaseRecoveryBudgetSeconds = $ExperimentLeaseSeconds +
        [Math]::Ceiling($ExperimentReaperInitialDelayMs / 1000.0) +
        [Math]::Ceiling($ExperimentReaperScanDelayMs / 1000.0) + 10
if ($leaseRecoveryBudgetSeconds -ge 120) {
    Write-Error 'FAIL_CLOSED: experiment lease/reaper timing does not fit the F03-L-003 120-second observation window'
    exit 17
}
$experimentTiming = [ordered]@{
    leaseSeconds = $ExperimentLeaseSeconds
    reaperInitialDelayMs = $ExperimentReaperInitialDelayMs
    reaperScanDelayMs = $ExperimentReaperScanDelayMs
    f03L003WatchdogSeconds = 120
    recoveryBudgetSeconds = $leaseRecoveryBudgetSeconds
    appliedToBackend = $RuntimeMode -eq 'WINDOWS_LOCAL'
}

if ($RuntimeMode -eq 'WINDOWS_LOCAL') {
    if (-not $ApplicationEnvPath -or -not (Test-Path -LiteralPath $ApplicationEnvPath -PathType Leaf)) {
        Write-Error 'FAIL_CLOSED: WINDOWS_LOCAL requires a readable -ApplicationEnvPath'
        exit 13
    }
    if (-not (Test-Path -LiteralPath $KafkaHome -PathType Container)) {
        Write-Error 'FAIL_CLOSED: WINDOWS_LOCAL requires a valid -KafkaHome'
        exit 14
    }
    if (-not $KafkaConfigPath -or -not (Test-Path -LiteralPath $KafkaConfigPath -PathType Leaf)) {
        Write-Error 'FAIL_CLOSED: WINDOWS_LOCAL requires a readable -KafkaConfigPath'
        exit 15
    }
    $nativeStatePathIsFullyQualified = $NativeStatePath -match '^(?:[A-Za-z]:[\\/]|[\\/]{2}[^\\/]+[\\/][^\\/]+(?:[\\/]|$))'
    if (-not $NativeStatePath -or -not $nativeStatePathIsFullyQualified) {
        Write-Error 'FAIL_CLOSED: WINDOWS_LOCAL requires a stable -NativeStatePath outside per-batch OutRoot derivation'
        exit 18
    }
    if ($RunType -eq 'REAL') {
        if (-not $BuildArtifactPath -or -not (Test-Path -LiteralPath $BuildArtifactPath -PathType Leaf)) {
            Write-Error 'FAIL_CLOSED: WINDOWS_LOCAL REAL requires -BuildArtifactPath'
            exit 9
        }
        if (-not $DatasetManifestPath -or -not (Test-Path -LiteralPath $DatasetManifestPath -PathType Leaf)) {
            Write-Error 'FAIL_CLOSED: WINDOWS_LOCAL REAL requires -DatasetManifestPath'
            exit 10
        }
        if (-not $DriverConfigPath -or -not (Test-Path -LiteralPath $DriverConfigPath -PathType Leaf)) {
            Write-Error 'FAIL_CLOSED: WINDOWS_LOCAL REAL requires -DriverConfigPath'
            exit 12
        }
    }
}

if ($ValidateOnly) {
    Write-Host "VALID: runType=$RunType case=$Case attempts=$Attempts publicMode=$PublicMode runtimeMode=$RuntimeMode"
    exit 0
}

function Get-Sha256Text([string] $Text) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '')
    } finally {
        $sha.Dispose()
    }
}

function Get-GitDirtyIdentity {
    $previousErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $trackedDiff = (& git diff --binary HEAD 2>$null | Out-String)
        $untrackedLines = New-Object System.Collections.Generic.List[string]
        $untracked = @(& git ls-files --others --exclude-standard 2>$null | Sort-Object)
        foreach ($relative in $untracked) {
            if (-not $relative) { continue }
            $absolute = Join-Path (Get-Location).Path $relative
            if (Test-Path -LiteralPath $absolute -PathType Leaf) {
                $hash = (Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash
                $untrackedLines.Add("$relative`t$hash")
            }
        }
        return Get-Sha256Text ($trackedDiff + "`n--UNTRACKED--`n" + ($untrackedLines -join "`n"))
    } finally {
        $ErrorActionPreference = $previousErrorPreference
    }
}

function Test-TcpEndpoint([string] $Name, [string] $HostName, [int] $Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(2000, $false)) {
            return [ordered]@{ name = $Name; endpoint = "$HostName`:$Port"; ok = $false; error = 'timeout' }
        }
        $client.EndConnect($pending)
        return [ordered]@{ name = $Name; endpoint = "$HostName`:$Port"; ok = $true; error = $null }
    } catch {
        return [ordered]@{ name = $Name; endpoint = "$HostName`:$Port"; ok = $false; error = $_.Exception.Message }
    } finally {
        $client.Dispose()
    }
}

function Test-HttpEndpoint([string] $Name, [string] $Url) {
    try {
        $response = Invoke-WebRequest -Uri $Url -Method Get -UseBasicParsing -TimeoutSec 3
        return [ordered]@{ name = $Name; endpoint = $Url; ok = $true; status = [int]$response.StatusCode; error = $null }
    } catch {
        $status = $null
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $status = [int]$_.Exception.Response.StatusCode
        }
        $reachable = $status -in @(200, 401, 403)
        return [ordered]@{ name = $Name; endpoint = $Url; ok = $reachable; status = $status; error = $_.Exception.Message }
    }
}

function Get-DotEnvValue([string] $Path, [string] $Name) {
    foreach ($rawLine in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#') -or -not $line.Contains('=')) { continue }
        $separatorIndex = $line.IndexOf('=')
        if ($separatorIndex -lt 1) { continue }
        if ($line.Substring(0, $separatorIndex).Trim() -ne $Name) { continue }
        $value = $line.Substring($separatorIndex + 1).Trim()
        if ($value.Length -ge 2 -and
                (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                 ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        return $value
    }
    return $null
}

function New-MySqlJdbcUrl([string] $MySqlHost, [int] $MySqlPort, [string] $MySqlDatabase) {
    return "jdbc:mysql://${MySqlHost}:${MySqlPort}/${MySqlDatabase}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
}

function Wait-TcpEndpoint([string] $Name, [string] $HostName, [int] $Port, [int] $TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $result = Test-TcpEndpoint $Name $HostName $Port
        if ($result.ok) { return $result }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "FAIL_CLOSED: $Name did not become ready at $HostName`:$Port"
}

function Wait-HttpEndpoint([string] $Name, [string] $Url, [int] $TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $result = Test-HttpEndpoint $Name $Url
        if ($result.ok) { return $result }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "FAIL_CLOSED: $Name did not become ready at $Url"
}

function Get-CaseAssertions([string] $CaseId, [bool] $Value) {
    switch ($CaseId) {
        'F03-L-001' { return [ordered]@{ outboxPublished = $Value; taskProcessingOpportunity = $Value } }
        'F03-L-002' { return [ordered]@{ duplicateDeliveryObserved = $Value; hashConflictRejected = $Value; esIdSetUnchanged = $Value } }
        'F03-L-003' { return [ordered]@{ leaseRecovered = $Value; finalCompleted = $Value } }
        'F03-L-004' { return [ordered]@{ bulkItemFailureObserved = $Value; activeVersionUnchanged = $Value } }
        'F03-L-005' { return [ordered]@{ dltFailedObserved = $Value; reprocessCreatedNewVersion = $Value; finalCompleted = $Value } }
        'F03-L-006' { return [ordered]@{ mysqlFinalizeRolledBack = $Value; minioObjectReusable = $Value } }
    }
}

function New-FailureRawState(
        [string] $CaseId,
        [string] $RunId,
        [int] $Attempt,
        [string] $Outcome,
        [object[]] $Blockers,
        [string] $Reason) {
    return [ordered]@{
        experimentId = $experimentId
        runType = $RunType
        caseId = $CaseId
        runId = $RunId
        attempt = $Attempt
        recordedOutcome = $Outcome
        blockers = $Blockers
        failureReason = $Reason
        task = $null
        outbox = $null
        file = [ordered]@{ latestProcessingVersion = $null; activeProcessingVersion = $null }
        chunks = @()
        activeChunks = @()
        expectedActiveEsIds = @()
        actualActiveEsIds = @()
        kafka = [ordered]@{ topic = 'document-processing-v2'; dltTopic = 'document-processing-v2-dlt'; offsets = @() }
        minio = [ordered]@{ objectKey = $null; reusable = $false }
        searchHits = @()
        permissionChecks = @()
        legacyVisibilityChecks = @()
        caseAssertions = Get-CaseAssertions $CaseId $false
    }
}

function Write-JsonFile([string] $Path, $Value) {
    $Value | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Write-RawMetrics(
        [string] $Path,
        $Raw,
        [string] $RunId,
        [string] $CaseId,
        [int] $Attempt,
        [int] $ExecutionOrder,
        [string] $StartedAt,
        [long] $ElapsedMs,
        [int] $ExitCode,
        [bool] $IncludedInStats,
        [string] $ExclusionReason,
        [string] $RequestParamHash) {
    $chunkKeys = @($Raw.chunks | ForEach-Object {
        "$($_.fileUploadId):$($_.processingVersion):$($_.chunkId)"
    })
    $mysqlDuplicateCount = $chunkKeys.Count - @($chunkKeys | Sort-Object -Unique).Count

    $recomputedEsIds = @($Raw.activeChunks | ForEach-Object {
        "$($_.fileUploadId):$($_.processingVersion):$($_.chunkId)"
    } | Sort-Object -Unique)
    $actualEsIds = @($Raw.actualActiveEsIds | ForEach-Object { [string]$_ } | Sort-Object -Unique)
    $esSymmetricDifference = @(Compare-Object -ReferenceObject $recomputedEsIds -DifferenceObject $actualEsIds).Count

    $invisibleHits = 0
    foreach ($hit in @($Raw.searchHits)) {
        $active = $Raw.file.activeProcessingVersion
        if ($hit.PSObject.Properties.Name -contains 'activeProcessingVersion') {
            $active = $hit.activeProcessingVersion
        }
        if ($null -ne $hit.processingVersion -and $hit.processingVersion -ne $active) {
            $invisibleHits++
        }
    }

    $permissionMap = @{}
    foreach ($check in @($Raw.permissionChecks)) { $permissionMap[[string]$check.actor] = [int]$check.status }
    $permissionPass = $permissionMap.ContainsKey('owner') -and
            $permissionMap.owner -ge 200 -and $permissionMap.owner -lt 300 -and
            $permissionMap.ContainsKey('admin') -and
            $permissionMap.admin -ge 200 -and $permissionMap.admin -lt 300 -and
            $permissionMap.other -eq 403

    $legacyRows = @($Raw.legacyVisibilityChecks)
    $legacyPass = Test-ZhF03LegacyVisibilityChecks -Checks $legacyRows

    $stateConsistencyPass = $false
    if ($null -eq $Raw.task) {
        $stateConsistencyPass = $CaseId -eq 'F03-L-006'
    } elseif ($Raw.task.status -eq 'COMPLETED') {
        $stateConsistencyPass = $Raw.task.processingVersion -eq $Raw.file.activeProcessingVersion -and
                $esSymmetricDifference -eq 0
    } elseif ($Raw.task.status -eq 'FAILED') {
        $stateConsistencyPass = $Raw.task.processingVersion -ne $Raw.file.activeProcessingVersion
    }

    $outcomePass = $Raw.recordedOutcome -eq 'PASS'
    $metricValues = [ordered]@{
        'ZH-M-F03-L01' = [ordered]@{ value = $mysqlDuplicateCount; unit = 'rows'; check = $mysqlDuplicateCount -eq 0 }
        'ZH-M-F03-L02' = [ordered]@{ value = $esSymmetricDifference; unit = 'ids'; check = $esSymmetricDifference -eq 0 }
        'ZH-M-F03-L03' = [ordered]@{ value = $invisibleHits; unit = 'hits'; check = $invisibleHits -eq 0 }
        'ZH-M-F03-L04' = [ordered]@{ value = $(if ($outcomePass) { 1 } else { 0 }); unit = 'case'; check = $outcomePass }
        'ZH-M-F03-L05' = [ordered]@{ value = $(if ($stateConsistencyPass) { 1 } else { 0 }); unit = 'boolean'; check = $stateConsistencyPass }
        'ZH-M-F03-L06' = [ordered]@{ value = $(if ($permissionPass) { 1 } else { 0 }); unit = 'boolean'; check = $permissionPass }
        'ZH-M-F03-L07' = [ordered]@{ value = $(if ($legacyPass) { 1 } else { 0 }); unit = 'boolean'; check = $legacyPass }
    }
    $sampleStatus = switch ($Raw.recordedOutcome) {
        'PASS' { 'PASS' }
        'FAIL' { 'FAIL' }
        'BLOCKED_BY_ENVIRONMENT' { 'ABORTED' }
    }
    $errorCode = switch ($Raw.recordedOutcome) {
        'PASS' { '' }
        'FAIL' { 'RECORDED_FAILURE' }
        'BLOCKED_BY_ENVIRONMENT' { 'BLOCKED_BY_ENVIRONMENT' }
    }
    $rows = New-Object System.Collections.Generic.List[string]
    foreach ($metric in $metricValues.GetEnumerator()) {
        $row = [ordered]@{
            runId = $RunId
            experimentId = $experimentId
            featureId = $featureId
            sampleId = $CaseId
            variant = 'after'
            runType = $RunType
            metricId = $metric.Key
            value = $metric.Value.value
            unit = $metric.Value.unit
            round = $Attempt
            repetition = $Attempt
            executionOrder = $ExecutionOrder
            startedAt = $StartedAt
            elapsedMs = $ElapsedMs
            sampleStatus = $sampleStatus
            httpStatus = $null
            exitCode = $ExitCode
            pass = $outcomePass -and [bool]$metric.Value.check
            errorCode = $errorCode
            includedInStats = $IncludedInStats
            exclusionReason = $ExclusionReason
            metricSchemaVersion = 'ZH-F03-METRIC/1'
            collectorVersion = $collectionScriptSha256
            requestParamHash = $RequestParamHash
        }
        $rows.Add(($row | ConvertTo-Json -Compress -Depth 8))
    }
    [System.IO.File]::WriteAllLines(
            $Path,
            $rows,
            (New-Object System.Text.UTF8Encoding($false)))
}

if ($RunType -eq 'REAL') {
    if (-not $BuildArtifactPath -or -not (Test-Path -LiteralPath $BuildArtifactPath -PathType Leaf)) {
        Write-Error 'FAIL_CLOSED: REAL requires -BuildArtifactPath pointing to the tested artifact'
        exit 9
    }
    if (-not $DatasetManifestPath -or -not (Test-Path -LiteralPath $DatasetManifestPath -PathType Leaf)) {
        Write-Error 'FAIL_CLOSED: REAL requires -DatasetManifestPath pointing to the frozen dataset manifest'
        exit 10
    }
    if (-not $DriverConfigPath -or -not (Test-Path -LiteralPath $DriverConfigPath -PathType Leaf)) {
        Write-Error 'FAIL_CLOSED: REAL requires -DriverConfigPath for the reviewed fault driver'
        exit 12
    }
}

$driverResolved = $fixedDriverPath
$driverConfigResolved = $null
$driverConfigSha256 = Get-Sha256Text "NOT_APPLICABLE:$RunType"
if ($DriverConfigPath) {
    $driverConfigResolved = (Resolve-Path -LiteralPath $DriverConfigPath).Path
    $driverConfigSha256 = (Get-FileHash -LiteralPath $driverConfigResolved -Algorithm SHA256).Hash
}
$buildArtifactResolved = $null
$buildArtifactHash = Get-Sha256Text "NOT_APPLICABLE:$RunType"
if ($BuildArtifactPath) {
    $buildArtifactResolved = (Resolve-Path -LiteralPath $BuildArtifactPath).Path
    $buildArtifactHash = (Get-FileHash -LiteralPath $buildArtifactResolved -Algorithm SHA256).Hash
}
$datasetManifestResolved = $null
$datasetManifestSha256 = Get-Sha256Text "NOT_APPLICABLE:$RunType"
if ($DatasetManifestPath) {
    $datasetManifestResolved = (Resolve-Path -LiteralPath $DatasetManifestPath).Path
    $datasetManifestSha256 = (Get-FileHash -LiteralPath $datasetManifestResolved -Algorithm SHA256).Hash
}
$collectionScriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
$statScriptSha256 = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'zh_f03_light_stat.py') -Algorithm SHA256).Hash
$verificationScriptSha256 = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'zh_f03_light_verify.py') -Algorithm SHA256).Hash
$driverScriptSha256 = (Get-FileHash -LiteralPath $driverResolved -Algorithm SHA256).Hash
$driverRequirementsSha256 = (Get-FileHash -LiteralPath $driverRequirementsPath -Algorithm SHA256).Hash
$metricContractScriptSha256 = (Get-FileHash -LiteralPath $metricContractPath -Algorithm SHA256).Hash
$nativeProcessControllerSha256 = (Get-FileHash -LiteralPath $fixedNativeControllerPath -Algorithm SHA256).Hash
$authPreparationScriptSha256 = (Get-FileHash -LiteralPath $fixedAuthPreparationPath -Algorithm SHA256).Hash
$applicationEnvSha256 = Get-Sha256Text 'NOT_APPLICABLE:DOCKER'
$nativeStatePathSha256 = Get-Sha256Text 'NOT_APPLICABLE:DOCKER'
$dirtyDiffSha256 = Get-GitDirtyIdentity

$authResult = $null
$authEvidence = $null
if ($RuntimeMode -eq 'WINDOWS_LOCAL') {
    $applicationEnvResolved = (Resolve-Path -LiteralPath $ApplicationEnvPath).Path
    $kafkaHomeResolved = (Resolve-Path -LiteralPath $KafkaHome).Path
    $kafkaConfigResolved = (Resolve-Path -LiteralPath $KafkaConfigPath).Path
    $kafkaStartResolved = (Resolve-Path -LiteralPath (Join-Path $kafkaHomeResolved 'bin\windows\kafka-server-start.bat')).Path
    $kafkaConsumerGroupsResolved = (Resolve-Path -LiteralPath (Join-Path $kafkaHomeResolved 'bin\windows\kafka-consumer-groups.bat')).Path
    $javaPathResolved = (Get-Command java -ErrorAction Stop).Source
    $applicationEnvSha256 = (Get-FileHash -LiteralPath $applicationEnvResolved -Algorithm SHA256).Hash
    $kafkaConsumerGroupsSha256 = (Get-FileHash -LiteralPath $kafkaConsumerGroupsResolved -Algorithm SHA256).Hash
    $kafkaStartSha256 = (Get-FileHash -LiteralPath $kafkaStartResolved -Algorithm SHA256).Hash
    $kafkaConfigSha256 = (Get-FileHash -LiteralPath $kafkaConfigResolved -Algorithm SHA256).Hash
    $javaSha256 = (Get-FileHash -LiteralPath $javaPathResolved -Algorithm SHA256).Hash
    $nativeStateResolved = [System.IO.Path]::GetFullPath($NativeStatePath)
    $nativeStatePathSha256 = Get-Sha256Text $nativeStateResolved
    $nativeStateParent = Split-Path -Parent $nativeStateResolved
    if (-not $nativeStateParent) {
        throw 'FAIL_CLOSED: NativeStatePath must have a parent directory'
    }
    New-Item -ItemType Directory -Path $nativeStateParent -Force | Out-Null
    if ($RuntimeLogDirectory) {
        $runtimeLogDirectoryResolved = [System.IO.Path]::GetFullPath($RuntimeLogDirectory)
    } else {
        $runtimeLogDirectoryResolved = Join-Path $nativeStateParent 'logs'
    }
    New-Item -ItemType Directory -Path $runtimeLogDirectoryResolved -Force | Out-Null

    $env:ZH_F03_NATIVE_STATE_PATH = $nativeStateResolved
    $env:ZH_F03_APP_ENV_PATH = $applicationEnvResolved
    $env:ZH_F03_KAFKA_HOME = $kafkaHomeResolved
    $env:ZH_F03_KAFKA_CONFIG_PATH = $kafkaConfigResolved
    $env:ZH_F03_KAFKA_START_PATH = $kafkaStartResolved
    $env:ZH_F03_KAFKA_START_SHA256 = $kafkaStartSha256
    $env:ZH_F03_KAFKA_CONFIG_SHA256 = $kafkaConfigSha256
    $env:ZH_F03_KAFKA_CONSUMER_GROUPS_PATH = $kafkaConsumerGroupsResolved
    $env:ZH_F03_KAFKA_CONSUMER_GROUPS_SHA256 = $kafkaConsumerGroupsSha256
    $env:ZH_F03_BUILD_ARTIFACT = $buildArtifactResolved
    $env:ZH_F03_BUILD_ARTIFACT_SHA256 = $buildArtifactHash
    $env:ZH_F03_JAVA_PATH = $javaPathResolved
    $env:ZH_F03_JAVA_SHA256 = $javaSha256
    $env:ZH_F03_APP_ENV_SHA256 = $applicationEnvSha256
    $env:ZH_F03_WORKTREE = (Get-Location).Path
    $env:ZH_F03_RUNTIME_LOG_DIR = $runtimeLogDirectoryResolved
    $env:ZH_F03_LEASE_SECONDS = [string]$ExperimentLeaseSeconds
    $env:ZH_F03_REAPER_INITIAL_DELAY_MS = [string]$ExperimentReaperInitialDelayMs
    $env:ZH_F03_REAPER_SCAN_DELAY_MS = [string]$ExperimentReaperScanDelayMs
    $mysqlUsername = Get-DotEnvValue $applicationEnvResolved 'SPRING_DATASOURCE_USERNAME'
    $mysqlPassword = Get-DotEnvValue $applicationEnvResolved 'SPRING_DATASOURCE_PASSWORD'
    $minioAccessKey = Get-DotEnvValue $applicationEnvResolved 'MINIO_ACCESS_KEY'
    $minioSecretKey = Get-DotEnvValue $applicationEnvResolved 'MINIO_SECRET_KEY'
    if (-not $mysqlUsername -or -not $minioAccessKey -or -not $minioSecretKey) {
        throw 'FAIL_CLOSED: application env is missing MySQL or MinIO credentials'
    }
    $env:ZH_F03_APP_BASE_URL = ([Uri]$ApplicationHealthUrl).GetLeftPart([System.UriPartial]::Authority)
    $env:ZH_F03_MYSQL_PORT = [string]$MySqlPort
    $env:ZH_F03_MYSQL_DATABASE = $MySqlDatabase
    $env:ZH_F03_MYSQL_USERNAME = $mysqlUsername
    $env:ZH_F03_MYSQL_PASSWORD = $mysqlPassword
    $env:ZH_F03_MINIO_ENDPOINT = "$MinioHost`:$MinioPort"
    $env:ZH_F03_MINIO_ACCESS_KEY = $minioAccessKey
    $env:ZH_F03_MINIO_SECRET_KEY = $minioSecretKey
    $env:ZH_F03_MINIO_BUCKET = $MinioBucket
    $env:ZH_F03_ES_URL = "http://$ElasticsearchHost`:$ElasticsearchPort"
    $env:ZH_F03_ELASTICSEARCH_PASSWORD = 'NOT_USED_SECURITY_DISABLED'
    $env:ZH_F03_DATASOURCE_URL = New-MySqlJdbcUrl $MySqlHost $MySqlPort $MySqlDatabase
    $env:ZH_F03_REDIS_DATABASE = [string]$RedisDatabase
    $env:ZH_F03_MINIO_BUCKET_OVERRIDE = $MinioBucket
    $env:ZH_F03_ES_HOST = $ElasticsearchHost
    $env:ZH_F03_ES_PORT = [string]$ElasticsearchPort
    $env:ZH_F03_KAFKA_BOOTSTRAP = "$KafkaHost`:$KafkaPort"

    # Backend environment overrides are applied only at process creation. Restart the
    # harness-owned backend before sampling so the frozen experiment timing is real.
    $backendStatusOutput = & python $fixedNativeControllerPath --action status --service backend --config $driverConfigResolved
    if ($LASTEXITCODE -ne 0) {
        throw 'FAIL_CLOSED: native process controller could not inspect the backend runtime'
    }
    $backendStatus = $backendStatusOutput | ConvertFrom-Json
    if ($backendStatus.running) {
        [void](& python $fixedNativeControllerPath --action stop --service backend --config $driverConfigResolved)
        if ($LASTEXITCODE -ne 0) {
            throw 'FAIL_CLOSED: native process controller could not restart the backend with experiment timing'
        }
    }
    $controllerOutput = & python $fixedNativeControllerPath --action ensure-running --config $driverConfigResolved
    if ($LASTEXITCODE -ne 0) {
        throw 'FAIL_CLOSED: native process controller could not establish the runtime'
    }
    [void]($controllerOutput | ConvertFrom-Json)
    [void](Wait-TcpEndpoint 'kafka' $KafkaHost $KafkaPort 90)
    [void](Wait-HttpEndpoint 'application' $ApplicationHealthUrl 120)

    $adminUsername = Get-DotEnvValue $applicationEnvResolved 'ADMIN_BOOTSTRAP_USERNAME'
    $adminPassword = Get-DotEnvValue $applicationEnvResolved 'ADMIN_BOOTSTRAP_PASSWORD'
    if (-not $adminUsername -or -not $adminPassword) {
        throw 'FAIL_CLOSED: application env does not contain bootstrap admin credentials'
    }
    try {
        $env:ZH_F03_ADMIN_USERNAME = $adminUsername
        $env:ZH_F03_ADMIN_PASSWORD = $adminPassword
        $authSession = 'zh-f03-' + [Guid]::NewGuid().ToString('N')
        $appBaseUrl = ([Uri]$ApplicationHealthUrl).GetLeftPart([System.UriPartial]::Authority)
        $authJson = & python $fixedAuthPreparationPath --app-url $appBaseUrl --session-id $authSession
        if ($LASTEXITCODE -ne 0) {
            throw 'FAIL_CLOSED: authentication preparation failed'
        }
        $authResult = $authJson | ConvertFrom-Json
    } finally {
        Remove-Item Env:ZH_F03_ADMIN_USERNAME -ErrorAction SilentlyContinue
        Remove-Item Env:ZH_F03_ADMIN_PASSWORD -ErrorAction SilentlyContinue
        $adminUsername = $null
        $adminPassword = $null
    }
    if (-not $authResult.evidence.distinctTokens) {
        throw 'FAIL_CLOSED: authentication preparation did not produce three distinct actors'
    }
    $authEvidence = $authResult.evidence
    $env:ZH_F03_OWNER_TOKEN = [string]$authResult.ownerToken
    $env:ZH_F03_ADMIN_TOKEN = [string]$authResult.adminToken
    $env:ZH_F03_OTHER_TOKEN = [string]$authResult.otherToken
    $env:ZH_F03_LEGACY_QUERY = "ZH_F03_LEGACY_ABSENT_$authSession"
    $env:ZH_F03_LEGACY_FILE_MD5 = '00000000000000000000000000000000'
    $env:ZH_F03_LEGACY_OWNER_USER_ID = [string]$authResult.otherUserId
    $env:ZH_F03_LEGACY_QUERY_USER_ID = [string]$authResult.ownerUserId
}

$preflight = @(
    (Test-HttpEndpoint 'application' $ApplicationHealthUrl),
    (Test-TcpEndpoint 'mysql' $MySqlHost $MySqlPort),
    (Test-TcpEndpoint 'kafka' $KafkaHost $KafkaPort),
    (Test-TcpEndpoint 'minio' $MinioHost $MinioPort),
    (Test-TcpEndpoint 'elasticsearch' $ElasticsearchHost $ElasticsearchPort)
)
$blockers = @($preflight | Where-Object { -not $_.ok })

$gitHead = (& git rev-parse HEAD 2>$null | Out-String).Trim()
$gitBranch = (& git branch --show-current 2>$null | Out-String).Trim()
$gitStatus = (& git status --porcelain=v2 --untracked-files=all 2>$null | Out-String)
$gitStatusSha = Get-Sha256Text $gitStatus
$javaVersion = (& java --version 2>&1 | Out-String).Trim()
$pythonVersion = (& python --version 2>&1 | Out-String).Trim()

$selectedCases = $caseIds
if ($Case -ne 'all') { $selectedCases = @($Case) }
$allRecordedPass = $true
$executionOrder = 0

try {
foreach ($caseId in $selectedCases) {
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $executionOrder++
        $startedClock = Get-Date
        $startedAt = (Get-Date).ToUniversalTime().ToString('o')
        $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ')
        $suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
        $runId = "$caseId-a$attempt-$stamp-$suffix"
        $runDir = Join-Path (Join-Path (Join-Path $OutRoot $runTypeDirectory) $caseId) $runId
        New-Item -ItemType Directory -Path $runDir -Force | Out-Null

        $commandPath = Join-Path $runDir 'command.txt'
        $commandLines = @(
            "orchestrator=$PSCommandPath",
            "runType=$RunType",
            "runtimeMode=$RuntimeMode",
            "caseId=$caseId",
            "attempt=$attempt",
            "driverScript=$driverResolved",
            "driverConfigSha256=$driverConfigSha256",
            "buildArtifact=$buildArtifactResolved",
            "datasetManifest=$datasetManifestResolved",
            "experimentLeaseSeconds=$ExperimentLeaseSeconds",
            "experimentReaperInitialDelayMs=$ExperimentReaperInitialDelayMs",
            "experimentReaperScanDelayMs=$ExperimentReaperScanDelayMs",
            "nativeStatePathSha256=$nativeStatePathSha256"
        )
        $commandLines | Set-Content -LiteralPath $commandPath -Encoding UTF8
        $commandText = $commandLines -join '; '

        $configPath = Join-Path $runDir 'run-config.json'
        $config = [ordered]@{
            applicationHealthUrl = $ApplicationHealthUrl
            mysql = "$MySqlHost`:$MySqlPort"
            kafka = "$KafkaHost`:$KafkaPort"
            minio = "$MinioHost`:$MinioPort"
            elasticsearch = "$ElasticsearchHost`:$ElasticsearchPort"
            attempts = $Attempts
            publicMode = $PublicMode
            cacheState = $CacheState
            driverConfigSha256 = $driverConfigSha256
            driverScriptSha256 = $driverScriptSha256
            driverRequirementsSha256 = $driverRequirementsSha256
            metricContractScriptSha256 = $metricContractScriptSha256
            runtimeMode = $RuntimeMode
            nativeProcessControllerSha256 = $nativeProcessControllerSha256
            authPreparationScriptSha256 = $authPreparationScriptSha256
            applicationEnvSha256 = $applicationEnvSha256
            authEvidence = $authEvidence
            experimentTiming = $experimentTiming
            nativeStatePathSha256 = $nativeStatePathSha256
        }
        Write-JsonFile $configPath $config
        $configHash = (Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash

        $environmentPath = Join-Path $runDir 'environment-snapshot.json'
        $environment = [ordered]@{
            capturedAt = (Get-Date).ToString('o')
            runType = $RunType
            gitHead = $gitHead
            gitBranch = $gitBranch
            gitStatusPorcelainV2Sha256 = $gitStatusSha
            dirtyDiffSha256 = $dirtyDiffSha256
            buildArtifactSha256 = $buildArtifactHash
            datasetManifestSha256 = $datasetManifestSha256
            configSha256 = $configHash
            javaVersion = $javaVersion
            pythonVersion = $pythonVersion
            powershellVersion = $PSVersionTable.PSVersion.ToString()
            operatingSystem = [System.Environment]::OSVersion.VersionString
            preflight = $preflight
            driverScriptSha256 = $driverScriptSha256
            driverRequirementsSha256 = $driverRequirementsSha256
            metricContractScriptSha256 = $metricContractScriptSha256
            driverConfigSha256 = $driverConfigSha256
            runtimeMode = $RuntimeMode
            nativeProcessControllerSha256 = $nativeProcessControllerSha256
            authPreparationScriptSha256 = $authPreparationScriptSha256
            applicationEnvSha256 = $applicationEnvSha256
            authEvidence = $authEvidence
            experimentTiming = $experimentTiming
            nativeStatePathSha256 = $nativeStatePathSha256
        }
        Write-JsonFile $environmentPath $environment

        $rawPath = Join-Path $runDir 'raw-state.json'
        $logPath = Join-Path $runDir 'raw.log'
        $resetProofPath = Join-Path $runDir 'state-reset-proof.json'
        $driverExit = $null
        $runExitCode = 0
        if ($blockers.Count -gt 0) {
            $runExitCode = 20
            "BLOCKED_BY_ENVIRONMENT before sample start" | Set-Content -LiteralPath $logPath -Encoding UTF8
            Write-JsonFile $resetProofPath ([ordered]@{
                status = 'NOT_STARTED_PRECHECK_BLOCKED'
                capturedAt = (Get-Date).ToUniversalTime().ToString('o')
                blockers = $blockers
            })
            Write-JsonFile $rawPath (New-FailureRawState $caseId $runId $attempt 'BLOCKED_BY_ENVIRONMENT' $blockers 'dependency preflight failed before sample start')
        } elseif ($RunType -ne 'REAL') {
            $runExitCode = 21
            "FAIL: the reviewed fault driver only accepts REAL" | Set-Content -LiteralPath $logPath -Encoding UTF8
            Write-JsonFile $resetProofPath ([ordered]@{
                status = 'NOT_STARTED_NON_REAL_DRIVER'
                capturedAt = (Get-Date).ToUniversalTime().ToString('o')
            })
            Write-JsonFile $rawPath (New-FailureRawState $caseId $runId $attempt 'FAIL' @() 'FIXTURE_ONLY/STUB require an explicitly separate diagnostic collector')
        } else {
            $caseWindowSeconds = if ($caseId -eq 'F03-L-005') { 180 } else { 120 }
            $stdoutPath = Join-Path $runDir 'driver.stdout.log'
            $stderrPath = Join-Path $runDir 'driver.stderr.log'
            $pythonPath = (Get-Command python -ErrorAction Stop).Source
            $driverArguments = @(
                $driverResolved,
                '--case-id', $caseId,
                '--run-id', $runId,
                '--run-type', 'REAL',
                '--run-dir', $runDir,
                '--attempt', [string]$attempt,
                '--config', $driverConfigResolved,
                '--dataset-manifest', $datasetManifestResolved
            )
            $process = Start-Process `
                    -FilePath $pythonPath `
                    -ArgumentList $driverArguments `
                    -NoNewWindow `
                    -PassThru `
                    -RedirectStandardOutput $stdoutPath `
                    -RedirectStandardError $stderrPath
            $null = $process.Handle
            $completedWithinWindow = $process.WaitForExit($caseWindowSeconds * 1000)
            if (-not $completedWithinWindow) {
                try { $process.Kill($true) } catch { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
                $process.WaitForExit()
                $driverExit = 24
                $runExitCode = 24
                $resetArguments = @(
                    $driverResolved,
                    '--case-id', $caseId,
                    '--run-id', $runId,
                    '--run-type', 'REAL',
                    '--run-dir', $runDir,
                    '--attempt', [string]$attempt,
                    '--config', $driverConfigResolved,
                    '--dataset-manifest', $datasetManifestResolved,
                    '--reset-only'
                )
                $resetProcess = Start-Process -FilePath $pythonPath -ArgumentList $resetArguments -NoNewWindow -PassThru
                $null = $resetProcess.Handle
                $resetCompleted = $resetProcess.WaitForExit(60000)
                if (-not $resetCompleted) {
                    try { $resetProcess.Kill($true) } catch { Stop-Process -Id $resetProcess.Id -Force -ErrorAction SilentlyContinue }
                    $resetProcess.WaitForExit()
                    $resetExitCode = 32
                } else {
                    $resetProcess.Refresh()
                    $resetExitCode = if ($null -eq $resetProcess.ExitCode) { 32 } else { [int]$resetProcess.ExitCode }
                }
                $timeoutRaw = New-FailureRawState $caseId $runId $attempt 'FAIL' @() "watchdog exceeded ${caseWindowSeconds}s"
                $timeoutRaw | Add-Member -NotePropertyName observation -NotePropertyValue ([ordered]@{
                    startedAt = $startedAt
                    completedAt = (Get-Date).ToUniversalTime().ToString('o')
                }) -Force
                $timeoutRaw | Add-Member -NotePropertyName watchdog -NotePropertyValue ([ordered]@{
                    timedOut = $true
                    maxSeconds = $caseWindowSeconds
                    emergencyResetExitCode = $resetExitCode
                    emergencyResetTimedOut = -not $resetCompleted
                }) -Force
                Write-JsonFile $rawPath $timeoutRaw
            } else {
                $process.Refresh()
                $driverExit = $process.ExitCode
                $runExitCode = if ($null -eq $driverExit) { 22 } else { [int]$driverExit }
            }
            $driverOutput = @()
            if (Test-Path -LiteralPath $stdoutPath) { $driverOutput += Get-Content -LiteralPath $stdoutPath -Raw -Encoding UTF8 }
            if (Test-Path -LiteralPath $stderrPath) { $driverOutput += Get-Content -LiteralPath $stderrPath -Raw -Encoding UTF8 }
            $combinedDriverOutput = $driverOutput -join "`n"
            $combinedDriverOutput | Set-Content -LiteralPath $logPath -Encoding UTF8
            $secretLeakDetected = $false
            if ($authResult) {
                foreach ($token in @($authResult.ownerToken, $authResult.adminToken, $authResult.otherToken)) {
                    if ($token -and $combinedDriverOutput.Contains([string]$token)) {
                        $secretLeakDetected = $true
                    }
                }
            }
            if (-not (Test-Path -LiteralPath $rawPath)) {
                if ($runExitCode -eq 0) { $runExitCode = 22 }
                Write-JsonFile $rawPath (New-FailureRawState $caseId $runId $attempt 'FAIL' @() "driver exit=$driverExit and did not create raw-state.json")
            } else {
                try {
                    $raw = Get-Content -LiteralPath $rawPath -Raw -Encoding UTF8 | ConvertFrom-Json
                    $identityOk = $raw.experimentId -eq $experimentId -and
                            $raw.runType -eq $RunType -and
                            $raw.caseId -eq $caseId -and
                            $raw.runId -eq $runId -and
                            [int]$raw.attempt -eq $attempt
                    if (-not $identityOk -or $raw.recordedOutcome -notin @('PASS', 'FAIL', 'BLOCKED_BY_ENVIRONMENT')) {
                        throw 'driver raw-state identity/outcome contract mismatch'
                    }
                    if ($driverExit -ne 0 -and $raw.recordedOutcome -eq 'PASS') {
                        $raw.recordedOutcome = 'FAIL'
                        $raw | Add-Member -NotePropertyName failureReason -NotePropertyValue "driver exit=$driverExit" -Force
                        Write-JsonFile $rawPath $raw
                    }
                    if ($secretLeakDetected) {
                        $raw.recordedOutcome = 'FAIL'
                        $raw | Add-Member -NotePropertyName failureReason -NotePropertyValue 'driver output contained an authentication token' -Force
                        Write-JsonFile $rawPath $raw
                        $runExitCode = 25
                    }
                } catch {
                    $invalidPath = Join-Path $runDir 'raw-state.invalid.json'
                    Move-Item -LiteralPath $rawPath -Destination $invalidPath
                    Add-Content -LiteralPath $logPath -Value "INVALID_RAW_STATE: $($_.Exception.Message)" -Encoding UTF8
                    $fallback = New-FailureRawState $caseId $runId $attempt 'FAIL' @() $_.Exception.Message
                    Write-JsonFile $rawPath $fallback
                }
            }
            if (-not (Test-Path -LiteralPath $resetProofPath -PathType Leaf)) {
                Write-JsonFile $resetProofPath ([ordered]@{
                    status = 'MISSING_DRIVER_RESET_PROOF'
                    capturedAt = (Get-Date).ToUniversalTime().ToString('o')
                })
                $rawWithoutProof = Get-Content -LiteralPath $rawPath -Raw -Encoding UTF8 | ConvertFrom-Json
                $rawWithoutProof.recordedOutcome = 'FAIL'
                $rawWithoutProof | Add-Member -NotePropertyName failureReason -NotePropertyValue 'driver did not create state-reset-proof.json' -Force
                Write-JsonFile $rawPath $rawWithoutProof
                if ($runExitCode -eq 0) { $runExitCode = 23 }
            }
        }

        $rawState = Get-Content -LiteralPath $rawPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($rawState.recordedOutcome -ne 'PASS') { $allRecordedPass = $false }
        $completedAt = (Get-Date).ToUniversalTime().ToString('o')
        $manifestStatus = switch ($rawState.recordedOutcome) {
            'PASS' { 'PASS' }
            'FAIL' { 'FAIL' }
            'BLOCKED_BY_ENVIRONMENT' { 'ABORTED' }
        }
        $includedInStats = $rawState.recordedOutcome -ne 'BLOCKED_BY_ENVIRONMENT'
        $exclusionReason = if ($includedInStats) { '' } else { [string]$rawState.failureReason }
        $elapsedMs = [long]((Get-Date) - $startedClock).TotalMilliseconds
        $commandHash = (Get-FileHash -LiteralPath $commandPath -Algorithm SHA256).Hash
        $rawResultPath = Join-Path $runDir 'raw-results.jsonl'
        Write-RawMetrics `
                $rawResultPath `
                $rawState `
                $runId `
                $caseId `
                $attempt `
                $executionOrder `
                $startedAt `
                $elapsedMs `
                $runExitCode `
                $includedInStats `
                $exclusionReason `
                $commandHash
        $rawHash = (Get-FileHash -LiteralPath $rawPath -Algorithm SHA256).Hash
        $rawResultHash = (Get-FileHash -LiteralPath $rawResultPath -Algorithm SHA256).Hash
        $rawLogHash = (Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash
        $environmentHash = (Get-FileHash -LiteralPath $environmentPath -Algorithm SHA256).Hash
        $manifest = [ordered]@{
            featureId = $featureId
            project = 'zhishu'
            variant = 'after'
            experimentId = $experimentId
            baselineId = $baselineId
            evidencePackageId = "ZH-F03-EVIDENCE-$runId"
            cardVersion = 'V3'
            cardSectionId = $cardSectionId
            cardSectionSha256 = $cardSectionSha256
            experimentDesignCardSha256 = $experimentDesignCardSha256
            plannedHarnessSpecHash = $plannedHarnessSpecHash
            collectionScriptSha256 = $collectionScriptSha256
            metricContractScriptSha256 = $metricContractScriptSha256
            statScriptSha256 = $statScriptSha256
            verificationScriptSha256 = $verificationScriptSha256
            resultPathTemplateSha256 = 'NOT_APPLICABLE:ZHISHU'
            executionProtocolVersion = $executionProtocolVersion
            repoCommitSha = $gitHead
            dirtyDiffSha256 = $dirtyDiffSha256
            buildArtifactHash = $buildArtifactHash
            environmentSnapshotId = "ZH-F03-ENV-$runId"
            datasetManifestSha256 = $datasetManifestSha256
            configHash = $configHash
            promptHash = 'NOT_APPLICABLE'
            agentConfigHash = 'NOT_APPLICABLE'
            toolManifestHash = $environmentHash
            cacheState = $CacheState
            stateResetProof = 'state-reset-proof.json'
            stateResetProofSha256 = (Get-FileHash -LiteralPath $resetProofPath -Algorithm SHA256).Hash
            randomSeed = $null
            orderIndex = $executionOrder
            startedAt = $startedAt
            completedAt = $completedAt
            command = $commandText
            exitCode = $runExitCode
            status = $manifestStatus
            round = $attempt
            repetition = $attempt
            executionOrder = $executionOrder
            includedInStats = $includedInStats
            exclusionReason = $exclusionReason
            runType = $RunType
            caseId = $caseId
            runId = $runId
            attempt = $attempt
            rawResultPath = 'raw-results.jsonl'
            rawResultSha256 = $rawResultHash
            rawLogPath = 'raw.log'
            rawLogSha256 = $rawLogHash
            rawStateFile = 'raw-state.json'
            rawStateSha256 = $rawHash
            environmentSnapshotFile = 'environment-snapshot.json'
            environmentSnapshotSha256 = $environmentHash
            rawLogFile = 'raw.log'
            commandFile = 'command.txt'
            commandSha256 = $commandHash
            configSnapshotFile = 'run-config.json'
            configSnapshotSha256 = $configHash
            driverExitCode = $driverExit
            collectedAt = $completedAt
            publicMode = $PublicMode
            runtimeMode = $RuntimeMode
            nativeProcessControllerSha256 = $nativeProcessControllerSha256
            authPreparationScriptSha256 = $authPreparationScriptSha256
            applicationEnvSha256 = $applicationEnvSha256
            experimentTiming = $experimentTiming
            nativeStatePathSha256 = $nativeStatePathSha256
        }
        Write-JsonFile (Join-Path $runDir 'run-manifest.json') $manifest
        Write-Host "[ZH-F03] case=$caseId attempt=$attempt outcome=$($rawState.recordedOutcome) runDir=$runDir"
    }
}
} finally {
    Remove-Item Env:ZH_F03_OWNER_TOKEN -ErrorAction SilentlyContinue
    Remove-Item Env:ZH_F03_ADMIN_TOKEN -ErrorAction SilentlyContinue
    Remove-Item Env:ZH_F03_OTHER_TOKEN -ErrorAction SilentlyContinue
    Remove-Item Env:ZH_F03_MYSQL_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:ZH_F03_MINIO_ACCESS_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:ZH_F03_MINIO_SECRET_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:ZH_F03_LEGACY_QUERY -ErrorAction SilentlyContinue
    Remove-Item Env:ZH_F03_LEGACY_FILE_MD5 -ErrorAction SilentlyContinue
    Remove-Item Env:ZH_F03_LEGACY_OWNER_USER_ID -ErrorAction SilentlyContinue
    Remove-Item Env:ZH_F03_LEGACY_QUERY_USER_ID -ErrorAction SilentlyContinue
    Remove-Item Env:ZH_F03_LEASE_SECONDS -ErrorAction SilentlyContinue
    Remove-Item Env:ZH_F03_REAPER_INITIAL_DELAY_MS -ErrorAction SilentlyContinue
    Remove-Item Env:ZH_F03_REAPER_SCAN_DELAY_MS -ErrorAction SilentlyContinue
    if ($authResult) {
        $authResult.ownerToken = $null
        $authResult.adminToken = $null
        $authResult.otherToken = $null
    }
}

if ($allRecordedPass) { exit 0 }
exit 4
