package dq.http;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Example host: exposes the dq-engine library over HTTP. */
@SpringBootApplication
public class DqHttpApplication {
    public static void main(String[] args) {
        SpringApplication.run(DqHttpApplication.class, args);
    }
}
