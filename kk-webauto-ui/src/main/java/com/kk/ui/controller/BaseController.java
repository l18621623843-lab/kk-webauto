package com.kk.ui.controller;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 控制器基类 - 提供通用功能
 */
@Slf4j
public abstract class BaseController {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 在日志区域添加日志
     */
    protected void appendLog(TextArea logArea, String message) {
        if (logArea == null) return;

        Platform.runLater(() -> {
            String timestamp = LocalTime.now().format(TIME_FORMATTER);
            logArea.appendText(String.format("[%s] %s%n", timestamp, message));
        });
    }

    /**
     * 显示信息对话框
     */
    protected void showInfo(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    /**
     * 显示错误对话框
     */
    protected void showError(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    /**
     * 显示警告对话框
     */
    protected void showWarning(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    /**
     * 在UI线程执行
     */
    protected void runOnUIThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}