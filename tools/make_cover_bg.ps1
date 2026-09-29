# ===========================================================================
# Extracts a clean COVER BACKGROUND out of several reference covers.
#
# All MC百科 covers in the reference set share one background template
# (480x300 = exactly 8:5, i.e. the 240x150 cover cell x 2). It is a hand
# drawn pattern -- a white outer frame, a steel-blue gradient and an
# OFFSET grid (the vertical lines do not line up from band to band), which
# is why it cannot be reproduced by drawing a regular grid. The subjects
# differ from image to image, so the background is recovered per pixel:
#
#   pass 1  median across all references            -> rough background
#   pass 2  for every pixel keep only the references that AGREE with pass 1
#           (|diff| <= -Tolerance) and take the median of those. Wherever at
#           least 2 references agree we now have true reference pixels, not a
#           blend of subjects.
#   pass 3  the few pixels where nothing agrees (all subjects overlap) are
#           filled by linear interpolation between the nearest good pixel on
#           the left and on the right of the same row.
#
# Result: pixel-identical to the references outside the subjects, and a
# smooth continuation of the gradient where the subjects all overlap
# (which is exactly the area the hero block covers anyway).
#
# NOTE: ASCII only -- Windows PowerShell 5.1 reads .ps1 as ANSI without a BOM.
#
# usage: powershell -File make_cover_bg.ps1 -RefDir <dir> -Out <png>
# ===========================================================================
param(
    [Parameter(Mandatory = $true)][string]$RefDir,
    [Parameter(Mandatory = $true)][string]$Out,
    [int]$Tolerance = 30,     # summed per-channel deviation still counted as "same as background"
    [int]$Dilate = 2          # grow the hole mask a little before inpainting
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

function Get-Pixels([string]$path) {
    $img = [System.Drawing.Image]::FromFile($path)
    $bmp = New-Object System.Drawing.Bitmap($img)
    $rect = New-Object System.Drawing.Rectangle(0, 0, $bmp.Width, $bmp.Height)
    $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $bytes = New-Object byte[] ($data.Stride * $bmp.Height)
    [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
    $bmp.UnlockBits($data)
    $w = $bmp.Width; $h = $bmp.Height; $stride = $data.Stride
    $img.Dispose(); $bmp.Dispose()
    return @{ Bytes = $bytes; W = $w; H = $h; Stride = $stride }
}

$refs = Get-ChildItem -LiteralPath $RefDir -Filter *.png | Sort-Object Name
if ($refs.Count -lt 3) { throw "need at least 3 reference covers in $RefDir, found $($refs.Count)" }
$imgs = @()
foreach ($r in $refs) { $imgs += ,(Get-Pixels $r.FullName) }
$W = $imgs[0].W; $H = $imgs[0].H; $Stride = $imgs[0].Stride
foreach ($im in $imgs) { if ($im.W -ne $W -or $im.H -ne $H) { throw "reference sizes differ" } }
$n = $imgs.Count
"references: $n  size: ${W}x${H}"

# --- pass 1: plain median ----------------------------------------------------
$med = New-Object byte[] ($Stride * $H)
$c = New-Object 'int[]' $n
$est = New-Object 'int[]' ($W * $H * 3)
for ($y = 0; $y -lt $H; $y++) {
    for ($x = 0; $x -lt $W; $x++) {
        $o = $y * $Stride + $x * 4
        $ei = (($y * $W) + $x) * 3
        for ($ch = 0; $ch -lt 3; $ch++) {
            for ($i = 0; $i -lt $n; $i++) { $c[$i] = $imgs[$i].Bytes[$o + $ch] }
            [Array]::Sort($c)
            $est[$ei + $ch] = $c[[int]($n / 2)]
        }
        $med[$o + 3] = 255
    }
}
# copy the pass-1 estimate into the output buffer (packed -> stride layout);
# pass 3 will overwrite the subject pixels in place
for ($y = 0; $y -lt $H; $y++) {
    for ($x = 0; $x -lt $W; $x++) {
        $o = $y * $Stride + $x * 4
        $ei = (($y * $W) + $x) * 3
        $med[$o] = [byte]$est[$ei]
        $med[$o + 1] = [byte]$est[$ei + 1]
        $med[$o + 2] = [byte]$est[$ei + 2]
    }
}

# --- pass 2: mark the pixels that are certainly background -------------------
# A pixel is "known" only when ALL references agree with the pass-1 median
# (deviation <= Tolerance). Anything else is subject territory in at least one
# reference -- including the white sticker outlines the covers put around their
# subjects, which is why "at least 2 agree" is not good enough: two white
# outlines agree with each other perfectly and leave white streaks behind.
$known = New-Object bool[] ($W * $H)
$knownCount = 0
for ($y = 0; $y -lt $H; $y++) {
    for ($x = 0; $x -lt $W; $x++) {
        $o = $y * $Stride + $x * 4
        $ei = (($y * $W) + $x) * 3
        $ok = $true
        for ($i = 0; $i -lt $n; $i++) {
            $d = 0
            for ($ch = 0; $ch -lt 3; $ch++) { $d += [Math]::Abs($imgs[$i].Bytes[$o + $ch] - $est[$ei + $ch]) }
            if ($d -gt $Tolerance) { $ok = $false; break }
        }
        if ($ok) { $known[$y * $W + $x] = $true; $knownCount++ }
    }
}
"known background pixels: $knownCount / $($W * $H)  ($([int](100.0 * $knownCount / ($W * $H)))%)"

# --- pass 3: fill the unknown pixels, row by row -----------------------------
# Each unknown run is painted with a linear blend between the nearest known
# pixel on its left and on its right (read from $est with the packed index --
# $med uses the bitmap stride layout, the two are NOT interchangeable).
$filled = 0
$rowsTouched = 0
for ($y = 0; $y -lt $H; $y++) {
    $x = 0
    $rowTouched = $false
    while ($x -lt $W) {
        if ($known[$y * $W + $x]) { $x++; continue }
        $x0 = $x
        while ($x -lt $W -and -not $known[$y * $W + $x]) { $x++ }
        $x1 = $x - 1
        $l = $x0 - 1
        while ($l -ge 0 -and -not $known[$y * $W + $l]) { $l-- }
        $r = $x1 + 1
        while ($r -lt $W -and -not $known[$y * $W + $r]) { $r++ }
        $hasL = ($l -ge 0); $hasR = ($r -lt $W)
        if (-not $hasL -and -not $hasR) { continue }     # nothing to anchor to
        for ($xx = $x0; $xx -le $x1; $xx++) {
            $o = $y * $Stride + $xx * 4
            if ($hasL -and $hasR) { $t = ($xx - $l) / [double]($r - $l) }
            elseif ($hasR) { $t = 1.0 } else { $t = 0.0 }
            $iL = (($y * $W) + $l) * 3
            $iR = (($y * $W) + $r) * 3
            for ($ch = 0; $ch -lt 3; $ch++) {
                $a = if ($hasL) { $est[$iL + $ch] } else { $est[$iR + $ch] }
                $b = if ($hasR) { $est[$iR + $ch] } else { $est[$iL + $ch] }
                $med[$o + $ch] = [byte][Math]::Round($a + ($b - $a) * $t)
            }
            $filled++
        }
        $rowTouched = $true
    }
    if ($rowTouched) { $rowsTouched++ }
}
"rows filled: $rowsTouched   pixels repainted: $filled"

# --- write out --------------------------------------------------------------
$outBmp = New-Object System.Drawing.Bitmap($W, $H, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$od = $outBmp.LockBits((New-Object System.Drawing.Rectangle(0, 0, $W, $H)), [System.Drawing.Imaging.ImageLockMode]::WriteOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
[System.Runtime.InteropServices.Marshal]::Copy($med, 0, $od.Scan0, [Math]::Min($med.Length, $od.Stride * $H))
$outBmp.UnlockBits($od)
$dir = Split-Path -Parent $Out
if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
$outBmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$outBmp.Dispose()
"OK  $Out  ($W x $H)"
