# ===========================================================================
# Builds a mod cover image out of the REAL block textures (no AI, no redraw).
#
#   isometric mode (default): the chosen block seen from the north-west-above,
#       i.e. the three faces visible in an isometric view: north (the hero
#       face), west and top.
#   -Flat : just the hero face texture, scaled up with nearest neighbour.
#
# The background is NOT drawn by this script: it is the template extracted
# from the reference covers by tools/make_cover_bg.ps1 (white outer frame +
# steel-blue gradient + offset grid). It is 480x300 = exactly 8:5, the same
# aspect as the 240x150 cover cell, so it is simply scaled to the canvas.
#
# -Hero picks which block: coordinator (default) | computer | node
#
# Texture -> face mapping, from models/block/fire_control_*.json:
#   north             -> block/fire_control_<hero>.png   (the panel, the hero)
#   top / east / west -> block/cbc_casing.png            (plain brass plate)
#
# --- isometric maths (screen Y grows downwards) ---------------------------
#   block +X (east)  -> ( +0.866, -0.5 ) * e     (right, up)
#   block +Z (south) -> ( -0.866, -0.5 ) * e     (left,  up)
#   block +Y (up)    -> (  0.0,   -1.0 ) * e     (straight up)
# All three edges project to the SAME length e, which is what makes it a
# true isometric. With this basis the vertex shared by the three visible
# faces is the block's north-west-TOP corner, and it lands in the middle of
# the hexagonal silhouette -- so that corner is what we anchor to the canvas
# centre (NOT the bottom corner: that mistake makes the top face collapse
# and the block turns into a "V").
#   silhouette (a pointy-top regular hexagon, e = edge length):
#       (0,-e) top, (+0.866e,-0.5e), (+0.866e,+0.5e), (0,+e), (-0.866e,+0.5e), (-0.866e,-0.5e)
#   the white sticker outline is that hexagon, filled + stroked with a fat
#   white pen (half the stroke falls inside and gets covered by the cube).
#
# NOTE: ASCII only -- Windows PowerShell 5.1 reads .ps1 as ANSI without a BOM.
#
# usage:
#   powershell -File make_mod_cover.ps1 -Hero coordinator -W 240 -H 150 -OutDir "D:\...\封面" -Name 封面
# ===========================================================================
param(
    [int]$W = 960,
    [int]$H = 600,
    [string]$OutDir = 'D:\moddev\cbc_fire_control\docs\cover',
    [string]$Name = 'cover',
    [string]$Hero = 'coordinator',
    [string]$Bg = 'template',                 # template | white | transparent
    [string]$BgTemplate = 'D:\moddev\cbc_fire_control\tools\cover_bg_480x300.png',
    [switch]$Flat
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$texDir = 'D:\moddev\cbc_fire_control\src\main\resources\assets\create_ballistics\textures\block'
if ($Hero -notin @('coordinator', 'computer', 'node')) { throw "unknown -Hero '$Hero' (coordinator|computer|node)" }
$heroPath = Join-Path $texDir ("fire_control_" + $Hero + ".png")
$platePath = Join-Path $texDir 'cbc_casing.png'
foreach ($p in @($heroPath, $platePath)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "missing file: $p" }
}
if ($Bg -eq 'template' -and -not (Test-Path -LiteralPath $BgTemplate)) { throw "missing file: $BgTemplate" }
$heroImg = [System.Drawing.Image]::FromFile($heroPath)
$plate = [System.Drawing.Image]::FromFile($platePath)
$bgImg = $null
if ($Bg -eq 'template') { $bgImg = [System.Drawing.Image]::FromFile($BgTemplate) }

