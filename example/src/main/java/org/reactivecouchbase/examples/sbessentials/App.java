package org.reactivecouchbase.examples.sbessentials;

import akka.Done;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.reactivecouchbase.common.Duration;
import org.reactivecouchbase.concurrent.Future;
import org.reactivecouchbase.json.Json;
import org.reactivecouchbase.sbessentials.libs.actions.Action;
import org.reactivecouchbase.sbessentials.libs.actions.ActionStep;
import org.reactivecouchbase.sbessentials.libs.result.Result;
import org.reactivecouchbase.sbessentials.libs.websocket.WebSocket;
import org.reactivecouchbase.sbessentials.libs.websocket.WebSocketMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static akka.pattern.PatternsCS.after;
import static org.reactivecouchbase.sbessentials.libs.result.Results.Ok;

@SpringBootApplication
public class App {

    private static final Logger LOGGER = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @RestController
    @RequestMapping("/api")
    public static class BaseController {

        private static final Logger logger = LoggerFactory.getLogger(BaseController.class);

        @Autowired
        ActorSystem actorSystem;

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

        @WebSocketMapping(path = "/websocket/{id}")
        public WebSocket webSocket() {
            return WebSocket.accept(context ->
                    Flow.fromSinkAndSource(
                            Sink.foreach(msg -> LOGGER.info(msg)),
                            Source.tick(FiniteDuration.Zero(), FiniteDuration.create(1, TimeUnit.SECONDS), "msg"+context.pathVariable("id"))
                    )
            );
        }

        @GetMapping("/hello")
        public Action text() {
            // Use composed action
            return LoggedAction.sync(ctx ->
                    Ok.text("Hello World!\n")
            );
        }

        @RequestMapping(method = RequestMethod.GET, path = "/sse")
        public Future<Result> testStream() {
            return Actions.sync(ctx -> {

                Result result = Ok.stream(
                        Source.tick(
                                FiniteDuration.apply(0, TimeUnit.MILLISECONDS),
                                FiniteDuration.apply(1, TimeUnit.SECONDS),
                                ""
                        )
                                .map(l -> Json.obj().with("time", System.currentTimeMillis()).with("value", l))
                                .map(Json::stringify)
                                .map(j -> "data: " + j + "\n\n")
                ).as("text/event-stream");

                result.materializedValue(Cancellable.class).andThen(ttry -> {
                    for (Cancellable c : ttry.asSuccess()) {
                        after(
                                FiniteDuration.create(5, TimeUnit.SECONDS),
                                actorSystem.scheduler(),
                                actorSystem.dispatcher(),
                                CompletableFuture.completedFuture(Done.getInstance())
                        ).thenAccept(d ->
                                c.cancel()
                        );
                    }
                });

                return result;
            });
        }
    }
}