#requires -Version 7.0
function Invoke-M3Child {
    param([string]$File,[string[]]$Arguments,[string]$WorkingDirectory,[hashtable]$Environment=@{},[int]$TimeoutSeconds=900,[int]$OutputLimit=1048576)
    if($TimeoutSeconds -lt 1 -or $TimeoutSeconds -gt 900 -or $OutputLimit -lt 1 -or $OutputLimit -gt 1048576) { throw 'PROCESS_LIMIT_INVALID' }
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.UseShellExecute=$false; $start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true; $start.WorkingDirectory=$WorkingDirectory
    if([IO.Path]::GetExtension($File) -in @('.bat','.cmd')) {
        $start.FileName=(Get-Process -Id $PID).Path
        foreach($arg in @('-NoProfile','-Command','& $env:VSRQG_M3_CHILD @($env:VSRQG_M3_ARGS | ConvertFrom-Json); exit $LASTEXITCODE')) { $start.ArgumentList.Add($arg) }
        $start.Environment['VSRQG_M3_CHILD']=$File
        $start.Environment['VSRQG_M3_ARGS']=ConvertTo-Json -Compress -InputObject @($Arguments)
    } else { $start.FileName=$File; foreach($arg in $Arguments) { $start.ArgumentList.Add($arg) } }
    foreach($name in $Environment.Keys) { $start.Environment[$name]=$Environment[$name] }
    $process=[Diagnostics.Process]::new(); $process.StartInfo=$start
    $started=$false
    try {
        $started=$process.Start()
        if(-not $started) { throw 'PROCESS_START_FAILED' }
        $clock=[Diagnostics.Stopwatch]::StartNew()
        $streams=@($process.StandardOutput,$process.StandardError)
        $buffers=@([char[]]::new(4096),[char[]]::new(4096))
        $text=@([Text.StringBuilder]::new(),[Text.StringBuilder]::new())
        $pending=@($streams[0].ReadAsync($buffers[0],0,4096),$streams[1].ReadAsync($buffers[1],0,4096))
        $done=@($false,$false)
        while(-not ($done[0] -and $done[1] -and $process.HasExited)) {
            if($clock.Elapsed.TotalSeconds -gt $TimeoutSeconds) { throw 'PROCESS_TIMEOUT' }
            for($i=0;$i -lt 2;$i++) {
                if(-not $done[$i] -and $pending[$i].IsCompleted) {
                    $count=$pending[$i].GetAwaiter().GetResult()
                    if($count -eq 0) { $done[$i]=$true; continue }
                    if($text[0].Length+$text[1].Length+$count -gt $OutputLimit) { throw 'PROCESS_OUTPUT_LIMIT' }
                    $null=$text[$i].Append($buffers[$i],0,$count)
                    $pending[$i]=$streams[$i].ReadAsync($buffers[$i],0,4096)
                }
            }
            Start-Sleep -Milliseconds 10
        }
        return @{ExitCode=$process.ExitCode;Output=$text[0].ToString();Error=$text[1].ToString()}
    } finally {
        if($started -and -not $process.HasExited) {
            $process.Kill($true)
            if(-not $process.WaitForExit(10000)) { throw 'PROCESS_CLEANUP_FAILED' }
        }
        $process.Dispose()
    }
}
