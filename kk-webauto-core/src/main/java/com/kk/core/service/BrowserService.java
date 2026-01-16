package com.kk.core.service;

/**
 * 浏览器服务接口
 */
public interface  BrowserService {
    /**
     * 启动浏览器
     */
    void launchBrowser(boolean headless);

    /**
     * 导航到URL
     */
    void navigateTo(String url);

    /**
     * 点击元素
     */
    void click(String selector);

    /**
     * 填充表单
     */
    void fill(String selector, String value);

    /**
     * 获取文本
     */
    String getText(String selector);

    /**
     * 截图
     */
    void screenshot(String path);

    /**
     * 执行JavaScript
     */
    Object evaluateScript(String script);

    /**
     * 等待元素
     */
    void waitForSelector(String selector, int timeout);

    /**
     * 关闭浏览器
     */
    void closeBrowser();

    /**
     * 检查是否已启动
     */
    boolean isActive();
}
