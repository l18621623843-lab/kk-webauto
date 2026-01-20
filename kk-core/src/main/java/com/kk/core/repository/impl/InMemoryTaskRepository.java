package com.kk.core.repository.impl;

import com.kk.common.model.AutomationTask;
import com.kk.common.model.TaskResult;
import com.kk.core.repository.TaskRepository;
import org.springframework.stereotype.Repository;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryTaskRepository implements TaskRepository {

    private final Map<String, AutomationTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, TaskResult> results = new ConcurrentHashMap<>();

    @Override
    public void saveTask(AutomationTask task) {
        tasks.put(task.getId(), task);
    }

    @Override
    public Optional<AutomationTask> findById(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public List<AutomationTask> findAll() {
        return new ArrayList<>(tasks.values());
    }

    @Override
    public void deleteTask(String taskId) {
        tasks.remove(taskId);
        results.remove(taskId);
    }

    @Override
    public void saveResult(TaskResult result) {
        results.put(result.getTaskId(), result);
    }

    @Override
    public Optional<TaskResult> findResultByTaskId(String taskId) {
        return Optional.ofNullable(results.get(taskId));
    }
}