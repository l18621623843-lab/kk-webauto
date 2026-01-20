package com.kk.playwright.service;


import com.kk.common.exception.KKException;
import com.kk.common.model.AutomationTask;
import com.kk.common.model.TaskResult;
import com.kk.common.model.TaskStep;
import com.kk.core.repository.TaskRepository;
import com.kk.core.service.AutomationExecutor;
import com.kk.core.service.BrowserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Service
public class PlaywrightAutomationExecutor implements AutomationExecutor {

    private final BrowserService browserService;
    private final TaskRepository taskRepository;
    private final Map<String, AutomationTask.TaskStatus> runningTasks = new ConcurrentHashMap<>();

    public PlaywrightAutomationExecutor(@Lazy BrowserService browserService, TaskRepository taskRepository) {
        this.browserService = browserService;
        this.taskRepository = taskRepository;
    }

    @Override
    public TaskResult executeTask(AutomationTask task, Consumer<String> logCallback) {
        LocalDateTime startTime = LocalDateTime.now();
        List<String> logs = new ArrayList<>();

        try {
            logCallback.accept("开始执行任务: " + task.getName());
            logs.add("开始执行任务: " + task.getName());

            task.setStatus(AutomationTask.TaskStatus.RUNNING);
            runningTasks.put(task.getId(), AutomationTask.TaskStatus.RUNNING);
            taskRepository.saveTask(task);

            // 启动浏览器
            browserService.launchBrowser(task.isHeadless());
            logCallback.accept("浏览器已启动");
            logs.add("浏览器已启动");

            // 执行步骤
            for (TaskStep step : task.getSteps()) {
                if (runningTasks.get(task.getId()) == AutomationTask.TaskStatus.STOPPED) {
                    throw new KKException("任务已被停止");
                }

                executeStep(step, logCallback);
                logs.add("完成步骤: " + step.getDescription());
                Thread.sleep(step.getDelay());
            }

            task.setStatus(AutomationTask.TaskStatus.SUCCESS);
            runningTasks.put(task.getId(), AutomationTask.TaskStatus.SUCCESS);

            logCallback.accept("任务执行完成!");
            logs.add("任务执行完成!");

            TaskResult result = TaskResult.builder()
                    .taskId(task.getId())
                    .taskName(task.getName())
                    .success(true)
                    .startTime(startTime)
                    .endTime(LocalDateTime.now())
                    .duration(java.time.Duration.between(startTime, LocalDateTime.now()).toMillis())
                    .logs(logs)
                    .build();

            taskRepository.saveResult(result);
            return result;

        } catch (Exception e) {
            log.error("任务执行失败", e);
            task.setStatus(AutomationTask.TaskStatus.FAILED);
            runningTasks.put(task.getId(), AutomationTask.TaskStatus.FAILED);

            String errorMsg = "错误: " + e.getMessage();
            logCallback.accept(errorMsg);
            logs.add(errorMsg);

            TaskResult result = TaskResult.builder()
                    .taskId(task.getId())
                    .taskName(task.getName())
                    .success(false)
                    .startTime(startTime)
                    .endTime(LocalDateTime.now())
                    .duration(java.time.Duration.between(startTime, LocalDateTime.now()).toMillis())
                    .logs(logs)
                    .errorMessage(e.getMessage())
                    .build();

            taskRepository.saveResult(result);
            return result;

        } finally {
            taskRepository.saveTask(task);
            runningTasks.remove(task.getId());
        }
    }

    private void executeStep(TaskStep step, Consumer<String> logCallback) {
        logCallback.accept("执行步骤: " + step.getDescription());

        switch (step.getType()) {
            case NAVIGATE -> browserService.navigateTo(step.getValue());
            case CLICK -> browserService.click(step.getSelector());
            case FILL -> browserService.fill(step.getSelector(), step.getValue());
            case SCREENSHOT -> browserService.screenshot(step.getValue());
            case WAIT -> browserService.waitForSelector(step.getSelector(),
                    Integer.parseInt(step.getValue()));
            case SCRIPT -> browserService.evaluateScript(step.getValue());
        }
    }

    @Override
    public void stopTask(String taskId) {
        runningTasks.put(taskId, AutomationTask.TaskStatus.STOPPED);
        browserService.closeBrowser();
        log.info("任务已停止: {}", taskId);
    }

    @Override
    public AutomationTask.TaskStatus getTaskStatus(String taskId) {
        return runningTasks.getOrDefault(taskId, AutomationTask.TaskStatus.PENDING);
    }
}