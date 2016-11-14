package org.reactivecouchbase.examples.sbessentials;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.*;

import org.reactivecouchbase.concurrent.Future;
import org.reactivecouchbase.common.Duration;
import org.reactivecouchbase.sbessentials.libs.actions.Action;
import org.reactivecouchbase.sbessentials.libs.actions.ActionStep;
import org.reactivecouchbase.sbessentials.libs.result.Result;
import org.reactivecouchbase.sbessentials.libs.result.Results;
import static org.reactivecouchbase.sbessentials.libs.result.Results.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;

@ComponentScan
@SpringBootApplication
public class App {

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @RestController
    @RequestMapping("/api")
    public static class BaseController {

        private static final Logger logger = LoggerFactory.getLogger(BaseController.class);

        // Action that logs before request
        private static ActionStep LogBefore = (req, block) -> {
            Long start = System.currentTimeMillis();
            logger.info("[Log] before action -> {}", req.getRequest().getRequestURI());
            return block.apply(req.setValue("start", start));
        };

        // Action that logs after request
        private static ActionStep LogAfter = (req, block) -> block.apply(req).andThen(ttry -> {
            logger.info(
                "[Log] after action -> {} : took {}",
                req.getRequest().getRequestURI(),
                Duration.of(
                    System.currentTimeMillis() - req.getValue("start", Long.class),
                    TimeUnit.MILLISECONDS
                ).toHumanReadable()
            );
        });

        // Actions composition
        private static ActionStep LoggedAction = LogBefore.andThen(LogAfter);

        @GetMapping("/hello")
        public Action text() {
            // Use composed action
            return LoggedAction.sync(ctx ->
                Ok.text("Hello World!\n")
            );
        }
    }
}