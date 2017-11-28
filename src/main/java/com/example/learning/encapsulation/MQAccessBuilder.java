package com.example.learning.encapsulation;

import com.example.learning.common.Constants;
import com.example.learning.common.DetailRes;
import com.rabbitmq.client.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.CorrelationData;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.amqp.rabbit.support.MessagePropertiesConverter;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Created by littlersmall on 16/5/11.
 * http://www.jianshu.com/p/4112d78a8753
 */
@Slf4j
public class MQAccessBuilder {

    private ConnectionFactory connectionFactory;

    public MQAccessBuilder(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * 构造方法一  使用direct作为路由方式，它会把消息路由到那些binding key与routing key完全匹配的Queue中。
     *
     * @param exchange
     * @param routingKey
     * @param queue
     * @return
     * @throws IOException
     */
    public MessageSender buildMessageSender(final String exchange, final String routingKey, final String queue) throws IOException {
        return buildMessageSender(exchange, routingKey, queue, "direct");
    }

    /**
     * 构造方法二  使用topic作为路由方式
     * <p>
     * routing key为一个句点号“. ”分隔的字符串（我们将被句点号“. ”分隔开的每一段独立的字符串称为一个单词），如“stock.usd.nyse”、“nyse.vmw”、“quick.orange.rabbit”
     * binding key与routing key一样也是句点号“. ”分隔的字符串
     * binding key中可以存在两种特殊字符“*”与“#”，用于做模糊匹配，其中“*”用于匹配一个单词，“#”用于匹配多个单词（可以是零个）
     *
     * @param exchange
     * @param routingKey
     * @return
     * @throws IOException
     */
    public MessageSender buildTopicMessageSender(final String exchange, final String routingKey) throws IOException {
        return buildMessageSender(exchange, routingKey, null, "topic");
    }


    /**
     * 生产者：
     * <p>
     * 1 构造template, exchange, routingkey等
     * 2 设置message序列化方法
     * 3 设置发送确认
     * 4 构造sender方法
     *
     * @param exchange
     * @param routingKey
     * @param queue
     * @param type
     * @return
     * @throws IOException
     */
    public MessageSender buildMessageSender(final String exchange, final String routingKey,
                                            final String queue, final String type) throws IOException {
        Connection connection = connectionFactory.createConnection();
        //1
        if (type.equals("direct")) {
            buildQueue(exchange, routingKey, queue, connection, "direct");
        } else if (type.equals("topic")) {
            buildTopic(exchange, connection);
        }

        final RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);

        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setExchange(exchange);
        rabbitTemplate.setRoutingKey(routingKey);
        //2
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());
        RetryCache retryCache = new RetryCache();

