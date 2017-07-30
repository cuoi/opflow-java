package com.devebot.opflow;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.devebot.opflow.exception.OpflowConstructorException;
import com.devebot.opflow.exception.OpflowOperationException;

/**
 *
 * @author drupalex
 */
public class OpflowBroker {

    final Logger logger = LoggerFactory.getLogger(OpflowBroker.class);

    private ConnectionFactory factory;
    private Connection connection;
    private Channel channel;

    private String exchangeName;
    private String exchangeType;
    private String routingKey;

    public OpflowBroker(Map<String, Object> params) throws Exception {
        factory = new ConnectionFactory();

        String uri = (String) params.get("uri");
        if (uri != null) {
            factory.setUri(uri);
        } else {
            String host = (String) params.get("host");
            if (host == null) host = "localhost";
            factory.setHost(host);

            String virtualHost = (String) params.get("virtualHost");
            if (virtualHost != null) {
                factory.setVirtualHost(virtualHost);
            }

            String username = (String) params.get("username");
            if (username != null) {
                factory.setUsername(username);
            }

            String password = (String) params.get("password");
            if (password != null) {
                factory.setPassword(password);
            }
        }
        
        connection = factory.newConnection();

        exchangeName = (String) params.get("exchangeName");
        exchangeType = (String) params.get("exchangeType");
        routingKey = (String) params.get("routingKey");
        
        if (exchangeType == null) exchangeType = "direct";

        if (exchangeName != null && exchangeType != null) {
            getChannel().exchangeDeclare(exchangeName, exchangeType, true);
        }
    }

    public void produce(final byte[] content, final AMQP.BasicProperties props, final Map<String, Object> override) {
        try {
            String customKey = this.routingKey;
            if (override != null && override.get("routingKey") != null) {
                customKey = (String) override.get("routingKey");
            }
            getChannel().basicPublish(this.exchangeName, customKey, props, content);
        } catch (IOException exception) {
            throw new OpflowOperationException(exception);
        }
    }
    
