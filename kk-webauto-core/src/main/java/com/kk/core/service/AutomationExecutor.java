package com.kk.core.service;

import com.kk.common.model.AutomationTask;
import com.kk.common.model.TaskResult;
import java.util.function.Consumer;

/**
 * 自动化任务执行器接口
 */
public interface AutomationExecutor {

    /**
     * 执行任务
     */
    TaskResult executeTask(AutomationTask task, Consumer<String> logCallback);

    /**
     * 停止任务
     */
    void stopTask(String taskId);

    /**
     * 获取任务状态
     */
    AutomationTask.TaskStatus getTaskStatus(String taskId);
}