        //3
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            /**
             * 通过回调返回值ack判断是佛消费成功
             * 失败 则打印消费失败原因
             */
            if (!ack) {
                log.info("send message failed: " + cause + correlationData.toString());
            } else {
                /**
                 * 消费成功  删除该任务
                 */
                retryCache.del(correlationData.getId());
            }
        });

        rabbitTemplate.setReturnCallback((message, replyCode, replyText, tmpExchange, tmpRoutingKey) -> {
            try {
                Thread.sleep(Constants.ONE_SECOND);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            log.info("send message failed: " + replyCode + " " + replyText);
            rabbitTemplate.send(message);
        });

        //4
        return new MessageSender() {
            {
                retryCache.setSender(this);
            }

            @Override
            public DetailRes send(Object message) {
                try {
                    String id = retryCache.generateId();
                    retryCache.add(id, message);
                    rabbitTemplate.correlationConvertAndSend(message, new CorrelationData(id));
                } catch (Exception e) {
                    return new DetailRes(false, "");
                }

                return new DetailRes(true, "");
            }
        };
    }


    public <T> MessageConsumer buildMessageConsumer(String exchange, String routingKey, final String queue,
                                                    final MessageProcess<T> messageProcess) throws IOException {
        return buildMessageConsumer(exchange, routingKey, queue, messageProcess, "direct");
    }

    public <T> MessageConsumer buildTopicMessageConsumer(String exchange, String routingKey, final String queue,
                                                         final MessageProcess<T> messageProcess) throws IOException {
        return buildMessageConsumer(exchange, routingKey, queue, messageProcess, "topic");
    }

    /**
     * 消费者：
     * <p>
     * 1 创建连接和channel
     * 2 设置message序列化方法
     * 3 构造consumer
     *
     * @param exchange
     * @param routingKey
     * @param queue
     * @param messageProcess
     * @param <T>
     * @return
     * @throws IOException
     */
    public <T> MessageConsumer buildMessageConsumer(String exchange, String routingKey, final String queue,
                                                    final MessageProcess<T> messageProcess, String type) throws IOException {
        final Connection connection = connectionFactory.createConnection();

        //1
        buildQueue(exchange, routingKey, queue, connection, type);

        //2
        final MessagePropertiesConverter messagePropertiesConverter = new DefaultMessagePropertiesConverter();
        final MessageConverter messageConverter = new Jackson2JsonMessageConverter();

        //3
        return new MessageConsumer() {
            QueueingConsumer consumer;

            {
                consumer = buildQueueConsumer(connection, queue);
            }

            @Override
            //1 通过delivery获取原始数据
            //2 将原始数据转换为特定类型的包
            //3 处理数据
            //4 手动发送ack确认
            public DetailRes consume() {
                QueueingConsumer.Delivery delivery;
                Channel channel = consumer.getChannel();

                try {
                    //1
                    delivery = consumer.nextDelivery();
                    Message message = new Message(delivery.getBody(),
                            messagePropertiesConverter.toMessageProperties(delivery.getProperties(), delivery.getEnvelope(), "UTF-8"));

                    //2
                    @SuppressWarnings("unchecked")
                    T messageBean = (T) messageConverter.fromMessage(message);

                    //3
                    DetailRes detailRes;

                    try {
                        detailRes = messageProcess.process(messageBean);
                    } catch (Exception e) {
                        detailRes = new DetailRes(false, "process exception: " + e);
                    }

                    //4  对于消息处理成功或者失败给出的信息
                    if (detailRes.isSuccess()) {
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    } else {
                        //避免过多失败log
                        Thread.sleep(Constants.ONE_SECOND);
                        log.info("process message failed: " + detailRes.getErrMsg());
                        channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
                    }

                    return detailRes;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    return new DetailRes(false, "interrupted exception " + e.toString());
                }
                //自动重连机制
                catch (ShutdownSignalException | ConsumerCancelledException | IOException e) {
                    e.printStackTrace();

                    try {
                        channel.close();
                    } catch (IOException | TimeoutException ex) {
                        ex.printStackTrace();
                    }

                    consumer = buildQueueConsumer(connection, queue);

                    return new DetailRes(false, "shutdown or cancelled exception " + e.toString());
                } catch (Exception e) {
                    e.printStackTrace();
                    log.info("exception : ", e);

                    try {
                        channel.close();
                    } catch (IOException | TimeoutException ex) {
                        ex.printStackTrace();
                    }

                    consumer = buildQueueConsumer(connection, queue);

                    return new DetailRes(false, "exception " + e.toString());
                }
            }
        };
    }

    private void buildQueue(String exchange, String routingKey,
                            final String queue, Connection connection, String type) throws IOException {
        Channel channel = connection.createChannel(false);

        if (type.equals("direct")) {
            channel.exchangeDeclare(exchange, "direct", true, false, null);
        } else if (type.equals("topic")) {
            channel.exchangeDeclare(exchange, "topic", true, false, null);
        }

        channel.queueDeclare(queue, true, false, false, null);
        channel.queueBind(queue, exchange, routingKey);

        try {
            channel.close();
        } catch (TimeoutException e) {
            e.printStackTrace();
            log.info("close channel time out ", e);
        }
    }

    private void buildTopic(String exchange, Connection connection) throws IOException {
        Channel channel = connection.createChannel(false);
        channel.exchangeDeclare(exchange, "topic", true, false, null);
    }

    private QueueingConsumer buildQueueConsumer(Connection connection, String queue) {
        try {
            Channel channel = connection.createChannel(false);
            QueueingConsumer consumer = new QueueingConsumer(channel);

            //通过 BasicQos 方法设置prefetchCount = 1。这样RabbitMQ就会使得每个Consumer在同一个时间点最多处理一个Message。
            //换句话说，在接收到该Consumer的ack前，他它不会将新的Message分发给它
            channel.basicQos(1);
            channel.basicConsume(queue, false, consumer);

            return consumer;
        } catch (Exception e) {
            e.printStackTrace();
            log.info("build queue consumer error : ", e);

            try {
                Thread.sleep(Constants.ONE_SECOND);
            } catch (InterruptedException inE) {
                inE.printStackTrace();
            }

            return buildQueueConsumer(connection, queue);
        }
    }

    //for test
    public int getMessageCount(final String queue) throws IOException {
        Connection connection = connectionFactory.createConnection();
        final Channel channel = connection.createChannel(false);
        final AMQP.Queue.DeclareOk declareOk = channel.queueDeclarePassive(queue);

        return declareOk.getMessageCount();
    }
}