package org.mvnsearch.http.protocol;

import com.aliyun.eventbridge.EventBridge;
import com.aliyun.eventbridge.EventBridgeClient;
import com.aliyun.eventbridge.models.CloudEvent;
import com.aliyun.eventbridge.models.Config;
import com.aliyun.eventbridge.models.PutEventsResponse;
import com.aliyun.eventbridge.util.EventBuilder;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import io.nats.client.Nats;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.mvnsearch.http.logging.HttpxErrorCodeLogger;
import org.mvnsearch.http.logging.HttpxErrorCodeLoggerFactory;
import org.mvnsearch.http.model.HttpRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;
import reactor.kafka.sender.SenderRecord;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Sender;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static reactor.core.publisher.SignalType.ON_COMPLETE;


public class MessagePublishExecutor implements BaseExecutor {
    private static final HttpxErrorCodeLogger log = HttpxErrorCodeLoggerFactory.getLogger(MessagePublishExecutor.class);

    @Override
    public List<byte[]> execute(HttpRequest httpRequest) {
        final URI realURI = httpRequest.getRequestTarget().getUri();
        String schema = realURI.getScheme();
        System.out.println("PUB " + realURI);
        System.out.println();
        if (Objects.equals(schema, "kafka")) {
            sendKafka(realURI, httpRequest);
        } else if (Objects.equals(schema, "amqp") || Objects.equals(schema, "amqps")) {
            sendRabbitMQ(realURI, httpRequest);
        } else if (Objects.equals(schema, "nats")) {
            sendNatsMessage(realURI, httpRequest);
        } else if (Objects.equals(schema, "rocketmq")) {
            sendRocketMessage(realURI, httpRequest);
        } else if (Objects.equals(schema, "eventbridge") && realURI.getHost().contains("aliyuncs")) {
            publishAliyunEventBridge(realURI, httpRequest);
        } else {
            System.err.println("Not support: " + realURI);
        }
        return Collections.emptyList();
    }

