$ErrorActionPreference = 'Stop'

$dir = Join-Path $PSScriptRoot 'testmedia'
New-Item -ItemType Directory -Force $dir | Out-Null

Write-Host 'Generating 720p30 test clip...'
& ffmpeg -y `
	-f lavfi -i 'testsrc2=size=1280x720:rate=30' `
	-f lavfi -i 'sine=frequency=440:sample_rate=48000' `
	-t 10 `
	-c:v libx264 -preset veryfast -pix_fmt yuv420p -b:v 4M -g 30 `
	-c:a aac -b:a 128k `
	-movflags +faststart `
	(Join-Path $dir 'test720p30.mp4')

Write-Host 'Generating 1080p60 test clip...'
& ffmpeg -y `
	-f lavfi -i 'testsrc2=size=1920x1080:rate=60' `
	-f lavfi -i 'sine=frequency=440:sample_rate=48000' `
	-t 10 `
	-c:v libx264 -preset veryfast -pix_fmt yuv420p -b:v 8M -g 60 `
	-c:a aac -b:a 128k `
	-movflags +faststart `
	(Join-Path $dir 'test1080p60.mp4')

Get-ChildItem $dir | Select-Object Name, Length | Format-Table -AutoSize
