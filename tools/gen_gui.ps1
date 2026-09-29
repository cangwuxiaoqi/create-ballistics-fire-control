# ===========================================================================
#  Generate the CLEAN GUI background textures for Create: Ballistics.
#
#  2026-09-24 -- REWRITTEN for the "no side button column" layout.
#
#  What changed and why:
#    * Both panels are still 560x300 and still 1:1 (no upscaling).
#    * The OLD art had a right-hand column of 6-8 amber well outlines plus a
#      vertical divider -- on the COMPUTER the code only ever used 6 of them,
#      and on the COORDINATOR it used NONE of them (the coordinator screen has
#      no buttons at all, yet its art was a copy of the computer's). Removing
#      that column is what lets the gun list run the full width, which is what
#      makes ONE LINE PER GUN possible.
#    * Every well drawn here is a well the code actually puts a button on.
#      Computer: 6 wells (the exact button count).
#      Coordinator: 0 wells.
#
#  Design rule (unchanged): the background carries NO text and NO numbers --
#  only the frame and the empty slots. All labels are drawn at runtime by the
#  Screen classes, so text stays crisp and can be localised without touching
#  the art.
#
#  Palette below was sampled from the previous textures so the two screens
#  keep the same look (dark metal, matches the CBC casing).
#
#  Keep this file ASCII-only: PowerShell 5.1 reads .ps1 as the ANSI codepage.
# ===========================================================================

Add-Type -AssemblyName System.Drawing

$outRoot = Join-Path $PSScriptRoot '..\src\main\resources\assets\create_ballistics\textures\gui'
$outRoot = [System.IO.Path]::GetFullPath($outRoot)

# ---- palette (sampled from the old art) ----
$FRAME_OUTER = [System.Drawing.Color]::FromArgb(255, 14, 14, 18)    # #0E0E12
$FRAME_LIGHT = [System.Drawing.Color]::FromArgb(255, 74, 74, 88)    # #4A4A58
$PANEL       = [System.Drawing.Color]::FromArgb(255, 35, 35, 43)    # #23232B
$SLOT_FILL   = [System.Drawing.Color]::FromArgb(255, 26, 26, 32)    # #1A1A20
$AMBER       = [System.Drawing.Color]::FromArgb(255, 184, 146, 58)  # #B8923A
$AMBER_DIM   = [System.Drawing.Color]::FromArgb(255, 106, 90, 40)   # #6A5A28

function New-Canvas([int]$w, [int]$h) {
    $bmp = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    return @($bmp, $g)
}

function Fill([System.Drawing.Graphics]$g, [int]$x, [int]$y, [int]$w, [int]$h, $color) {
    $brush = New-Object System.Drawing.SolidBrush($color)
    $g.FillRectangle($brush, $x, $y, $w, $h)
    $brush.Dispose()
}

function Save([System.Drawing.Bitmap]$bmp, [string]$rel) {
    $path = Join-Path $outRoot $rel
    $dir = Split-Path $path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Output ("  wrote " + $rel + "  (" + $bmp.Width + "x" + $bmp.Height + ")")
    $bmp.Dispose()
}

# Panel frame: 2px dark, 1px light bevel, panel fill.
function Draw-Frame([System.Drawing.Graphics]$g, [int]$w, [int]$h) {
    Fill $g 0 0 $w $h $FRAME_OUTER
    Fill $g 2 2 ($w - 4) 1 $FRAME_LIGHT
    Fill $g 2 2 1 ($h - 4) $FRAME_LIGHT
    Fill $g ($w - 3) 2 1 ($h - 4) $FRAME_LIGHT
    Fill $g 2 ($h - 3) ($w - 4) 1 $FRAME_LIGHT
    Fill $g 3 3 ($w - 6) ($h - 6) $PANEL
}

# A recessed slot with the amber outline (the coordinate input box,
# and a button well -- they look the same in the old art).
function Draw-Well([System.Drawing.Graphics]$g, [int]$x, [int]$y, [int]$w, [int]$h) {
    Fill $g $x $y $w $h $AMBER
    Fill $g ($x + 1) ($y + 1) ($w - 2) ($h - 2) $AMBER_DIM
    Fill $g ($x + 2) ($y + 2) ($w - 4) ($h - 4) $SLOT_FILL
}

# ===========================================================================
# 火控协调器  Fire Control Coordinator  --  560 x 300, NO buttons
#   The whole panel is text; nothing to lay out but the frame.
# ===========================================================================
$c = New-Canvas 560 300
Draw-Frame $c[1] 560 300
Save $c[0] 'panel\coordinator.png'

# ===========================================================================
# 火控计算机  Fire Control Computer  --  560 x 300, 6 buttons + 1 input box
#
#   MUST match FireControlComputerScreen's constants exactly.
#   (x, y, w, h) in panel pixels:
#     input box      ( 16, 222, 360, 22)
#     commit         (384, 222, 160, 22)
#     clear          ( 16, 248, 110, 22)
#     arc            (132, 248, 132, 22)
#     fire mode      (272, 248, 132, 22)
#     param          (412, 248, 132, 22)
#     FIRE           ( 16, 274, 528, 22)
# ===========================================================================
$c = New-Canvas 560 300
$g = $c[1]
Draw-Frame $g 560 300

Draw-Well $g  16 222 360 22    # coordinate input box
Draw-Well $g 384 222 160 22    # commit
Draw-Well $g  16 248 110 22    # clear
Draw-Well $g 132 248 132 22    # arc mode
Draw-Well $g 272 248 132 22    # fire mode
Draw-Well $g 412 248 132 22    # fire mode parameter
Draw-Well $g  16 274 528 22    # FIRE

Save $c[0] 'panel\computer.png'

Write-Output "done."
