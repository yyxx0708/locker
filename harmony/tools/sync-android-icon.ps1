Add-Type -AssemblyName System.Drawing

$harmonyRoot = Split-Path $PSScriptRoot -Parent
$repoRoot = Split-Path $harmonyRoot -Parent
$topBlue = [System.Drawing.ColorTranslator]::FromHtml('#36A9E2')
$middleBlue = [System.Drawing.ColorTranslator]::FromHtml('#198ACD')
$bottomBlue = [System.Drawing.ColorTranslator]::FromHtml('#0764AF')
$white = [System.Drawing.ColorTranslator]::FromHtml('#F5F5F5')

function New-LockerIcon([int]$size, [string]$destination, [bool]$includeLock) {
    $bitmap = [System.Drawing.Bitmap]::new($size, $size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $gradient = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
        [System.Drawing.Point]::new(0, 0),
        [System.Drawing.Point]::new($size, $size),
        $topBlue,
        $bottomBlue
    )
    $blend = [System.Drawing.Drawing2D.ColorBlend]::new(3)
    $blend.Colors = [System.Drawing.Color[]]@($topBlue, $middleBlue, $bottomBlue)
    $blend.Positions = [single[]]@(0.0, 0.48, 1.0)
    $gradient.InterpolationColors = $blend
    $graphics.FillRectangle($gradient, 0, 0, $size, $size)

    if ($includeLock) {
    $scale = $size / 108.0
    $lockScale = 1.25
    $offset = $scale * 54 * (1 - $lockScale)
    $transform = [System.Drawing.Drawing2D.Matrix]::new(
        [single]($scale * $lockScale), 0, 0,
        [single]($scale * $lockScale),
        [single]$offset, [single]$offset
    )
    $graphics.Transform = $transform

    $stroke = [System.Drawing.Pen]::new($white, 7)
    $stroke.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $stroke.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $stroke.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $shackle = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $shackle.AddLine(42, 58, 42, 41)
    $shackle.AddArc(42, 29, 24, 24, 180, 180)
    $shackle.AddLine(66, 41, 66, 58)
    $graphics.DrawPath($stroke, $shackle)

    $body = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $body.AddLine(44, 52, 64, 52)
    $body.AddArc(54, 52, 20, 20, 270, 90)
    $body.AddLine(74, 62, 74, 72)
    $body.AddArc(54, 62, 20, 20, 0, 90)
    $body.AddLine(64, 82, 44, 82)
    $body.AddArc(34, 62, 20, 20, 90, 90)
    $body.AddLine(34, 72, 34, 62)
    $body.AddArc(34, 52, 20, 20, 180, 90)
    $body.CloseFigure()
    $whiteBrush = [System.Drawing.SolidBrush]::new($white)
    $keyCircle = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $keyCircle.AddEllipse(49, 58, 10, 10)
    $keyStem = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $keyStem.AddLine(51.5, 67, 56.5, 67)
    $keyStem.AddLine(56.5, 67, 56.5, 70)
    $keyStem.AddArc(51.5, 67.5, 5, 5, 0, 180)
    $keyStem.CloseFigure()
    $bodyRegion = [System.Drawing.Region]::new($body)
    $bodyRegion.Exclude($keyCircle)
    $bodyRegion.Exclude($keyStem)
    $graphics.FillRegion($whiteBrush, $bodyRegion)

    $stroke.Dispose()
    $shackle.Dispose()
    $body.Dispose()
    $bodyRegion.Dispose()
    $keyCircle.Dispose()
    $keyStem.Dispose()
    $whiteBrush.Dispose()
    $transform.Dispose()
    }

    $graphics.Dispose()
    $gradient.Dispose()
    $bitmap.Save($destination, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
}

$appMedia = Join-Path $harmonyRoot 'AppScope/resources/base/media'
$entryMedia = Join-Path $harmonyRoot 'entry/src/main/resources/base/media'
$androidMedia = Join-Path $repoRoot 'app/src/main/res/drawable-nodpi'
New-Item -ItemType Directory -Path $androidMedia -Force | Out-Null
New-LockerIcon 1024 (Join-Path $androidMedia 'ic_launcher_background.png') $false
foreach ($media in @($appMedia, $entryMedia)) {
    New-LockerIcon 1024 (Join-Path $media 'background.png') $true
    $transparent = [System.Drawing.Bitmap]::new(1024, 1024)
    $transparent.Save((Join-Path $media 'foreground.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $transparent.Dispose()
}
New-LockerIcon 256 (Join-Path $entryMedia 'startIcon.png') $true
