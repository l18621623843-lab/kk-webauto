package com.kk.core.repository;

import com.kk.common.model.AutomationTask;
import com.kk.common.model.TaskResult;
import java.util.List;
import java.util.Optional;

/**
 * 任务仓储接口
 */
public interface TaskRepository {

    /**
     * 保存任务
     */
    void saveTask(AutomationTask task);

    /**
     * 查找任务
     */
    Optional<AutomationTask> findById(String taskId);

    /**
     * 查找所有任务
     */
    List<AutomationTask> findAll();

    /**
     * 删除任务
     */
    void deleteTask(String taskId);

    /**
     * 保存任务结果
     */
    void saveResult(TaskResult result);

    /**
     * 获取任务结果
     */
    Optional<TaskResult> findResultByTaskId(String taskId);
}