package com.kk.common.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class TaskResult {
    private String taskId;
    private String taskName;
    private boolean success;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private long duration;
    @Builder.Default
    private List<String> logs = new ArrayList<>();
    private String errorMessage;
    private Object data;
}