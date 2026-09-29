param([ValidateSet('all', 'runtime', 'kws', 'asr', 'vad')][string]$Component = 'all')
$ErrorActionPreference = 'Stop'
$voiceRoot = Split-Path -Parent $PSScriptRoot
$voiceAssets = Join-Path $voiceRoot 'android/app/src/main/assets/voice'
$voiceLibs = Join-Path $voiceRoot 'android/app/libs'
$voiceCache = Join-Path $voiceRoot '.voice-downloads'
New-Item -ItemType Directory -Force $voiceAssets, $voiceLibs, $voiceCache | Out-Null

function Get-VoiceFile($Url, $Destination, $MinimumSize, $Sha256 = '') {
  $partial = "$Destination.part"
  if ((Test-Path -LiteralPath $Destination) -and (Get-Item -LiteralPath $Destination).Length -ge $MinimumSize) {
    if ($Destination.EndsWith('.aar')) {
      try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($Destination)
        $zip.Dispose()
        return
      } catch { Move-Item -LiteralPath $Destination -Destination $partial -Force }
    } elseif (-not $Sha256 -or (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -eq $Sha256) { return }
    else { throw "Checksum mismatch: $Destination" }
  }
  $requestTimeout = @{ TimeoutSec = 30 }
  if ((Get-Command Invoke-WebRequest).Parameters.ContainsKey('OperationTimeoutSeconds')) {
    $requestTimeout.OperationTimeoutSeconds = 30
  }
  if ($Sha256) {
    # Small HTTP ranges avoid long, stalled CDN responses; append only complete chunks.
    $chunkFile = Join-Path $voiceCache 'sensevoice.chunk'
    while (-not (Test-Path -LiteralPath $partial) -or (Get-Item -LiteralPath $partial).Length -lt $MinimumSize) {
      $offset = if (Test-Path -LiteralPath $partial) { (Get-Item -LiteralPath $partial).Length } else { 0 }
      $end = [Math]::Min($offset + 8MB - 1, $MinimumSize - 1)
      $chunkComplete = $false
      for ($attempt = 1; $attempt -le 6; $attempt++) {
        try {
          $response = Invoke-WebRequest -Uri "$Url`?download=true" -Headers @{ Range = "bytes=$offset-$end" } -OutFile $chunkFile -PassThru @requestTimeout
          if ($response.StatusCode -ne 206 -or (($response.Headers['Content-Range'] -join '') -ne "bytes $offset-$end/$MinimumSize") -or (Get-Item -LiteralPath $chunkFile).Length -ne ($end - $offset + 1)) {
            throw 'Unexpected model range response'
          }
          $chunkComplete = $true
          break
        } catch { Write-Host "Retrying model chunk at $offset ($attempt/6)" }
      }
      if (-not $chunkComplete) { throw 'Unable to download model chunk; rerun to resume' }
      $inputStream = [System.IO.File]::OpenRead($chunkFile)
      $outputStream = [System.IO.File]::Open($partial, [System.IO.FileMode]::Append, [System.IO.FileAccess]::Write)
      try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
      Write-Host "SenseVoice: $($end + 1)/$MinimumSize bytes"
    }
    if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $Sha256) { throw 'Model checksum mismatch' }
    Move-Item -LiteralPath $partial -Destination $Destination -Force
    return
  }
  $downloaded = $false
  for ($attempt = 1; $attempt -le 20; $attempt++) {
    try {
      Invoke-WebRequest -Uri $Url -OutFile $partial -Resume @requestTimeout
      $downloaded = $true
      break
    } catch {
      Write-Host "Download interrupted; resuming ($attempt/20): $Url"
    }
  }
  if (-not $downloaded) { throw "Download failed: $Url" }
  if ((Get-Item -LiteralPath $partial).Length -lt $MinimumSize) { throw "Invalid download: $Url" }
  if ($Sha256 -and (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $Sha256) { throw "Checksum mismatch: $Url" }
  Move-Item -LiteralPath $partial -Destination $Destination -Force
}

if ($Component -in @('all', 'runtime')) {
Get-VoiceFile 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar' (Join-Path $voiceLibs 'sherpa-onnx-1.13.8.aar') 1000000
if ((Get-FileHash -LiteralPath (Join-Path $voiceLibs 'sherpa-onnx-1.13.8.aar')).Hash -ne '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96') {
  throw 'sherpa-onnx runtime checksum mismatch'
}
$runtime = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $voiceLibs 'sherpa-onnx-1.13.8.aar'))
try {
  if (-not $runtime.GetEntry('classes.jar')) { throw 'Incomplete sherpa-onnx runtime' }
} finally { $runtime.Dispose() }
}
if ($Component -in @('all', 'kws')) {
$kwsName = 'sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20'
$kwsArchive = Join-Path $voiceCache "$kwsName.tar.bz2"
Get-VoiceFile "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$kwsName.tar.bz2" $kwsArchive 1000000
& tar -xf $kwsArchive -C $voiceCache
if ($LASTEXITCODE -ne 0) { throw 'Failed to extract keyword model' }
$kwsOutput = Join-Path $voiceAssets 'kws'
$asrOutput = Join-Path $voiceAssets 'asr'
New-Item -ItemType Directory -Force $kwsOutput, $asrOutput | Out-Null
$kwsFiles = @{
  'encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx' = 'encoder.int8.onnx'
  'decoder-epoch-13-avg-2-chunk-8-left-64.onnx' = 'decoder.onnx'
  'joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx' = 'joiner.int8.onnx'
  'tokens.txt' = 'tokens.txt'
}
foreach ($file in $kwsFiles.GetEnumerator()) {
  Copy-Item -LiteralPath (Join-Path $voiceCache "$kwsName/$($file.Key)") -Destination (Join-Path $kwsOutput $file.Value) -Force
}
}
if ($Component -in @('all', 'asr')) {
$asrOutput = Join-Path $voiceAssets 'asr'
New-Item -ItemType Directory -Force $asrOutput | Out-Null
$asrBase = 'https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main'
Get-VoiceFile "$asrBase/model.int8.onnx" (Join-Path $asrOutput 'model.int8.onnx') 239233841 'c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51'
Get-VoiceFile "$asrBase/tokens.txt" (Join-Path $asrOutput 'tokens.txt') 100000
}
if ($Component -in @('all', 'vad')) {
Get-VoiceFile 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx' (Join-Path $voiceAssets 'silero_vad.onnx') 100000
}
Get-ChildItem -LiteralPath $voiceAssets -Recurse -File | Select-Object FullName, Length
