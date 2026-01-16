package com.kk.ui.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kk.common.model.AutomationTask;
import com.kk.common.model.TaskStep;
import com.kk.ui.service.UIService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import netscape.javascript.JSObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MainController extends BaseController {

    private final UIService uiService;

    // 任务配置区域
    @FXML private TextField urlTextField;
    @FXML private CheckBox headlessCheckBox;

    // 步骤编辑区域
    @FXML private ComboBox<String> actionComboBox;
    @FXML private TextField selectorTextField;
    @FXML private TextArea valueTextArea;
    @FXML private Button addStepButton;
    @FXML private Button cancelEditButton;

    // 可视化流程编辑器（JavaFX WebView）
    @FXML private ToggleButton visualEditorToggle;
    @FXML private StackPane editorStackPane;
    @FXML private SplitPane listEditorSplitPane;
    @FXML private BorderPane visualEditorPane;
    @FXML private WebView flowWebView;

    private WebEngine flowWebEngine;
    private volatile boolean flowEditorReady = false;
    private String flowEditorUrl;

    private static final Gson GSON = new Gson();

    private String addStepButtonDefaultText;

    private boolean editingMode = false;
    private int editingIndex = -1;
    private boolean selectionChangedByClick = false;

    // 步骤列表
    @FXML private ListView<String> stepListView;

    private final ObservableList<TaskStep> taskSteps = FXCollections.observableArrayList();

    // 任务历史表格
    @FXML private TableView<AutomationTask> taskHistoryTable;
    @FXML private TableColumn<AutomationTask, String> nameColumn;
    @FXML private TableColumn<AutomationTask, String> statusColumn;
    @FXML private TableColumn<AutomationTask, Integer> stepsColumn;

    // 日志区域
    @FXML private TextArea logTextArea;

    @FXML
    public void initialize() {
        initializeComponents();
        initFlowEditor();
        loadTaskHistory();
        appendLog(logTextArea, "应用启动成功");
    }

    private void initializeComponents() {
        // 初始化操作类型下拉框
        actionComboBox.setItems(FXCollections.observableArrayList(
                "导航", "点击", "填充", "截图", "等待", "执行脚本"
        ));
        actionComboBox.getSelectionModel().selectFirst();

        // 初始化“添加/保存修改”按钮默认文案
        if (addStepButton != null) {
            addStepButtonDefaultText = addStepButton.getText();
        }

        // 点击步骤行后，自动把内容回填到左侧编辑区，用于修改
        stepListView.getSelectionModel().selectedIndexProperty().addListener((obs, oldVal, newVal) -> {
            int idx = newVal == null ? -1 : newVal.intValue();
            if (idx < 0 || idx >= taskSteps.size()) {
                editingIndex = -1;
                setEditMode(false);
                clearStepEditor();
                return;
            }

            selectionChangedByClick = true;
            editingIndex = idx;

            TaskStep step = taskSteps.get(idx);
            actionComboBox.getSelectionModel().select(toActionLabel(step.getType()));
            selectorTextField.setText(step.getSelector() == null ? "" : step.getSelector());
            valueTextArea.setText(step.getValue() == null ? "" : step.getValue());
            setEditMode(true);
        });

        // 初始化任务历史表格
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        statusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        stepsColumn.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleIntegerProperty(
                        cellData.getValue().getSteps().size()
                ).asObject()
        );

        // 设置默认值
        urlTextField.setText("https://www.baidu.com");
        setEditMode(false);
        clearStepEditor();
    }

    @FXML
    private void handleAddStep() {
        String action = actionComboBox.getValue();
        String selector = selectorTextField.getText();
        String value = valueTextArea.getText();

        TaskStep step = createTaskStep(action, selector, value);
        if (step == null) {
            return;
        }

        int selectedIndex = stepListView.getSelectionModel().getSelectedIndex();
        if (editingMode && selectedIndex >= 0 && selectedIndex < taskSteps.size() && selectedIndex == editingIndex) {
            // 编辑模式：更新所选步骤
            taskSteps.set(selectedIndex, step);
            stepListView.getItems().set(selectedIndex, formatStep(step));
            appendLog(logTextArea, "✓ 已更新步骤: " + formatStep(step));

            handleCancelEdit();
        } else {
            // 新增模式：添加步骤
            taskSteps.add(step);
            stepListView.getItems().add(formatStep(step));
            appendLog(logTextArea, "✓ 已添加步骤: " + formatStep(step));

            editingIndex = -1;
            setEditMode(false);
            clearStepEditor();
        }

        syncStepsToGraph();
    }

    @FXML
    private void handleCancelEdit() {
        stepListView.getSelectionModel().clearSelection();
        editingIndex = -1;
        setEditMode(false);
        clearStepEditor();
    }

    @FXML
    private void handleStepListClick() {
        if (selectionChangedByClick) {
            selectionChangedByClick = false;
            return;
        }

        int idx = stepListView.getSelectionModel().getSelectedIndex();
        if (editingMode && idx >= 0 && idx == editingIndex) {
            handleCancelEdit();
        }
    }

    private void clearStepEditor() {
        actionComboBox.getSelectionModel().selectFirst();
        selectorTextField.clear();
        valueTextArea.clear();
    }

    @FXML
    private void handleRemoveStep() {
        int selectedIndex = stepListView.getSelectionModel().getSelectedIndex();
        if (selectedIndex >= 0) {
            stepListView.getItems().remove(selectedIndex);
            taskSteps.remove(selectedIndex);
            handleCancelEdit();
            appendLog(logTextArea, "✓ 已删除步骤");
            syncStepsToGraph();
        } else {
            showWarning("提示", "请先选择要删除的步骤");
        }
    }

    @FXML
    private void handleMoveUp() {
        int selectedIndex = stepListView.getSelectionModel().getSelectedIndex();
        if (selectedIndex > 0) {
            String item = stepListView.getItems().remove(selectedIndex);
            stepListView.getItems().add(selectedIndex - 1, item);

            TaskStep step = taskSteps.remove(selectedIndex);
            taskSteps.add(selectedIndex - 1, step);

            stepListView.getSelectionModel().select(selectedIndex - 1);
            syncStepsToGraph();
        }
    }

    @FXML
    private void handleMoveDown() {
        int selectedIndex = stepListView.getSelectionModel().getSelectedIndex();
        if (selectedIndex >= 0 && selectedIndex < stepListView.getItems().size() - 1) {
            String item = stepListView.getItems().remove(selectedIndex);
            stepListView.getItems().add(selectedIndex + 1, item);

            TaskStep step = taskSteps.remove(selectedIndex);
            taskSteps.add(selectedIndex + 1, step);

            stepListView.getSelectionModel().select(selectedIndex + 1);
            syncStepsToGraph();
        }
    }

    @FXML
    private void handleExecuteTask() {
        appendLog(logTextArea, "准备执行任务...");

        AutomationTask task = new AutomationTask("用户自定义任务-" + System.currentTimeMillis());
        task.setHeadless(headlessCheckBox.isSelected());
        task.addStep(TaskStep.navigate(urlTextField.getText()));

        // 添加所有步骤
        taskSteps.forEach(task::addStep);

        // 执行任务
        uiService.executeTask(task);
        appendLog(logTextArea, "✓ 任务已提交执行");

        // 刷新任务历史
        loadTaskHistory();
    }

    @FXML
    private void handleStopTask() {
        appendLog(logTextArea, "任务已停止");
        showInfo("提示", "任务停止功能开发中");
    }

    @FXML
    private void handleLoadDemo() {
        AutomationTask demoTask = uiService.loadDemoTask();

        // 清空并加载示例
        stepListView.getItems().clear();
        taskSteps.clear();
        handleCancelEdit();

        urlTextField.setText("https://www.baidu.com");
        headlessCheckBox.setSelected(false);

        demoTask.getSteps().stream()
                .skip(1) // 跳过导航步骤
                .forEach(step -> {
                    taskSteps.add(step);
                    stepListView.getItems().add(formatStep(step));
                });

        appendLog(logTextArea, "✓ 已加载示例任务");
        syncStepsToGraph();
    }

    @FXML
    private void handleClearSteps() {
        stepListView.getItems().clear();
        taskSteps.clear();
        handleCancelEdit();
        appendLog(logTextArea, "✓ 已清空步骤列表");
        syncStepsToGraph();
    }

    @FXML
    private void handleSaveTask() {
        appendLog(logTextArea, "保存任务功能开发中...");
        showInfo("提示", "保存任务功能开发中");
    }

    @FXML
    private void handleLoadTask() {
        appendLog(logTextArea, "加载任务功能开发中...");
        showInfo("提示", "加载任务功能开发中");
    }

    @FXML
    private void handleAbout() {
        showInfo("关于",
                "网页自动化工具 v1.0\n" +
                        "基于 JavaFX + Spring + Playwright\n" +
                        "模块化架构设计");
    }

    @FXML
    private void handleExit() {
        System.exit(0);
    }

    private void loadTaskHistory() {
        List<AutomationTask> tasks = uiService.getAllTasks();
        taskHistoryTable.setItems(FXCollections.observableArrayList(tasks));
    }

    private TaskStep createTaskStep(String action, String selector, String value) {
        try {
            return switch (action) {
                case "导航" -> TaskStep.navigate((value == null || value.isBlank()) ? urlTextField.getText() : value);
                case "点击" -> TaskStep.click(selector, "点击: " + selector);
                case "填充" -> TaskStep.fill(selector, value, "填充: " + selector);
                case "截图" -> TaskStep.screenshot(value);
                case "等待" -> TaskStep.waitFor(selector,
                        (value == null || value.isBlank()) ? 5000 : Integer.parseInt(value));
                case "执行脚本" -> new TaskStep()
                        .setType(TaskStep.StepType.SCRIPT)
                        .setValue(value)
                        .setDescription("执行脚本");
                default -> null;
            };
        } catch (NumberFormatException e) {
            showWarning("参数错误", "等待步骤的超时时间必须是数字（毫秒）");
            return null;
        }
    }

    private void setEditMode(boolean editing) {
        editingMode = editing;

        if (addStepButton != null) {
            if (addStepButtonDefaultText == null) {
                addStepButtonDefaultText = addStepButton.getText();
            }
            addStepButton.setText(editing ? "💾 保存修改" : addStepButtonDefaultText);
        }

        if (cancelEditButton != null) {
            cancelEditButton.setDisable(!editing);
            cancelEditButton.setVisible(editing);
            cancelEditButton.setManaged(editing);
        }

        if (!editing) {
            editingIndex = -1;
        }
    }

    private String toActionLabel(TaskStep.StepType type) {
        if (type == null) return "";
        return switch (type) {
            case NAVIGATE -> "导航";
            case CLICK -> "点击";
            case FILL -> "填充";
            case SCREENSHOT -> "截图";
            case WAIT -> "等待";
            case SCRIPT -> "执行脚本";
            default -> type.name();
        };
    }

    private String formatStep(TaskStep step) {
        if (step == null) return "";

        String action = toActionLabel(step.getType());
        String selector = step.getSelector();
        String value = step.getValue();
        String desc = step.getDescription();

        StringBuilder sb = new StringBuilder();
        sb.append("[").append(action).append("]");

        if (selector != null && !selector.isBlank()) {
            sb.append(" 选择器:").append(selector);
        }
        if (value != null && !value.isBlank()) {
            sb.append(" 值:").append(value);
        }
        if (desc != null && !desc.isBlank()) {
            sb.append(" | ").append(desc);
        }

        return sb.toString().trim();
    }

    @FXML
    private void handleToggleVisualEditor() {
        boolean toVisual = visualEditorToggle != null && visualEditorToggle.isSelected();
        setVisualEditorVisible(toVisual);
        if (visualEditorToggle != null) {
            visualEditorToggle.setText(toVisual ? "返回列表编辑" : "可视化编排");
        }
        if (toVisual) {
            syncStepsToGraph();
        }
    }

    @FXML
    private void handleSyncStepsToGraph() {
        syncStepsToGraph();
    }

    private void setVisualEditorVisible(boolean visual) {
        if (visualEditorPane != null) {
            visualEditorPane.setVisible(visual);
            visualEditorPane.setManaged(visual);
        }
        if (listEditorSplitPane != null) {
            listEditorSplitPane.setVisible(!visual);
            listEditorSplitPane.setManaged(!visual);
        }
    }

    private void initFlowEditor() {
        if (flowWebView == null) return;

        try {
            flowEditorUrl = prepareFlowEditorUrl();
        } catch (Exception e) {
            appendLog(logTextArea, "✗ 初始化 Flow Editor 资源失败: " + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
            return;
        }

        flowWebEngine = flowWebView.getEngine();
        flowWebEngine.setJavaScriptEnabled(true);

        flowWebEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                flowEditorReady = true;
                installWebViewBridge();
                Platform.runLater(this::syncStepsToGraph);
            }
            if (newState == Worker.State.FAILED) {
                Throwable ex = flowWebEngine.getLoadWorker().getException();
                appendLog(logTextArea, "✗ Flow Editor 加载失败: " + (ex == null ? "unknown" : (ex.getMessage() == null ? String.valueOf(ex) : ex.getMessage())));
            }
        });

        flowWebEngine.load(flowEditorUrl);
    }

    private void installWebViewBridge() {
        if (flowWebEngine == null) return;

        try {
            JSObject window = (JSObject) flowWebEngine.executeScript("window");
            window.setMember("kkBridge", new FlowEditorBridge());

            String js = "(function(){\n" +
                    "  try {\n" +
                    "    if (window.__kkWebViewBridgeInstalled) return;\n" +
                    "    window.__kkWebViewBridgeInstalled = true;\n" +
                    "    window.onTaskStepsChanged = function(stepsJson, reason){\n" +
                    "      try {\n" +
                    "        if (!window.kkBridge || !window.kkBridge.stepsChanged) return;\n" +
                    "        window.kkBridge.stepsChanged(String(stepsJson||'[]'), String(reason||''));\n" +
                    "      } catch(e) {}\n" +
                    "    };\n" +
                    "  } catch(e) {}\n" +
                    "})();";

            flowWebEngine.executeScript(js);
        } catch (Exception e) {
            appendLog(logTextArea, "✗ 安装 WebView Bridge 失败: " + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
        }
    }

    public class FlowEditorBridge {
        public void stepsChanged(String stepsJson, String reason) {
            try {
                if (stepsJson == null) stepsJson = "[]";
                if (reason == null) reason = "";

                // 我们主动 setTaskSteps 时，页面会触发一次回调；忽略避免循环
                if ("setTaskSteps".equals(reason) || "boot".equals(reason)) {
                    return;
                }

                String finalStepsJson = stepsJson;
                Platform.runLater(() -> applyStepsFromVisual(finalStepsJson));
            } catch (Exception e) {
                Platform.runLater(() -> appendLog(logTextArea, "✗ Bridge 回调失败: " + (e.getMessage() == null ? String.valueOf(e) : e.getMessage())));
            }
        }
    }

    private void syncStepsToGraph() {
        if (flowWebEngine == null || !flowEditorReady) return;

        String json = buildStepsJsonForGraph();
        try {
            flowWebEngine.executeScript("window.setTaskSteps && window.setTaskSteps(" + json + ")");
        } catch (Exception e) {
            appendLog(logTextArea, "✗ 同步到画布失败: " + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
        }
    }

    private String buildStepsJsonForGraph() {
        JsonArray arr = new JsonArray();

        // 头部导航步骤（从任务配置读取）
        String url = urlTextField == null ? "" : urlTextField.getText();
        JsonObject nav = new JsonObject();
        nav.addProperty("id", "s0");
        nav.addProperty("type", "nav");
        nav.addProperty("action", "导航");
        nav.addProperty("label", "打开页面");
        nav.addProperty("fieldLabel", "URL");
        nav.addProperty("value", url == null ? "" : url);
        arr.add(nav);

        for (int i = 0; i < taskSteps.size(); i++) {
            TaskStep step = taskSteps.get(i);
            String id = "s" + (i + 1);
            String action = toActionLabel(step.getType());

            String type = stepTypeToFlowType(step.getType());
            String fieldLabel = flowFieldLabel(type);

            // 当前 flow-editor.html 仅提供一个输入框：这里优先展示 selector，其次 value
            String fieldValue = (step.getSelector() != null && !step.getSelector().isBlank()) ? step.getSelector() : step.getValue();
            if (fieldValue == null) fieldValue = "";

            JsonObject obj = new JsonObject();
            obj.addProperty("id", id);
            obj.addProperty("type", type);
            obj.addProperty("action", action);
            obj.addProperty("label", step.getDescription() == null ? formatStep(step) : step.getDescription());
            obj.addProperty("fieldLabel", fieldLabel);
            obj.addProperty("value", fieldValue);
            arr.add(obj);
        }

        return GSON.toJson(arr);
    }

    private static String stepTypeToFlowType(TaskStep.StepType type) {
        if (type == null) return "click";
        return switch (type) {
            case NAVIGATE -> "nav";
            case CLICK -> "click";
            case FILL -> "fill";
            case SCREENSHOT -> "shot";
            case WAIT -> "wait";
            case SCRIPT -> "script";
            default -> "click";
        };
    }

    private static String flowFieldLabel(String flowType) {
        if (flowType == null) return "参数";
        return switch (flowType) {
            case "nav" -> "URL";
            case "click" -> "选择器";
            case "fill" -> "输入";
            case "wait" -> "超时(ms)";
            case "shot" -> "路径";
            case "script" -> "脚本";
            default -> "参数";
        };
    }

    private void applyStepsFromVisual(String stepsJson) {
        try {
            JsonElement parsed = JsonParser.parseString(stepsJson == null ? "[]" : stepsJson);
            if (!parsed.isJsonArray()) return;

            JsonArray arr = parsed.getAsJsonArray();
            List<TaskStep> newSteps = new ArrayList<>();

            for (int i = 0; i < arr.size(); i++) {
                JsonObject o = arr.get(i).getAsJsonObject();
                String type = o.has("type") ? o.get("type").getAsString() : "";
                String action = o.has("action") ? o.get("action").getAsString() : "";
                String label = o.has("label") ? o.get("label").getAsString() : "";
                String value = o.has("value") ? o.get("value").getAsString() : "";

                // 第一条 nav：同步到 URL 输入框，不计入 taskSteps
                if (i == 0 && "nav".equals(type)) {
                    if (urlTextField != null) urlTextField.setText(value == null ? "" : value);
                    continue;
                }

                TaskStep step = new TaskStep();
                step.setDescription(label);

                switch (type) {
                    case "click" -> {
                        step.setType(TaskStep.StepType.CLICK);
                        step.setSelector(value);
                    }
                    case "fill" -> {
                        step.setType(TaskStep.StepType.FILL);
                        step.setValue(value);
                    }
                    case "wait" -> {
                        step.setType(TaskStep.StepType.WAIT);
                        step.setValue(value);
                    }
                    case "shot" -> {
                        step.setType(TaskStep.StepType.SCREENSHOT);
                        step.setValue(value);
                    }
                    case "script" -> {
                        step.setType(TaskStep.StepType.SCRIPT);
                        step.setValue(value);
                    }
                    case "nav" -> {
                        // 非首条 nav：作为普通步骤
                        step.setType(TaskStep.StepType.NAVIGATE);
                        step.setValue(value);
                    }
                    default -> {
                        // fallback：按 action 推断
                        TaskStep.StepType t = fromActionLabel(action);
                        step.setType(t);
                        step.setValue(value);
                    }
                }

                newSteps.add(step);
            }

            // 刷新列表（会触发 selection listener，这里先清空选择）
            handleCancelEdit();
            taskSteps.setAll(newSteps);
            stepListView.getItems().clear();
            for (TaskStep s : taskSteps) {
                stepListView.getItems().add(formatStep(s));
            }

            appendLog(logTextArea, "✓ 可视化编辑器已同步到步骤列表");
        } catch (Exception e) {
            appendLog(logTextArea, "✗ 从可视化编辑器同步失败: " + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
        }
    }

    private TaskStep.StepType fromActionLabel(String action) {
        String a = action == null ? "" : action;
        return switch (a) {
            case "导航" -> TaskStep.StepType.NAVIGATE;
            case "点击" -> TaskStep.StepType.CLICK;
            case "填充" -> TaskStep.StepType.FILL;
            case "截图" -> TaskStep.StepType.SCREENSHOT;
            case "等待" -> TaskStep.StepType.WAIT;
            case "执行脚本" -> TaskStep.StepType.SCRIPT;
            default -> TaskStep.StepType.CLICK;
        };
    }

    private String prepareFlowEditorUrl() throws IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "kk-webauto-flow-editor");
        Path vendor = root.resolve("vendor").resolve("x6");
        Files.createDirectories(vendor);

        copyResource("/web/flow-editor.html", root.resolve("flow-editor.html"));
        copyResource("/web/vendor/x6/x6.min.js", vendor.resolve("x6.min.js"));
        copyResource("/web/vendor/x6/x6.css", vendor.resolve("x6.css"));

        return root.resolve("flow-editor.html").toUri().toString();
    }

    private void copyResource(String classpath, Path target) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(classpath)) {
            if (in == null) throw new IOException("resource not found: " + classpath);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
