Add-Type -AssemblyName System.IO.Compression.FileSystem
$xlsx = (Get-ChildItem "D:\Idea\enterprise-knowledge-agent\docs\input" -Filter *.xlsx | Select-Object -First 1).FullName
$zip = [System.IO.Compression.ZipFile]::OpenRead($xlsx)

foreach ($name in @("xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml")) {
    $entry = $zip.GetEntry($name)
    if ($entry -eq $null) { continue }
    $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
    $content = $reader.ReadToEnd()
    $reader.Close()

    # decode HTML entities to real chars
    $decoded = [System.Net.WebUtility]::HtmlDecode($content)

    # extract each row
    $rows = [regex]::Matches($decoded, '<row[^>]*>(.*?)</row>')
    Write-Output ("=== " + $name + " : " + $rows.Count + " rows ===")

    $mktRows = @()
    foreach ($row in $rows) {
        $cells = [regex]::Matches($row.Groups[1].Value, '<t[^>]*>([^<]*)</t>') | ForEach-Object { $_.Groups[1].Value }
        $joined = $cells -join " | "
        if ($joined -match "市场营销部") {
            $mktRows += $joined
        }
    }
    Write-Output ("Rows containing 市场营销部: " + $mktRows.Count)
    $i = 1
    foreach ($r in $mktRows) {
        Write-Output ("  [" + $i + "] " + $r)
        $i++
    }
    Write-Output ""
}
$zip.Dispose()
