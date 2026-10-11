package ru.whitebeef.beefsavebot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BeefSaveBotApplication {

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(BeefSaveBotApplication.class);
    // REST API для интеграций поднимается, только когда задан ключ: без него бот как раньше — без веба
    String key = System.getenv("API_KEY");
    app.setWebApplicationType(key == null || key.isBlank() ? WebApplicationType.NONE : WebApplicationType.SERVLET);
    app.run(args);
  }

}
