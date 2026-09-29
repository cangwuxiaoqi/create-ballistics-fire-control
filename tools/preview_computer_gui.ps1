# ===========================================================================
#  Offline preview of the Fire Control Computer's TEXT LAYOUT.
#
#  Why: the panel's line positions are fixed anchors in
#  FireControlComputerScreen (Y_TITLE / Y_NET / Y_UNREACH_* / Y_COORD_LIST /
#  Y_TARGET / Y_STAGE), and tweaking them in-game costs a restart per try.
#  This draws the layout on top of the real panel texture so it can be
#  eyeballed (and rearranged) without launching Minecraft.
#
#  Output: tools/preview/computer_gui_layout.png
#
#  The ROWS come from tools/preview/computer_gui_lines.tsv (UTF-8) -- keep the
#  Chinese text there, NOT here: PowerShell 5.1 reads .ps1 as the ANSI codepage,
#  so a non-ASCII .ps1 would be mangled. This file stays ASCII-only on purpose.
#
#  Row kinds (tab separated):
#    T   y  x      colorhex  text          -> plain text at (x, y)
#    LV  y  x      colorhex  label  value  -> muted label + coloured value after it
#    B   y  x  w   text                    -> button label, centred in [x, x+w)
#
#  y / x / w are PANEL pixels (the same numbers the Screen code uses).
#  Body text is drawn at MC's TEXT_SCALE = 1.15; button labels are unscaled.
#  NOTE: glyph widths differ from Minecraft's own font, so the horizontal
#  positions are approximate -- the VERTICAL anchors are exact.
# ===========================================================================

Add-Type -AssemblyName System.Drawing

$root   = $PSScriptRoot
$src    = Join-Path $root '..\src\main\resources\assets\create_ballistics\textures\gui\panel\computer.png'
$data   = Join-Path $root 'preview\computer_gui_lines.tsv'
$outDir = Join-Path $root 'preview'
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
$out    = Join-Path $outDir 'computer_gui_layout.png'

$S = 3                                  # magnification (readability)

$panel  = New-Object System.Drawing.Bitmap([System.IO.Path]::GetFullPath($src))
$canvas = New-Object System.Drawing.Bitmap (560 * $S), (300 * $S)
$g = [System.Drawing.Graphics]::FromImage($canvas)
$g.InterpolationMode   = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.PixelOffsetMode     = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g.TextRenderingHint   = [System.Drawing.Text.TextRenderingHint]::SingleBitPerPixelGridFit
$g.DrawImage($panel, 0, 0, 560 * $S, 300 * $S)
$panel.Dispose()

$bodyFont = [System.Drawing.Font]::new('Microsoft YaHei', [float](9.0 * 1.15 * $S), [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$btnFont  = [System.Drawing.Font]::new('Microsoft YaHei', [float](9.0 * $S), [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)

function New-Brush([string]$hex) {
    return [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#' + $hex))
}
$muted = New-Brush 'A8A8B4'
$label = New-Brush 'E6E6E6'

foreach ($line in (Get-Content -LiteralPath $data -Encoding UTF8)) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $f = $line -split "`t"
    $y = [float]([int]$f[1] * $S)
    switch ($f[0]) {
        'T' {
            $g.DrawString($f[4], $bodyFont, (New-Brush $f[3]), [float]([int]$f[2] * $S), $y)
        }
        'LV' {
            # columns: LV  y  x  color  label  value
            $x = [float]([int]$f[2] * $S)
            $g.DrawString($f[4], $bodyFont, $muted, $x, $y)
            $w = $g.MeasureString($f[4], $bodyFont).Width
            $g.DrawString($f[5], $bodyFont, (New-Brush $f[3]), $x + $w, $y)
        }
        'B' {
            $x = [float]([int]$f[2] * $S)
            $w = [float]([int]$f[3] * $S)
            $tw = $g.MeasureString($f[4], $btnFont).Width
            $g.DrawString($f[4], $btnFont, $label, $x + ($w - $tw) / 2.0, $y)
        }
    }
}

$canvas.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
Write-Output ('wrote ' + $out + '  (' + $canvas.Width + 'x' + $canvas.Height + ')')
$canvas.Dispose()
$g.Dispose()
