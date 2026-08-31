$ErrorActionPreference='Stop'
$Root='C:\WRO_EXTERNAL_COMPILER\MT5_6116'
$Stage='C:\WRO_EXTERNAL_COMPILER\MT5_6116_STAGE'
$ExpectedMetaEditorSha='F6E48C5F1AB83729BBF9580E90F4ACF466E05BB8A725A63D0C205DCCE2BB3778'
$Setup=Join-Path $env:TEMP 'wro-mt5setup.exe'
$Url='https://download.mql5.com/cdn/web/metaquotes.software.corp/mt5/mt5setup.exe'
New-Item -ItemType Directory -Force 'C:\WRO_EXTERNAL_COMPILER' | Out-Null
Remove-Item $Stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Stage | Out-Null
Invoke-WebRequest -Uri $Url -OutFile $Setup -UseBasicParsing
$p=Start-Process -FilePath $Setup -ArgumentList @('/auto','/path:'+('"'+$Stage+'"')) -PassThru
if(-not $p.WaitForExit(600000)){try{$p.Kill()}catch{};throw 'MT5_SETUP_TIMEOUT'}
$me=Get-ChildItem $Stage -Filter MetaEditor64.exe -File -Recurse | Select-Object -First 1
if(-not $me){throw 'METAEDITOR_NOT_FOUND_AFTER_SETUP'}
$sha=(Get-FileHash $me.FullName -Algorithm SHA256).Hash
if($sha -ne $ExpectedMetaEditorSha){throw ('METAEDITOR_6116_HASH_MISMATCH:'+ $sha)}
$trade=Get-ChildItem $Stage -Filter Trade.mqh -File -Recurse | Where-Object {$_.FullName -match '\\MQL5\\Include\\Trade\\Trade\.mqh$'} | Select-Object -First 1
if(-not $trade){throw 'STANDARD_MQL5_INCLUDE_MISSING'}
Remove-Item $Root -Recurse -Force -ErrorAction SilentlyContinue
Move-Item $Stage $Root
$me2=Get-ChildItem $Root -Filter MetaEditor64.exe -File -Recurse | Select-Object -First 1
$trade2=Get-ChildItem $Root -Filter Trade.mqh -File -Recurse | Where-Object {$_.FullName -match '\\MQL5\\Include\\Trade\\Trade\.mqh$'} | Select-Object -First 1
[ordered]@{status='PASS';metaeditor=$me2.FullName;metaeditor_sha256=(Get-FileHash $me2.FullName -Algorithm SHA256).Hash;trade_mqh=$trade2.FullName;built_at_utc=(Get-Date).ToUniversalTime().ToString('o')} | ConvertTo-Json | Set-Content (Join-Path $Root 'WRO_MT5_6116_LOCAL_BUILD_RECEIPT.json') -Encoding UTF8
Write-Host 'WRO_MT5_6116_LOCAL_BUILD=PASS'