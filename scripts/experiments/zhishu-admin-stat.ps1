# ZH-F07 实验统计脚本
# experimentId: ZH-EXP-F07-ADMIN-USERS
# fail-closed: 只消费 hash 校验通过、includedInStats=true、sampleStatus 已填、runType 三处一致、
#              datasetManifestSha256 / environmentSnapshotSha256 匹配的样本。缺任一证据字段立即失败。
# 输出 stat-summary.json（按 arm：baseline / after，按 scale：1k/10k/100k）。
#
# 用法：
#   .\scripts\experiments\zhishu-admin-stat.ps1 -ExperimentId ZH-EXP-F07-ADMIN-USERS -PublicMode true -RunType REAL -VerifyRunType true -FailOnMixed true

param(
    [string] $ExperimentId = 'ZH-EXP-F07-ADMIN-USERS',
    [string] $RunType = 'REAL',
    [string] $PublicMode = 'true',
    [switch] $VerifyRunType,
    [switch] $FailOnMixed,
    [string] $RunsRoot = '.local/项目功能改造与指标评审/runs/zhishu/ZH-EXP-F07-ADMIN-USERS',
    [string] $DatasetManifestSha256 = '',   # G4 冻结值，必须与每个 run-manifest 一致
    [string] $EnvironmentSnapshotSha256 = '' # G4 冻结值
)

$ErrorActionPreference = 'Stop'
if ($PublicMode -eq 'true' -and $RunType -ne 'REAL') { Write-Error 'FAIL_CLOSED: publicMode=true requires REAL'; exit 7 }
# publicMode=true 且传入冻结 hash 时，要求 hash 非空（fail-closed：不允许空证据 pass-through）
if ($PublicMode -eq 'true') {
    if (-not $DatasetManifestSha256) { Write-Error 'FAIL_CLOSED: publicMode=true requires -DatasetManifestSha256'; exit 7 }
    if (-not $EnvironmentSnapshotSha256) { Write-Error 'FAIL_CLOSED: publicMode=true requires -EnvironmentSnapshotSha256'; exit 7 }
    # R2 majorFinding 3: publicMode=true 时 runType 三处一致校验与 MIXED 拒绝强制生效，不再 opt-in。
    # 避免公开统计漏带 -VerifyRunType -FailOnMixed 静默纳入 runType 不一致或 MIXED_WITH_BOUNDARY run。
    $VerifyRunType = $true
    $FailOnMixed = $true
}

$runTypeDir = Join-Path $RunsRoot $RunType
if (-not (Test-Path $runTypeDir)) { Write-Error "no runs dir: $runTypeDir"; exit 6 }

# 收集所有 run 目录
$runs = Get-ChildItem -Path $runTypeDir -Directory -ErrorAction SilentlyContinue
if (-not $runs) { Write-Error "no run dirs under $runTypeDir"; exit 6 }

$validSamples = @()
$rejected = @()
$seenRunIds = @{}

foreach ($run in $runs) {
    $manifestPath = Join-Path $run.FullName 'run-manifest.json'
    $rawPath      = Join-Path $run.FullName 'raw-results.jsonl'
    if (-not (Test-Path $manifestPath) -or -not (Test-Path $rawPath)) { $rejected += "$($run.Name): missing manifest/raw"; continue }

    $manifest = Get-Content $manifestPath -Raw | ConvertFrom-Json

    # 三处 runType 一致
    if ($VerifyRunType) {
        if ($manifest.runType -ne $RunType) { $rejected += "$($run.Name): manifest.runType=$($manifest.runType) != $RunType"; continue }
    }
    # dataset / env 快照 hash 校验（G4 冻结后非空时强制）
    if ($DatasetManifestSha256) {
        if (-not $manifest.datasetManifestSha256) { $rejected += "$($run.Name): manifest.datasetManifestSha256 empty (fail-closed: evidence required)"; continue }
        if ($manifest.datasetManifestSha256 -ne $DatasetManifestSha256) { $rejected += "$($run.Name): datasetManifestSha256 mismatch"; continue }
    }
    if ($EnvironmentSnapshotSha256) {
        if (-not $manifest.environmentSnapshotSha256) { $rejected += "$($run.Name): manifest.environmentSnapshotSha256 empty (fail-closed: evidence required)"; continue }
        if ($manifest.environmentSnapshotSha256 -ne $EnvironmentSnapshotSha256) { $rejected += "$($run.Name): environmentSnapshotSha256 mismatch"; continue }
    }
    if ($FailOnMixed -and $manifest.runType -eq 'MIXED_WITH_BOUNDARY') { $rejected += "$($run.Name): MIXED not allowed in public stats"; continue }

    # hash 复算 raw
    $rawBytes = [System.IO.File]::ReadAllBytes($rawPath)
    $rawSha = (Get-FileHash -Algorithm SHA256 -InputStream ([System.IO.MemoryStream]::new($rawBytes))).Hash
    if ($rawSha -ne $manifest.rawResultsSha256) { $rejected += "$($run.Name): rawResultsSha256 mismatch (expected $($manifest.rawResultsSha256) got $rawSha)"; continue }

    # 逐行读 raw，校验 runType / includedInStats / sampleStatus
    $lines = Get-Content $rawPath
    $runSamples = @()
    foreach ($line in $lines) {
        if (-not $line.Trim()) { continue }
        $o = $line | ConvertFrom-Json
        if ($o.runType -ne $RunType) { $rejected += "$($run.Name)/$($o.sampleId): raw runType=$($o.runType) != $RunType"; continue }
        if ($o.includedInStats -ne 'true') { continue }
        if (-not $o.sampleStatus) { $rejected += "$($run.Name)/$($o.sampleId): missing sampleStatus"; continue }
        if ($o.error) { continue } # 失败样本不计入延迟统计，但仍记录
        $runSamples += $o
    }
    $seenRunIds[$manifest.runId] = $manifest.variant
    $validSamples += $runSamples
}

