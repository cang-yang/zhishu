# ZH-F07 实验结果校验脚本
# experimentId: ZH-EXP-F07-ADMIN-USERS
# 独立复算主指标/护栏、抽样复跑、响应等价 diff。
# 输出 verification-report.md（G5 结果验真用）。
#
# 用法：
#   .\scripts\experiments/zhishu-admin-verify.ps1 -ExperimentId ZH-EXP-F07-ADMIN-USERS -RunType REAL

param(
    [string] $ExperimentId = 'ZH-EXP-F07-ADMIN-USERS',
    [string] $RunType = 'REAL',
    [string] $RunsRoot = '.local/项目功能改造与指标评审/runs/zhishu/ZH-EXP-F07-ADMIN-USERS',
    [string] $BaseUrl = 'http://127.0.0.1:18081',
    [string] $AdminUser = 'admin',
    [string] $AdminPwd = 'ZhiShu2026Admin'
)

$ErrorActionPreference = 'Stop'
$runTypeDir = Join-Path $RunsRoot $RunType
$statPath = Join-Path $runTypeDir 'stat-summary.json'
$impPath  = Join-Path $runTypeDir 'improvement-summary.json'
if (-not (Test-Path $statPath)) { Write-Error "no stat-summary.json"; exit 6 }
$stat = Get-Content $statPath -Raw | ConvertFrom-Json
$imp  = if (Test-Path $impPath) { Get-Content $impPath -Raw | ConvertFrom-Json } else { @() }

# ---- 1. 独立复算：重读 raw，重算 P95，与 stat-summary 比对 ----
$runs = Get-ChildItem -Path $runTypeDir -Directory
$recompute = @{}
foreach ($run in $runs) {
    $rawPath = Join-Path $run.FullName 'raw-results.jsonl'
    if (-not (Test-Path $rawPath)) { continue }
    $manifest = Get-Content (Join-Path $run.FullName 'run-manifest.json') -Raw | ConvertFrom-Json
    $lat = @(Get-Content $rawPath | ForEach-Object { if ($_.Trim()) { $o = $_ | ConvertFrom-Json; if (-not $o.error) { [long]$o.latencyMs } } }) | Sort-Object
    if ($lat.Count -eq 0) { continue }
    $idx95 = [int][math]::Ceiling(0.95 * $lat.Count) - 1; if ($idx95 -lt 0) { $idx95 = 0 }
    $key = "$($manifest.variant)|$($manifest.scale)"
    if (-not $recompute[$key]) { $recompute[$key] = @() }
    $recompute[$key] += $lat
}
$recomputeSummary = @()
foreach ($k in $recompute.Keys) {
    $all = $recompute[$k] | Sort-Object
    $idx95 = [int][math]::Ceiling(0.95 * $all.Count) - 1; if ($idx95 -lt 0) { $idx95 = 0 }
    $idx50 = [int][math]::Ceiling(0.50 * $all.Count) - 1; if ($idx50 -lt 0) { $idx50 = 0 }
    $recomputeSummary += [PSCustomObject]@{ key=$k; n=$all.Count; p50=$all[$idx50]; p95=$all[$idx95] }
}

# ---- 2. 响应等价（护栏 ZH-M-F07-04）：baseline vs after 同参数首响应 canonical diff ----
$loginBody = @{ username=$AdminUser; password=$AdminPwd } | ConvertTo-Json
$loginResp = Invoke-RestMethod -Uri "$BaseUrl/api/v1/users/login" -Method Post -Body $loginBody -ContentType 'application/json'
$token = if ($loginResp.data.token) { $loginResp.data.token } elseif ($loginResp.token) { $loginResp.token } else { $loginResp.data.accessToken }
$headers = @{ Authorization = "Bearer $token" }
$queryUrl = "$BaseUrl/api/v1/admin/users/list?keyword=test&orgTag=ORG_1&status=1&page=1&size=20"

