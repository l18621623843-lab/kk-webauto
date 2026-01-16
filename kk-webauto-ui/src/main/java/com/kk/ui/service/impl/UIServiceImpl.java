package com.kk.ui.service.impl;

import com.kk.common.model.AutomationTask;
import com.kk.common.model.TaskResult;
import com.kk.common.model.TaskStep;
import com.kk.core.repository.TaskRepository;
import com.kk.core.service.AutomationExecutor;
import com.kk.ui.service.UIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class UIServiceImpl implements UIService {

    private final AutomationExecutor automationExecutor;
    private final TaskRepository taskRepository;

    @Override
    public void executeTask(AutomationTask task) {
        CompletableFuture.runAsync(() -> {
            try {
                automationExecutor.executeTask(task, message ->
                        log.info("Task log: {}", message)
                );
            } catch (Exception e) {
                log.error("任务执行失败", e);
            }
        });
    }

    @Override
    public void stopTask(String taskId) {
        automationExecutor.stopTask(taskId);
    }

    @Override
    public List<AutomationTask> getAllTasks() {
        return taskRepository.findAll();
    }

    @Override
    public TaskResult getTaskResult(String taskId) {
        return taskRepository.findResultByTaskId(taskId).orElse(null);
    }

    @Override
    public void saveTask(AutomationTask task) {
        taskRepository.saveTask(task);
    }

    @Override
    public void deleteTask(String taskId) {
        taskRepository.deleteTask(taskId);
    }

    @Override
    public AutomationTask loadDemoTask() {
        AutomationTask task = new AutomationTask("百度搜索示例");
        task.setHeadless(false);
        task.addStep(TaskStep.navigate("https://www.baidu.com"));
        task.addStep(TaskStep.fill("#kw", "Playwright自动化测试", "在搜索框输入关键词"));
        task.addStep(TaskStep.click("#su", "点击搜索按钮"));
        task.addStep(TaskStep.screenshot("./screenshots/baidu-result.png"));
        return task;
    }
}