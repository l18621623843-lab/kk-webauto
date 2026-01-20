package com.kk.ui;

import com.kk.ui.controller.MainController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * JavaFX 应用入口
 */
public class JavaFXApplication extends Application {

    private static ConfigurableApplicationContext springContext;
    private static Stage primaryStage;

    public static void setSpringContext(ConfigurableApplicationContext context) {
        springContext = context;
    }

    @Override
    public void start(Stage stage) throws Exception {
        primaryStage = stage;

        // 使用 Spring 管理的 FXMLLoader
        // 旧版界面（Web 自动化/流程编排）：/fxml/main-view.fxml（暂不启用）
        // 新版界面：点对点消息收发（聊天）
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/chat-view.fxml"));
        loader.setControllerFactory(springContext::getBean);


        Parent root = loader.load();

        Scene scene = new Scene(root);
        stage.setTitle("kk-platform");
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            Platform.exit();
            springContext.close();
        });

        stage.show();
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        if (springContext != null && springContext.isActive()) {
            springContext.close();
        }
    }
}