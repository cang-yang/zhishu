function Test-ZhF03LegacyVisibilityChecks {
    param(
        [AllowEmptyCollection()]
        [object[]] $Checks
    )

    if ($null -eq $Checks -or $Checks.Count -eq 0) {
        return $false
    }
    foreach ($check in $Checks) {
        $fileOwnerUserId = [string]$check.fileOwnerUserId
        $queryOwnerUserId = [string]$check.queryOwnerUserId
        $expectedVisible = $check.expectedVisible
        $actualVisible = $check.actualVisible
        if ([string]::IsNullOrWhiteSpace($fileOwnerUserId) -or
                [string]::IsNullOrWhiteSpace($queryOwnerUserId) -or
                $expectedVisible -isnot [bool] -or
                $actualVisible -isnot [bool] -or
                $expectedVisible -ne $actualVisible) {
            return $false
        }
    }
    return $true
}
