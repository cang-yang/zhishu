package com.canggo.zhishu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ZhiShuApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZhiShuApplication.class, args);
    }

}