    public ConsumerInfo consume(final OpflowListener listener, final Map<String, Object> options) {
        Map<String, Object> opts = OpflowUtil.ensureNotNull(options);
        try {
            final Channel _channel;
            
            final Boolean _forceNewChannel = (Boolean) opts.get("forceNewChannel");
            if (!Boolean.FALSE.equals(_forceNewChannel)) {
                _channel = this.connection.createChannel();
            } else {
                _channel = getChannel();
            }
            
            Integer _prefetch = null;
            if (opts.get("prefetch") instanceof Integer) {
                _prefetch = (Integer) opts.get("prefetch");
            }
            if (_prefetch != null && _prefetch > 0) {
                _channel.basicQos(_prefetch);
            }
            
            final String _queueName;
            String opts_queueName = (String) opts.get("queueName");
            if (opts_queueName != null) {
                _queueName = _channel.queueDeclare(opts_queueName, true, false, false, null).getQueue();
            } else {
                _queueName = _channel.queueDeclare().getQueue();
            }
            
            final Boolean _binding = (Boolean) opts.get("binding");
            if (!Boolean.FALSE.equals(_binding) && exchangeName != null && routingKey != null) {
                _channel.exchangeDeclarePassive(exchangeName);
                Map<String, Object> _bindingArgs = (Map<String, Object>) opts.get("bindingArgs");
                if (_bindingArgs == null) _bindingArgs = new HashMap<String, Object>();
                _channel.queueBind(_queueName, exchangeName, routingKey, _bindingArgs);
                if (logger.isTraceEnabled()) {
                    logger.trace(MessageFormat.format("Exchange[{0}] binded to Queue[{1}] with routingKey[{2}]", new Object[] {
                        exchangeName, _queueName, routingKey
                    }));
                }
            }
            
            final String _replyToName;
            String opts_replyToName = (String) opts.get("replyTo");
            if (opts_replyToName != null) {
                _replyToName = _channel.queueDeclarePassive(opts_replyToName).getQueue();
            } else {
                _replyToName = null;
            }
            
            final Consumer _consumer = new DefaultConsumer(_channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope,
                                           AMQP.BasicProperties properties, byte[] body) throws IOException {
                    String requestID = getRequestID(properties.getHeaders());

                    if (logger.isInfoEnabled()) {
                        logger.info("Request["+requestID+"] / DeliveryTag["+envelope.getDeliveryTag()+"] / ConsumerTag["+consumerTag+"]");
                    }

                    if (logger.isTraceEnabled()) {
                        if (body.length < 4*1024) {
                            logger.trace("Request[" + requestID + "] - Message: " + new String(body, "UTF-8"));
                        } else {
                            logger.trace("Request[" + requestID + "] - Message size too large: " + body.length);
                        }
                    }

                    if (logger.isTraceEnabled()) logger.trace(MessageFormat.format("Request[{0}] invoke listener.processMessage()", new Object[] {
                        requestID
                    }));
                    listener.processMessage(body, properties, _replyToName, _channel);

                    if (logger.isTraceEnabled()) {
                        logger.trace(MessageFormat.format("Request[{0}] invoke Ack({1}, false)) / ConsumerTag[{2}]", new Object[] {
                            requestID, envelope.getDeliveryTag(), consumerTag
                        }));
                    }
                    _channel.basicAck(envelope.getDeliveryTag(), false);

                    if (logger.isInfoEnabled()) {
                        logger.info("Request[" + requestID + "] has finished successfully");
                    }
                }
                
                @Override
                public void handleCancelOk(String consumerTag) {
                    if (!Boolean.FALSE.equals(_forceNewChannel)) {
                        try {
                            _channel.close();
                        } catch (IOException ex) {
                            
                        } catch (TimeoutException ex) {
                            
                        }
                    }
                }
                
                @Override
                public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {
                    if (logger.isInfoEnabled()) {
                        logger.info(MessageFormat.format("ConsumerTag[{1}] handle shutdown signal", new Object[] {
                            consumerTag
                        }));
                    }
                }
            };
            
            final String _consumerTag = _channel.basicConsume(_queueName, false, _consumer);
                        
            _channel.addShutdownListener(new ShutdownListener() {
                @Override
                public void shutdownCompleted(ShutdownSignalException sse) {
                    if (logger.isInfoEnabled()) {
                        logger.info(MessageFormat.format("Channel[{0}] contains Queue[{1}]/ConsumerTag[{2}] has been shutdown", new Object[] {
                            _channel.getChannelNumber(), _queueName, _consumerTag
                        }));
                    }
                }
            });
            
            if (logger.isInfoEnabled()) {
                logger.info("[*] Consume Channel[" + _channel.getChannelNumber() + "]/Queue[" + _queueName + "] -> consumerTag: " + _consumerTag);
            }
            return new ConsumerInfo(_channel, _queueName, _consumer, _consumerTag);
        } catch(IOException exception) {
            if (logger.isErrorEnabled()) logger.error("consume() has been failed, exception: " + exception.getMessage());
            throw new OpflowOperationException(exception);
        }
    }
    
    public class ConsumerInfo {
        private final Channel channel;
        private final String queueName;
        private final Consumer consumer;
        private final String consumerTag;
        
        public ConsumerInfo(Channel channel, String queueName, Consumer consumer, String consumerTag) {
            this.channel = channel;
            this.queueName = queueName;
            this.consumer = consumer;
            this.consumerTag = consumerTag;
        }

        public Channel getChannel() {
            return channel;
        }

        public String getQueueName() {
            return queueName;
        }

        public Consumer getConsumer() {
            return consumer;
        }

        public String getConsumerTag() {
            return consumerTag;
        }
    }
    
    public void close() {
        try {
            if (logger.isInfoEnabled()) logger.info("[*] Cancel consumers, close channels, close connection.");
            if (channel != null) channel.close();
            if (connection != null) connection.close();
        } catch (Exception exception) {
            if (logger.isErrorEnabled()) logger.error("close() has been failed, exception: " + exception.getMessage());
            throw new OpflowOperationException(exception);
        }
    }
    
    private Channel getChannel() {
        if (channel == null) {
            try {
                channel = connection.createChannel();
                channel.addShutdownListener(new ShutdownListener() {
                    @Override
                    public void shutdownCompleted(ShutdownSignalException sse) {
                        if (logger.isInfoEnabled()) {
                            logger.info("Main channel[" + channel.getChannelNumber() + "] has been shutdown");
                        }
                    }
                });
            } catch (IOException exception) {
                if (logger.isErrorEnabled()) logger.error("getChannel() has been failed, exception: " + exception.getMessage());
                throw new OpflowConstructorException(exception);
            }
        }
        return channel;
    }
    
    private String getRequestID(Map<String, Object> headers) {
        if (headers == null) return UUID.randomUUID().toString();
        Object requestID = headers.get("requestId");
        if (requestID == null) return UUID.randomUUID().toString();
        return requestID.toString();
    }
}