if (-not $validSamples) { Write-Error "no valid samples; rejected: $($rejected -join '; ')"; exit 6 }

# 按 (variant, scale) 分组算 P50/P95/min/max/mean
function Percentile($sorted, $p) {
    if ($sorted.Count -eq 0) { return $null }
    $idx = [int][math]::Ceiling(($p/100) * $sorted.Count) - 1
    if ($idx -lt 0) { $idx = 0 }
    return $sorted[$idx]
}

$groups = $validSamples | Group-Object -Property variant, scale
$summary = [ordered]@{
    experimentId = $ExperimentId
    runType = $RunType
    publicMode = $PublicMode
    generatedAt = (Get-Date).ToString('o')
    datasetManifestSha256 = $DatasetManifestSha256
    environmentSnapshotSha256 = $EnvironmentSnapshotSha256
    rejectedCount = $rejected.Count
    rejected = $rejected
    arms = @()
}

foreach ($g in $groups) {
    $lat = $g.Group | ForEach-Object { [long]$_.latencyMs } | Sort-Object
    $variant = ($g.Name -split ', ')[0]
    $scale   = ($g.Name -split ', ')[1]
    $n = $lat.Count
    $summary.arms += [ordered]@{
        variant = $variant
        scale = $scale
        n = $n
        meanMs = [math]::Round(($lat | Measure-Object -Average).Average, 3)
        minMs  = $lat[0]
        maxMs  = $lat[-1]
        p50Ms  = Percentile $lat 50
        p90Ms  = Percentile $lat 90
        p95Ms  = Percentile $lat 95
        failedSamples = ($g.Group | Where-Object { $_.error }).Count
    }
}

# 输出 stat-summary.json
$statPath = Join-Path $runTypeDir 'stat-summary.json'
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $statPath -Encoding UTF8

# 提升 % 计算（按 scale 配对 baseline/after）
$improvements = @()
$scales = $validSamples | Select-Object -ExpandProperty scale -Unique
foreach ($s in $scales) {
    $base = $summary.arms | Where-Object { $_.variant -eq 'baseline' -and $_.scale -eq $s }
    $aft  = $summary.arms | Where-Object { $_.variant -eq 'after'    -and $_.scale -eq $s }
    if ($base -and $aft -and $base.p95Ms -gt 0) {
        $improvements += [ordered]@{
            scale = $s
            baselineP95Ms = $base.p95Ms
            afterP95Ms = $aft.p95Ms
            p95ImprovementPct = [math]::Round((1 - ($aft.p95Ms / $base.p95Ms)) * 100, 2)
            baselineMeanMs = $base.meanMs
            afterMeanMs = $aft.meanMs
            meanImprovementPct = [math]::Round((1 - ($aft.meanMs / $base.meanMs)) * 100, 2)
            nBaseline = $base.n
            nAfter = $aft.n
        }
    }
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $statPath -Encoding UTF8

$impPath = Join-Path $runTypeDir 'improvement-summary.json'
$improvements | ConvertTo-Json -Depth 6 | Set-Content -Path $impPath -Encoding UTF8

Write-Host "[ZH-F07 stat] valid samples: $($validSamples.Count); rejected: $($rejected.Count)"
$summary.arms | Format-Table variant, scale, n, meanMs, p50Ms, p95Ms, maxMs -AutoSize
Write-Host "Improvements:"
$improvements | Format-Table scale, baselineP95Ms, afterP95Ms, p95ImprovementPct -AutoSize
Write-Host "  stat: $statPath"
Write-Host "  imp:  $impPath"
exit 0
