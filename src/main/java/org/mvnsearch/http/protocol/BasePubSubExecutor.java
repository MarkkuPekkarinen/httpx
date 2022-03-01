package org.mvnsearch.http.protocol;

import org.mvnsearch.http.model.HttpRequest;

import java.net.URI;


public interface BasePubSubExecutor extends BaseExecutor {

    record UriAndSubject(String uri, String subject) {
    }

    default UriAndSubject getRedisUriAndChannel(URI redisURI, HttpRequest httpRequest) {
        String connectionUri;
        String channel;
        final String hostHeader = httpRequest.getHeader("Host");
        if (hostHeader != null) {
            connectionUri = hostHeader;
            channel = httpRequest.getRequestTarget().getRequestLine();
        } else {
            connectionUri = redisURI.toString();
            final int offset = connectionUri.lastIndexOf("/");
            channel = connectionUri.substring(offset + 1);
            connectionUri = connectionUri.substring(0, offset);
        }
        return new UriAndSubject(connectionUri, channel);
    }

    default UriAndSubject getRabbitUriAndQueue(URI rabbitURI, HttpRequest httpRequest) {
        String connectionUri;
        String queue;
        final String hostHeader = httpRequest.getHeader("Host");
        if (hostHeader != null) {
            connectionUri = hostHeader;
            queue = httpRequest.getRequestTarget().getRequestLine();
        } else {
            connectionUri = rabbitURI.toString();
            queue = queryToMap(rabbitURI).get("queue");
        }
        return new UriAndSubject(connectionUri, queue);
    }

    default UriAndSubject getMqttUriAndTopic(URI mqttURI, HttpRequest httpRequest) {
        String topic = mqttURI.getPath().substring(1);
        String schema = mqttURI.getScheme();
        String brokerUrl = mqttURI.toString();
        if (schema.contains("+")) {
            brokerUrl = brokerUrl.substring(brokerUrl.indexOf("+") + 1);
        } else {
            brokerUrl = brokerUrl.replace("mqtt://", "tcp://");
        }
        brokerUrl = brokerUrl.substring(0, brokerUrl.lastIndexOf("/"));
        return new UriAndSubject(brokerUrl, topic);
    }
}
