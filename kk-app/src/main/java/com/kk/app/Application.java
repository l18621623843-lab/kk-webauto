package com.kk.app;

import cn.hutool.extra.spring.SpringUtil;
import com.kk.ui.JavaFXApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication(scanBasePackages = {"com.kk"})
public class Application {

    public static void main(String[] args) {
        // 先启动Spring容器
        ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
                .headless(false)  // 禁用headless模式以支持JavaFX
                .run(args);
        // 启动JavaFX应用, 如果p2p - relay 启动hop模式则不启用javafx
        if (!"HOP".equals(SpringUtil.getProperty("kk.p2p.relay.mode"))) {

            // 判断当前环境是否为windows系统
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                JavaFXApplication.setSpringContext(context);
                javafx.application.Application.launch(JavaFXApplication.class, args);
            }
        }
    }
}