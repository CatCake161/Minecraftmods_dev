$ErrorActionPreference = 'Stop'

$dir = Join-Path $PSScriptRoot 'testmedia'
New-Item -ItemType Directory -Force $dir | Out-Null

Write-Host 'Generating 720p30 test clip...'
& ffmpeg -y `
	-f lavfi -i 'testsrc2=size=1280x720:rate=30' `
	-f lavfi -i 'aevalsrc=0.12*sin(2*PI*440*t)|0.12*sin(2*PI*554*t):s=48000:c=stereo' `
	-t 10 `
	-c:v libx264 -preset veryfast -pix_fmt yuv420p -b:v 4M -g 30 `
	-c:a aac -b:a 192k `
	-movflags +faststart `
	(Join-Path $dir 'test720p30.mp4')

Write-Host 'Generating 1080p60 test clip...'
& ffmpeg -y `
	-f lavfi -i 'testsrc2=size=1920x1080:rate=60' `
	-f lavfi -i 'aevalsrc=0.12*sin(2*PI*440*t)|0.12*sin(2*PI*554*t):s=48000:c=stereo' `
	-t 10 `
	-c:v libx264 -preset veryfast -pix_fmt yuv420p -b:v 8M -g 60 `
	-c:a aac -b:a 192k `
	-movflags +faststart `
	(Join-Path $dir 'test1080p60.mp4')

Write-Host 'Generating 5.1 channel-mapping clip...'
& ffmpeg -y `
	-f lavfi -i 'aevalsrc=0.12*sin(2*PI*440*t)|0.12*sin(2*PI*554*t)|0.12*sin(2*PI*659*t)|0.12*sin(2*PI*80*t)|0.12*sin(2*PI*880*t)|0.12*sin(2*PI*988*t):s=48000:c=5.1' `
	-t 5 `
	-c:a aac -b:a 384k `
	-movflags +faststart `
	(Join-Path $dir 'test51.mp4')

Get-ChildItem $dir | Select-Object Name, Length | Format-Table -AutoSize
