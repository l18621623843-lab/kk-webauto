package com.kk.ui.service;

import com.kk.common.model.AutomationTask;
import com.kk.common.model.TaskResult;
import java.util.List;

/**
 * UI服务接口 - 定义UI层与业务层的交互契约
 */
public interface UIService {

    /**
     * 执行任务
     */
    void executeTask(AutomationTask task);

    /**
     * 停止任务
     */
    void stopTask(String taskId);

    /**
     * 获取所有任务
     */
    List<AutomationTask> getAllTasks();

    /**
     * 获取任务结果
     */
    TaskResult getTaskResult(String taskId);

    /**
     * 保存任务
     */
    void saveTask(AutomationTask task);

    /**
     * 删除任务
     */
    void deleteTask(String taskId);

    /**
     * 加载示例任务
     */
    AutomationTask loadDemoTask();
}