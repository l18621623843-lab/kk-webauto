package com.kk.common.model;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class TaskStep {
    private StepType type;
    private String selector;
    private String value;
    private String description;
    private long delay = 500;

    public enum StepType {
        NAVIGATE, CLICK, FILL, SCREENSHOT, WAIT, SCRIPT, SCROLL, HOVER
    }

    public static TaskStep navigate(String url) {
        return new TaskStep()
                .setType(StepType.NAVIGATE)
                .setValue(url)
                .setDescription("导航到: " + url);
    }

    public static TaskStep click(String selector, String desc) {
        return new TaskStep()
                .setType(StepType.CLICK)
                .setSelector(selector)
                .setDescription(desc);
    }

    public static TaskStep fill(String selector, String value, String desc) {
        return new TaskStep()
                .setType(StepType.FILL)
                .setSelector(selector)
                .setValue(value)
                .setDescription(desc);
    }

    public static TaskStep screenshot(String path) {
        return new TaskStep()
                .setType(StepType.SCREENSHOT)
                .setValue(path)
                .setDescription("截图: " + path);
    }

    public static TaskStep waitFor(String selector, int timeout) {
        return new TaskStep()
                .setType(StepType.WAIT)
                .setSelector(selector)
                .setValue(String.valueOf(timeout))
                .setDescription("等待元素: " + selector);
    }
}