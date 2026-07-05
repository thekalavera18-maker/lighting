$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$assets = Join-Path $root "assets"
$iconPath = Join-Path $assets "app.ico"
$splashPath = Join-Path $assets "splash.png"

if (-not (Test-Path $assets)) {
    New-Item -ItemType Directory -Path $assets | Out-Null
}

Add-Type -AssemblyName System.Drawing
if (-not (Test-Path $iconPath)) {
    $bitmap = New-Object System.Drawing.Bitmap 256, 256
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.Color]::Transparent)
    $gradientBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.Rectangle 0, 0, 256, 256),
        [System.Drawing.ColorTranslator]::FromHtml("#ff7b54"),
        [System.Drawing.ColorTranslator]::FromHtml("#4de3c1"),
        45
    )
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $radius = 52
    $diameter = $radius * 2
    $path.AddArc(18, 18, $diameter, $diameter, 180, 90)
    $path.AddArc(186 - $radius, 18, $diameter, $diameter, 270, 90)
    $path.AddArc(186 - $radius, 186 - $radius, $diameter, $diameter, 0, 90)
    $path.AddArc(18, 186 - $radius, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    $graphics.FillPath($gradientBrush, $path)
    $innerBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(220, 18, 21, 31))
    $graphics.FillEllipse($innerBrush, 62, 42, 132, 172)
    $pen = New-Object System.Drawing.Pen([System.Drawing.ColorTranslator]::FromHtml("#fff6e9"), 16)
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $graphics.DrawLine($pen, 128, 72, 128, 174)
    $graphics.DrawArc($pen, 70, 78, 116, 84, 0, 180)
    $icon = [System.Drawing.Icon]::FromHandle($bitmap.GetHicon())
    $stream = [System.IO.File]::Create($iconPath)
    $icon.Save($stream)
    $stream.Close()
    $path.Dispose()
    $gradientBrush.Dispose()
    $graphics.Dispose()
    $bitmap.Dispose()
}

if (Test-Path $splashPath) {
    Remove-Item $splashPath -Force
}

$bitmap = New-Object System.Drawing.Bitmap 220, 110
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.Clear([System.Drawing.ColorTranslator]::FromHtml("#0d1017"))

$panelBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Rectangle 0, 0, 220, 110),
    [System.Drawing.ColorTranslator]::FromHtml("#121926"),
    [System.Drawing.ColorTranslator]::FromHtml("#0a0d13"),
    25
)
$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$path.AddArc(8, 8, 18, 18, 180, 90)
$path.AddArc(194, 8, 18, 18, 270, 90)
$path.AddArc(194, 84, 18, 18, 0, 90)
$path.AddArc(8, 84, 18, 18, 90, 90)
$path.CloseFigure()
$graphics.FillPath($panelBrush, $path)

$iconBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Rectangle 16, 20, 42, 42),
    [System.Drawing.ColorTranslator]::FromHtml("#ff7b54"),
    [System.Drawing.ColorTranslator]::FromHtml("#4de3c1"),
    45
)
$iconPathShape = New-Object System.Drawing.Drawing2D.GraphicsPath
$iconPathShape.AddArc(16, 20, 18, 18, 180, 90)
$iconPathShape.AddArc(40, 20, 18, 18, 270, 90)
$iconPathShape.AddArc(40, 44, 18, 18, 0, 90)
$iconPathShape.AddArc(16, 44, 18, 18, 90, 90)
$iconPathShape.CloseFigure()
$graphics.FillPath($iconBrush, $iconPathShape)

$innerBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(220, 18, 21, 31))
$graphics.FillEllipse($innerBrush, 26, 26, 22, 32)

$pen = New-Object System.Drawing.Pen([System.Drawing.ColorTranslator]::FromHtml("#fff6e9"), 3)
$pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$graphics.DrawLine($pen, 37, 32, 37, 52)
$graphics.DrawArc($pen, 25, 31, 24, 18, 0, 180)

$titleFont = New-Object System.Drawing.Font("Segoe UI Semibold", 12)
$subtitleFont = New-Object System.Drawing.Font("Segoe UI", 6.5)
$titleBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#dde3ec"))
$subtitleBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#7f92a6"))
$graphics.DrawString("HappyLighting", $titleFont, $titleBrush, 72, 32)
$graphics.DrawString("Starting up...", $subtitleFont, $subtitleBrush, 73, 54)

$bitmap.Save($splashPath, [System.Drawing.Imaging.ImageFormat]::Png)

$titleFont.Dispose()
$subtitleFont.Dispose()
$titleBrush.Dispose()
$subtitleBrush.Dispose()
$pen.Dispose()
$innerBrush.Dispose()
$iconBrush.Dispose()
$iconPathShape.Dispose()
$path.Dispose()
$panelBrush.Dispose()
$graphics.Dispose()
$bitmap.Dispose()

py -3 -m pip install -U PyInstaller | Out-Host
py -3 -m happylighting.build
