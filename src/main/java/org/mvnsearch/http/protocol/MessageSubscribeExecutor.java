package org.mvnsearch.http.protocol;

import com.rabbitmq.client.ConnectionFactory;
import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Subscription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.mvnsearch.http.logging.HttpxErrorCodeLogger;
import org.mvnsearch.http.logging.HttpxErrorCodeLoggerFactory;
import org.mvnsearch.http.model.HttpRequest;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;


public class MessageSubscribeExecutor implements BasePubSubExecutor {
    private static final HttpxErrorCodeLogger log = HttpxErrorCodeLoggerFactory.getLogger(MessageSubscribeExecutor.class);

    @Override
    public List<byte[]> execute(HttpRequest httpRequest) {
        final URI realURI = httpRequest.getRequestTarget().getUri();
        String schema = realURI.getScheme();
        if (Objects.equals(realURI.getScheme(), "kafka")) {
            subscribeKafka(realURI, httpRequest);
        } else if (Objects.equals(schema, "amqp") || Objects.equals(schema, "amqps")) {
            subscribeRabbit(realURI, httpRequest);
        } else if (Objects.equals(schema, "nats")) {
            subscribeNats(realURI, httpRequest);
        } else if (Objects.equals(schema, "redis")) {
            subscribeRedis(realURI, httpRequest);
        } else if (Objects.equals(schema, "rocketmq")) {
            subscribeRocketmq(realURI, httpRequest);
        } else {
            System.err.println("Not support: " + realURI);
        }
        return Collections.emptyList();
    }

    public void subscribeKafka(URI kafkaURI, HttpRequest httpRequest) {
        Properties props = new Properties();
        int port = kafkaURI.getPort();
        if (port <= 0) {
            port = 9092;
        }
        String topic = kafkaURI.getPath().substring(1);
        final Map<String, String> params = queryToMap(kafkaURI);
        String groupId = "httpx-" + UUID.randomUUID();
        if (params.containsKey("group")) {
            groupId = params.get("group");
        }
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaURI.getHost() + ":" + port);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        reactor.kafka.receiver.ReceiverOptions<String, String> receiverOptions =
                reactor.kafka.receiver.ReceiverOptions.<String, String>create(props).subscription(Collections.singleton(topic));

        try {
            final KafkaReceiver<String, String> receiver = KafkaReceiver.create(receiverOptions);
            receiver.receive()
                    .doOnSubscribe(subscription -> {
                        System.out.println("Succeeded to subscribe: " + topic + "!");
                    })
                    .doOnNext(record -> {
                        String key = record.key();
                        System.out.println("Received message: " + (key == null ? "" : key));
                        System.out.println(record.value());
                    })
                    .blockLast();
        } catch (Exception e) {
            log.error("HTX-106-500", httpRequest.getRequestTarget().getUri(), e);
        }
    }

    public void subscribeRabbit(URI rabbitURI, HttpRequest httpRequest) {
        try {
            final UriAndSubject rabbitUriAndQueue = getRabbitUriAndQueue(rabbitURI, httpRequest);
            ConnectionFactory connectionFactory = new ConnectionFactory();
            connectionFactory.setUri(rabbitUriAndQueue.uri());
            ReceiverOptions receiverOptions = new ReceiverOptions()
                    .connectionFactory(connectionFactory)
                    .connectionSubscriptionScheduler(Schedulers.boundedElastic());
            final Receiver receiver = RabbitFlux.createReceiver(receiverOptions);
            receiver.consumeAutoAck(rabbitUriAndQueue.subject())
                    .doOnSubscribe(subscription -> {
                        System.out.println("SUB " + rabbitUriAndQueue.uri());
                        System.out.println();
                    })
                    .doOnError(e -> {
                        log.error("HTX-106-500", httpRequest.getRequestTarget().getUri(), e);
                    })
                    .doOnNext(delivery -> {
                        String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                        System.out.println(" [x] Received '" + message + "'");
                    })
                    .blockLast();
        } catch (Exception e) {
            log.error("HTX-105-401", httpRequest.getRequestTarget().getUri());
        }
    }

    public void subscribeNats(URI natsURI, HttpRequest httpRequest) {
        String topic = natsURI.getPath().substring(1);
        try (io.nats.client.Connection nc = Nats.connect(natsURI.toString())) {
            Subscription sub = nc.subscribe(topic);
            nc.flush(Duration.ofSeconds(5));
            System.out.println("Succeeded to subscribe(1000 max): " + topic + "!");
            for (int i = 0; i < 1000; i++) {
                Message msg = sub.nextMessage(Duration.ofHours(1));
                if (i > 0) {
                    System.out.println("======================================");
                }
                System.out.printf("Message Received [%d]\n", (i + 1));
                if (msg.hasHeaders()) {
                    System.out.println("  Headers:");
                    for (String key : msg.getHeaders().keySet()) {
                        for (String value : msg.getHeaders().get(key)) {
                            System.out.printf("    %s: %s\n", key, value);
                        }
                    }
                }
                System.out.printf("  Subject: %s\n  Data: %s\n",
                        msg.getSubject(),
                        new String(msg.getData(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.error("HTX-106-500", httpRequest.getRequestTarget().getUri(), e);
        }
    }

    public void subscribeRedis(URI redisURI, HttpRequest httpRequest) {
        final UriAndSubject redisUriAndChannel = getRedisUriAndChannel(redisURI, httpRequest);
        RedisClient client = RedisClient.create(redisUriAndChannel.uri());
        try (StatefulRedisPubSubConnection<String, String> connection = client.connectPubSub()) {
            RedisPubSubReactiveCommands<String, String> reactive = connection.reactive();
            reactive.subscribe(redisUriAndChannel.subject()).subscribe();
            System.out.println("Succeeded to subscribe: " + redisUriAndChannel.subject() + "!");
            reactive.observeChannels().doOnNext(patternMessage -> {
                System.out.println("Message Received:");
                System.out.println(patternMessage.getMessage());
            }).blockLast();
        } catch (Exception e) {
            log.error("HTX-105-500", redisUriAndChannel.uri(), e);
        }
    }

    public void subscribeRocketmq(URI rocketURI, HttpRequest httpRequest) {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("httpx-" + UUID.randomUUID());
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Thread.sleep(200);
                    latch.countDown();
                    System.out.println("Shutting down ...");
                } catch (Exception ignore) {
                }
            }));
            String nameServerAddress = rocketURI.getHost() + ":" + rocketURI.getPort();
            String topic = rocketURI.getPath().substring(1);
            consumer.setNamesrvAddr(nameServerAddress);
            consumer.subscribe(topic, "*");
            // Register callback to execute on arrival of messages fetched from brokers.
            consumer.registerMessageListener((MessageListenerConcurrently) (msgList, context) -> {
                for (MessageExt messageExt : msgList) {
                    System.out.println("msg: " + messageExt.getMsgId());
                    System.out.println(new String(messageExt.getBody(), StandardCharsets.UTF_8));
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();
            System.out.println("Succeeded to subscribe(1000 max): " + topic + "!");
            latch.await();
        } catch (Exception e) {
            log.error("HTX-105-500", httpRequest.getRequestTarget().getUri(), e);
        } finally {
            consumer.shutdown();
        }
    }
}