    public void sendKafka(URI kafkaURI, HttpRequest httpRequest) {
        Properties props = new Properties();
        int port = kafkaURI.getPort();
        if (port <= 0) {
            port = 9092;
        }
        String topic = kafkaURI.getPath().substring(1);
        String body = new String(httpRequest.getBodyBytes(), StandardCharsets.UTF_8);
        Integer partition = null;
        String key = null;
        final Map<String, String> params = queryToMap(kafkaURI);
        if (params.containsKey("key")) {
            key = params.get("key");
        }
        if (params.containsKey("partition")) {
            partition = Integer.valueOf(params.get("partition"));
        }
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaURI.getHost() + ":" + port);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        KafkaSender<String, String> sender = KafkaSender.create(SenderOptions.create(props));
        sender.send(Mono.just(SenderRecord.create(topic, partition, System.currentTimeMillis(),
                        key, body, null)))
                .doOnError(e -> {
                    log.error("HTX-105-500", httpRequest.getRequestTarget().getUri(), e);
                })
                .doFinally(signalType -> {
                    if (signalType == ON_COMPLETE) {
                        System.out.print("Succeeded to send message to " + topic + "!");
                    }
                    sender.close();
                })
                .blockLast();
    }

    public void sendRabbitMQ(URI rabbitURI, HttpRequest httpRequest) {
        try {
            ConnectionFactory connectionFactory = new ConnectionFactory();
            connectionFactory.useNio();
            URI connectionUri;
            String queue;
            final String hostHeader = httpRequest.getHeader("Host");
            if (hostHeader != null) {
                connectionUri = URI.create(hostHeader);
                queue = httpRequest.getRequestTarget().getRequestLine();
            } else {
                connectionUri = rabbitURI;
                queue = queryToMap(rabbitURI).get("queue");
            }
            connectionFactory.setUri(connectionUri);
            reactor.rabbitmq.SenderOptions senderOptions = new reactor.rabbitmq.SenderOptions()
                    .connectionFactory(connectionFactory)
                    .resourceManagementScheduler(Schedulers.boundedElastic());

            final Sender rabbitSender = RabbitFlux.createSender(senderOptions);
            rabbitSender
                    .send(Mono.just(new OutboundMessage("", queue, httpRequest.getBodyBytes())))
                    .doOnError(e -> {
                        log.error("HTX-105-500", httpRequest.getRequestTarget().getUri(), e);
                    })
                    .doFinally(signalType -> {
                        if (signalType == ON_COMPLETE) {
                            System.out.print("Succeeded to send message to " + queue + "!");
                        }
                        rabbitSender.close();
                    }).block();
        } catch (Exception ignore) {
            log.error("HTX-105-401", httpRequest.getRequestTarget().getUri());
        }
    }

    public void sendNatsMessage(URI natsURI, HttpRequest httpRequest) {
        String topic = natsURI.getPath().substring(1);
        byte[] body = httpRequest.getBodyBytes();
        try (io.nats.client.Connection nc = Nats.connect(natsURI.toString())) {
            nc.publish(topic, body);
            System.out.print("Succeeded to send message to " + topic + "!");
        } catch (Exception e) {
            log.error("HTX-105-500", httpRequest.getRequestTarget().getUri(), e);
        }
    }

    public void sendRocketMessage(URI rocketURI, HttpRequest httpRequest) {
        DefaultMQProducer producer = new DefaultMQProducer("httpx-cli");
        try {
            // Specify name server addresses.
            String nameServerAddress = rocketURI.getHost() + ":" + rocketURI.getPort();
            String topic = rocketURI.getPath().substring(1);
            producer.setNamesrvAddr(nameServerAddress);
            //Launch the instance.
            producer.start();
            Message msg = new Message(topic, httpRequest.getBodyBytes());
            //Call send message to deliver message to one of brokers.
            SendResult sendResult = producer.send(msg);
            System.out.println("Succeeded to send message to " + topic + "!");
            System.out.print(sendResult.toString());
        } catch (Exception e) {
            log.error("HTX-105-500", httpRequest.getRequestTarget().getUri(), e);
        } finally {
            producer.shutdown();
        }
    }


    @SuppressWarnings("unchecked")
    public void publishAliyunEventBridge(URI eventBridgeURI, HttpRequest httpRequest) {
        String[] keyIdAndSecret = httpRequest.getBasicAuthorization();
        if (keyIdAndSecret == null) {
            System.err.println("Please supply access key Id/Secret in Authorization header as : `Authorization: Basic keyId:secret`");
            return;
        }
        try {
            String eventBus = eventBridgeURI.getPath().substring(1);
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
            final Map<String, Object> cloudEvent = objectMapper.readValue(httpRequest.getBodyBytes(), Map.class);
            //validate cloudEvent
            String source = (String) cloudEvent.get("source");
            if (source == null) {
                System.err.println("Please supply source field in json body!");
                return;
            }
            String datacontenttype = (String) cloudEvent.get("datacontenttype");
            if (datacontenttype != null && !datacontenttype.startsWith("application/json")) {
                System.err.println("datacontenttype's value should be 'application/json'!");
                return;
            }
            final Object data = cloudEvent.get("data");
            if (data == null) {
                System.err.println("data field should be supplied in json body!");
                return;
            }
            String jsonData;
            if (data instanceof Map<?, ?> || data instanceof List<?>) {
                jsonData = objectMapper.writeValueAsString(data);
            } else {
                jsonData = data.toString();
            }
            String eventId = (String) cloudEvent.get("id");
            if (eventId == null) {
                eventId = UUID.randomUUID().toString();
            }
            Config authConfig = new Config();
            authConfig.accessKeyId = keyIdAndSecret[0];
            authConfig.accessKeySecret = keyIdAndSecret[1];
            authConfig.endpoint = eventBridgeURI.getHost();
            EventBridge eventBridgeClient = new EventBridgeClient(authConfig);
            final CloudEvent event = EventBuilder.builder()
                    .withId(eventId)
                    .withSource(URI.create(source))
                    .withType((String) cloudEvent.get("type"))
                    .withSubject((String) cloudEvent.get("subject"))
                    .withTime(new Date())
                    .withJsonStringData(jsonData)
                    .withAliyunEventBus(eventBus)
                    .build();
            System.out.println("Begin to send message to " + eventBus + " with '" + eventId + "' ID");
            PutEventsResponse putEventsResponse = eventBridgeClient.putEvents(List.of(event));
            System.out.println("Succeeded with Aliyun EventBridge Response:");
            System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(putEventsResponse));
        } catch (Exception e) {
            log.error("HTX-105-500", httpRequest.getRequestTarget().getUri(), e);
        }
    }

}
