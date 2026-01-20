package com.kk.playwright.service;

import com.kk.core.service.impl.AbstractBrowserService;
import com.microsoft.playwright.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.nio.file.Paths;

@Service
@Lazy
public class PlaywrightBrowserService extends AbstractBrowserService {

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    private void ensurePlaywrightInitialized() {
        if (playwright == null) {
            logInfo("延迟初始化 Playwright 核心...");
            playwright = Playwright.create();
        }
    }

    @Override
    public void launchBrowser(boolean headless) {
        // 确保 Playwright 基础环境已就绪
        ensurePlaywrightInitialized();

        if (browser != null && browser.isConnected()) {
            logInfo("浏览器已启动");
            return;
        }

        logInfo("启动浏览器 (headless: {})", headless);
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setSlowMo(50));

        context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1920, 1080));

        page = context.newPage();
        active = true;
    }

    @Override
    public void navigateTo(String url) {
        validateActive();
        logInfo("导航到: {}", url);
        page.navigate(url);
    }

    @Override
    public void click(String selector) {
        validateActive();
        logInfo("点击元素: {}", selector);
        page.click(selector);
    }

    @Override
    public void fill(String selector, String value) {
        validateActive();
        logInfo("填充 {} 为: {}", selector, value);
        page.fill(selector, value);
    }

    @Override
    public String getText(String selector) {
        validateActive();
        return page.textContent(selector);
    }

    @Override
    public void screenshot(String path) {
        validateActive();
        logInfo("截图保存到: {}", path);
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get(path))
                .setFullPage(true));
    }

    @Override
    public Object evaluateScript(String script) {
        validateActive();
        return page.evaluate(script);
    }

    @Override
    public void waitForSelector(String selector, int timeout) {
        validateActive();
        page.waitForSelector(selector, new Page.WaitForSelectorOptions()
                .setTimeout(timeout));
    }

    @Override
    public void closeBrowser() {
        if (page != null) {
            page.close();
            page = null;
        }
        if (context != null) {
            context.close();
            context = null;
        }
        if (browser != null) {
            browser.close();
            browser = null;
        }
        active = false;
        logInfo("浏览器已关闭");
    }

    public Page getPage() {
        return page;
    }

    @PreDestroy
    public void cleanup() {
        closeBrowser();
        if (playwright != null) {
            playwright.close();
            logInfo("Playwright 已关闭");
        }
    }
}