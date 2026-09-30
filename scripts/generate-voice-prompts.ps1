$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskPrompts = Get-Content -Raw -Encoding UTF8 (Join-Path $taskRoot 'src/resources/voice/prompts.json') | ConvertFrom-Json
$taskOutput = Join-Path $taskRoot 'android/app/src/main/res/raw'
New-Item -ItemType Directory -Force $taskOutput | Out-Null
$taskVoice = New-Object -ComObject SAPI.SpVoice
$taskChinese = $taskVoice.GetVoices() | Where-Object { $_.GetDescription() -like '*Huihui*' } | Select-Object -First 1
if ($null -eq $taskChinese) { throw 'Microsoft Huihui Chinese voice is required to regenerate bundled prompts.' }
$taskVoice.Voice = $taskChinese
foreach ($taskPrompt in $taskPrompts.PSObject.Properties) {
    $taskName = 'voice_' + $taskPrompt.Name.ToLowerInvariant() + '.wav'
    $taskFile = New-Object -ComObject SAPI.SpFileStream
    try {
        $taskFile.Open((Join-Path $taskOutput $taskName), 3)
        $taskVoice.AudioOutputStream = $taskFile
        [void]$taskVoice.Speak($taskPrompt.Value)
    } finally { $taskFile.Close() }
}
Write-Output "Generated $($taskPrompts.PSObject.Properties.Name.Count) bundled Chinese prompts."
