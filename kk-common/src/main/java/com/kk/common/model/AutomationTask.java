package com.kk.common.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class AutomationTask {

    private String id;
    private String name;
    private String description;
    private boolean headless;
    private List<TaskStep> steps = new ArrayList<>();
    private TaskStatus status = TaskStatus.PENDING;

    public enum TaskStatus {
        PENDING, RUNNING, SUCCESS, FAILED, STOPPED
    }

    public AutomationTask(String name) {
        this.id = java.util.UUID.randomUUID().toString();
        this.name = name;
    }

    public AutomationTask addStep(TaskStep step) {
        this.steps.add(step);
        return this;
    }

}
