package com.eap.eap_matchengine.configuration.reliability;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageBatchRecoverer;

import java.util.List;

import static com.eap.common.reliability.TransientDatabaseOutageClassifier.isDatabaseUnavailable;

public class MatchCdaDatabaseOutageMessageRecoverer implements MessageBatchRecoverer {

    private final MatchCdaDatabaseOutageCircuitBreaker circuitBreaker;

    public MatchCdaDatabaseOutageMessageRecoverer(MatchCdaDatabaseOutageCircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        recover(List.of(message), cause);
    }

    @Override
    public void recover(List<Message> messages, Throwable cause) {
        String sourceQueue = messages == null || messages.isEmpty()
                ? null
                : messages.get(0).getMessageProperties().getConsumerQueue();
        if (circuitBreaker.ownsQueue(sourceQueue) && isDatabaseUnavailable(cause)) {
            circuitBreaker.open(cause);
            throw new ImmediateRequeueAmqpException(
                    "MatchEngine database unavailable before durable consumer commit", cause);
        }
        throw new AmqpRejectAndDontRequeueException(
                "MatchEngine listener retries exhausted for a non-outage failure", true, cause);
    }
}