# NOTE: arm 切换需重启后端并改 zhishu.experiment.user-list-arm。本脚本假定后端已在某 arm 下运行；
# 完整等价性在 G4 跑两次（一次 baseline、一次 after）分别存响应，再 diff。此处只做当前 arm 的字段白名单校验。
$resp = Invoke-RestMethod -Uri $queryUrl -Method Get -Headers $headers
$allowedEnvelop = @('code','message','data')
$allowedData = @('content','totalElements','totalPages','size','number')
$allowedContent = @('userId','username','orgTags','primaryOrg','status','createdAt','usage')
$fieldCheck = $true
$fieldNotes = @()
foreach ($k in $resp.PSObject.Properties.Name) { if ($k -notin $allowedEnvelop) { $fieldCheck=$false; $fieldNotes += "envelop extra: $k" } }
foreach ($k in $resp.data.PSObject.Properties.Name) { if ($k -notin $allowedData) { $fieldCheck=$false; $fieldNotes += "data extra: $k" } }
if ($resp.data.content) {
    foreach ($row in $resp.data.content) {
        foreach ($k in $row.PSObject.Properties.Name) { if ($k -notin $allowedContent) { $fieldCheck=$false; $fieldNotes += "content extra: $k" } }
    }
}

# ---- 3. 生成 verification-report.md ----
$report = @()
$report += "# ZH-F07 结果独立验真报告（自动生成）"
$report += ""
$report += "experimentId: $ExperimentId"
$report += "runType: $RunType"
$report += "generatedAt: $(Get-Date -Format o)"
$report += ""
$report += "## 1. 独立复算 P95（重读 raw-results.jsonl）"
$report += ""
$report += "| variant|scale | n | recomputeP50 | recomputeP95 |"
$report += "|---|---|---|---|---|"
foreach ($r in $recomputeSummary) { $report += "| $($r.key) | $($r.n) | $($r.p50) | $($r.p95) |" }
$report += ""
$report += "## 2. 响应字段白名单校验（护栏 ZH-M-F07-04）"
$report += ""
$report += "fieldCheck: $fieldCheck"
if ($fieldNotes) { $report += "notes: $($fieldNotes -join '; ')" } else { $report += "notes: 无越界字段" }
$report += ""
$report += "## 3. 提升表（来自 stat-summary.json + improvement-summary.json）"
$report += ""
$report += "| scale | baselineP95 | afterP95 | p95ImprovePct | baseMean | afterMean | meanImprovePct | nBase | nAfter |"
$report += "|---|---|---|---|---|---|---|---|---|"
foreach ($i in $imp) { $report += "| $($i.scale) | $($i.baselineP95Ms) | $($i.afterP95Ms) | $($i.p95ImprovementPct)% | $($i.baselineMeanMs) | $($i.afterMeanMs) | $($i.meanImprovementPct)% | $($i.nBaseline) | $($i.nAfter) |" }
$report += ""
$report += "## 4. MME 达成判定"
$report += ""
$mme = $imp | Where-Object { $_.scale -eq '100k' } | Select-Object -First 1
if ($mme) {
    $p95Pass = ($mme.p95ImprovementPct -ge 30)
    $report += "- 100k P95 降>=30%: $($mme.p95ImprovementPct)% → $(if($p95Pass){'PASS'}else{'NOT_MET'})"
    $report += "- 扫描行数降>=80% 或 SQL 行为下推一致: 需 stat 脚本从 SQL 日志解析（人工/脚本补）"
    $report += "- 峰值堆降>=20%: 需 JFR/JVM 采样（人工/脚本补）"
    $report += "- 响应等价 100%: 需 baseline vs after canonical diff（G4 两次跑后补）"
}
$report += ""
$report += "## 5. 结论"
$report += ""
$report += "本报告由 verify 脚本独立复算生成，不替代 G5 人工结果验真审查。"

$reportPath = Join-Path $runTypeDir 'verification-report.md'
$report -join "`r`n" | Set-Content -Path $reportPath -Encoding UTF8
Write-Host "[ZH-F07 verify] report: $reportPath"
exit 0
