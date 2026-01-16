package com.kk.core.service.impl;

import com.kk.core.service.BrowserService;
import lombok.extern.slf4j.Slf4j;

/**
 * 浏览器服务抽象基类
 */
@Slf4j
public abstract class AbstractBrowserService implements BrowserService {

    protected boolean active = false;

    @Override
    public boolean isActive() {
        return active;
    }

    protected void validateActive() {
        if (!active) {
            throw new IllegalStateException("浏览器未启动，请先调用 launchBrowser()");
        }
    }

    protected void logInfo(String message, Object... args) {
        log.info(message, args);
    }

    protected void logError(String message, Throwable e) {
        log.error(message, e);
    }

}
