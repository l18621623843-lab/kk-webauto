#!/usr/bin/env pwsh
# Playwright 浏览器安装脚本 - PowerShell 版本

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Playwright 浏览器安装工具" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 检查是否在项目根目录
if (-Not (Test-Path "pom.xml")) {
Write-Host "错误: 请在项目根目录 (kk-webauto) 下运行此脚本！" -ForegroundColor Red
exit 1
}

# 步骤1: 构建项目
Write-Host "步骤 1/3: 构建项目..." -ForegroundColor Yellow
Write-Host "执行: mvn clean install -DskipTests" -ForegroundColor Gray
mvn clean install -DskipTests

if ($LASTEXITCODE -ne 0) {
Write-Host "项目构建失败，请检查错误信息" -ForegroundColor Red
exit 1
}

Write-Host "✓ 项目构建成功" -ForegroundColor Green
Write-Host ""

# 步骤2: 设置镜像源
Write-Host "步骤 2/3: 配置下载源..." -ForegroundColor Yellow

$choice = Read-Host "选择下载源: [1] 官方源(较慢) [2] 国内镜像(推荐) (输入 1 或 2)"

if ($choice -eq "2") {
$env:PLAYWRIGHT_DOWNLOAD_HOST = "https://npmmirror.com/mirrors/playwright"
Write-Host "✓ 已设置国内镜像源" -ForegroundColor Green
} else {
Write-Host "✓ 使用官方源" -ForegroundColor Green
}
Write-Host ""

# 步骤3: 安装浏览器
Write-Host "步骤 3/3: 安装 Chromium 浏览器..." -ForegroundColor Yellow
Write-Host "这可能需要几分钟，请耐心等待..." -ForegroundColor Gray
Write-Host ""

Set-Location "kk-webauto-playwright"

# 创建临时安装器
$installerCode = @"
package temp;
import com.microsoft.playwright.CLI;
public class Install {
public static void main(String[] args) {
CLI.main(new String[]{"install", "chromium"});
}
}
"@

# 创建临时目录
$tempDir = "src/main/java/temp"
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
Set-Content -Path "$tempDir/Install.java" -Value $installerCode

# 编译并运行
Write-Host "编译安装器..." -ForegroundColor Gray
mvn compile -q

Write-Host "开始下载浏览器..." -ForegroundColor Gray
mvn exec:java -Dexec.mainClass="temp.Install" -q

# 清理临时文件
Remove-Item -Recurse -Force $tempDir

Set-Location ..

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "✓ 安装完成！" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "浏览器安装位置: $env:USERPROFILE\AppData\Local\ms-playwright" -ForegroundColor Gray
Write-Host ""
Write-Host "现在可以运行应用了:" -ForegroundColor Yellow
Write-Host "  cd kk-webauto-app" -ForegroundColor White
Write-Host "  mvn spring-boot:run" -ForegroundColor White
Write-Host ""