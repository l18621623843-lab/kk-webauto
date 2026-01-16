package com.kk.app;

import com.kk.ui.JavaFXApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication(scanBasePackages = {
        "com.kk.core",
        "com.kk.playwright",
        "com.kk.ui"
})
public class Application {

    public static void main(String[] args) {
        // 先启动Spring容器
        ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
                .headless(false)  // 禁用headless模式以支持JavaFX
                .run(args);

        // 将Spring上下文传递给JavaFX应用
        JavaFXApplication.setSpringContext(context);

        // 启动JavaFX应用
        javafx.application.Application.launch(JavaFXApplication.class, args);
    }
}