if (-not (Test-Path -LiteralPath $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }

# ---------------------------------------------------------------- helpers ----
function New-Shaded([double]$k) {
    $cm = New-Object System.Drawing.Imaging.ColorMatrix
    $cm.Matrix00 = [single]$k
    $cm.Matrix11 = [single]$k
    $cm.Matrix22 = [single]$k
    $cm.Matrix33 = [single]1.0
    $cm.Matrix44 = [single]1.0
    $ia = New-Object System.Drawing.Imaging.ImageAttributes
    $ia.SetColorMatrix($cm)
    return $ia
}

function Draw-Face($g, [System.Drawing.Image]$img, [System.Drawing.PointF[]]$pts, $ia) {
    $src = New-Object System.Drawing.RectangleF(0, 0, 16, 16)
    $g.DrawImage($img, $pts, $src, [System.Drawing.GraphicsUnit]::Pixel, $ia)
}

function New-Pt([double]$x, [double]$y) {
    return New-Object System.Drawing.PointF([single]$x, [single]$y)
}

# ----------------------------------------------------------------- canvas ----
$bmp = New-Object System.Drawing.Bitmap($W, $H, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half

# --- background ------------------------------------------------------------
#   template    : the background pulled out of the reference covers, drawn
#                 with a high quality resampler (soft gradient + thin lines,
#                 so bicubic downsamples much better than nearest neighbour)
#   white       : plain white -- for hand editing
#   transparent : nothing, so the PNG can be pasted onto any background
if ($Bg -eq 'template') {
    $bgAttrs = New-Object System.Drawing.Imaging.ImageAttributes
    $g.DrawImage($bgImg, (New-Object System.Drawing.Rectangle(0, 0, $W, $H)), 0, 0, $bgImg.Width, $bgImg.Height, [System.Drawing.GraphicsUnit]::Pixel, $bgAttrs)
}
elseif ($Bg -eq 'white') {
    $whiteBg = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $g.FillRectangle($whiteBg, 0, 0, $W, $H)
}
elseif ($Bg -ne 'transparent') {
    throw "unknown -Bg '$Bg' (template|white|transparent)"
}
# a white outline is pointless on a white background
$drawOutline = ($Bg -ne 'white')

$cx = [double]($W / 2.0)
$cy = [double]($H / 2.0)

# ---------------------------------------------------------------- subject ----
$outlineW = [single]([Math]::Max(2.0, $H * 0.020))   # full pen width; half ends up outside

if ($Flat) {
    $scale = [int][Math]::Round(($H * 0.74) / 16.0)
    if ($scale -lt 1) { $scale = 1 }
    $side = 16 * $scale
    $dx = [single](($W - $side) / 2.0)
    $dy = [single](($H - $side) / 2.0)

    $shadowPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $shadowPath.AddEllipse([single]($dx + $side * 0.06), [single]($dy + $side * 0.86), [single]($side * 0.88), [single]($side * 0.16))
    $sgb = New-Object System.Drawing.Drawing2D.PathGradientBrush($shadowPath)
    $sgb.CenterColor = [System.Drawing.Color]::FromArgb(110, 10, 26, 48)
    $sgb.SurroundColors = [System.Drawing.Color[]]@([System.Drawing.Color]::FromArgb(0, 10, 26, 48))
    $g.FillPath($sgb, $shadowPath)

    if ($drawOutline) {
        $o = [single]($outlineW / 2.0)
        $white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
        $g.FillRectangle($white, [single]($dx - $o), [single]($dy - $o), [single]($side + $outlineW), [single]($side + $outlineW))
    }

    $dst = New-Object System.Drawing.RectangleF($dx, $dy, $side, $side)
    $src = New-Object System.Drawing.RectangleF(0, 0, 16, 16)
    $g.DrawImage($heroImg, $dst, $src, [System.Drawing.GraphicsUnit]::Pixel)
}
else {
    $e = [double]$H * 0.395                 # one block edge, in pixels
    $pCx = $cx
    $pCy = $cy + $e * 0.25                  # silhouette centre correction

    $exX = 0.866 * $e; $exY = -0.5 * $e      # east  -> right, up
    $ezX = -0.866 * $e; $ezY = -0.5 * $e     # south -> left,  up
    $eyX = 0.0; $eyY = -1.0 * $e             # up    -> straight up

    # C = block's north-west-top corner = the vertex the three faces share
    $C = New-Pt $pCx $pCy
    $P_NE_Top = New-Pt ($pCx + $exX) ($pCy + $exY)
    $P_SW_Top = New-Pt ($pCx + $ezX) ($pCy + $ezY)
    $P_NW_Bot = New-Pt ($pCx - $eyX) ($pCy - $eyY)
    $P_SW_Bot = New-Pt ($pCx + $ezX - $eyX) ($pCy + $ezY - $eyY)
    $P_NE_Bot = New-Pt ($pCx + $exX - $eyX) ($pCy + $exY - $eyY)

    # ground shadow
    $shW = 1.70 * $e
    $shH = 0.34 * $e
    $shCx = $pCx + ($exX + $ezX) / 2.0
    $shCy = $pCy + 0.60 * $e
    $shadowPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $shadowPath.AddEllipse([single]($shCx - $shW / 2), [single]($shCy - $shH / 2), [single]$shW, [single]$shH)
    $sgb = New-Object System.Drawing.Drawing2D.PathGradientBrush($shadowPath)
    $sgb.CenterColor = [System.Drawing.Color]::FromArgb(110, 10, 26, 48)
    $sgb.SurroundColors = [System.Drawing.Color[]]@([System.Drawing.Color]::FromArgb(0, 10, 26, 48))
    $g.FillPath($sgb, $shadowPath)

    # white sticker outline: the hexagonal silhouette, filled and stroked
    $hex = [System.Drawing.PointF[]]@(
        (New-Pt $pCx ($pCy + $eyY)),                     # top
        $P_NE_Top,
        $P_NE_Bot,
        (New-Pt $pCx ($pCy - $eyY)),                     # bottom
        $P_SW_Bot,
        $P_SW_Top
    )
    $whiteBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    if ($drawOutline) {
        $whitePen = New-Object System.Drawing.Pen([System.Drawing.Color]::White, $outlineW)
        $whitePen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
        $g.FillPolygon($whiteBrush, $hex)
        $g.DrawPolygon($whitePen, $hex)
    }

    $iaTop = New-Shaded 1.00
    $iaHero = New-Shaded 0.94
    $iaWest = New-Shaded 0.72

    Draw-Face $g $plate ([System.Drawing.PointF[]]@($C, $P_NE_Top, $P_SW_Top)) $iaTop
    Draw-Face $g $plate ([System.Drawing.PointF[]]@($P_SW_Top, $C, $P_SW_Bot)) $iaWest
    Draw-Face $g $heroImg ([System.Drawing.PointF[]]@($C, $P_NE_Top, $P_NW_Bot)) $iaHero
}

# ------------------------------------------------------------------- save ----
$outPath = Join-Path $OutDir ($Name + '.png')
$bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)

$g.Dispose(); $bmp.Dispose(); $heroImg.Dispose(); $plate.Dispose()
if ($bgImg -ne $null) { $bgImg.Dispose() }
$fi = Get-Item -LiteralPath $outPath
"OK  {0}  {1} bytes  ({2}x{3})  hero={4}  bg={5}" -f $fi.Name, $fi.Length, $W, $H, $Hero, $Bg
