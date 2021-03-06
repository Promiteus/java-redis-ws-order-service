package com.romanm.websocket_and_redis.services.redis.publishers.orders;

import com.romanm.websocket_and_redis.components.utils.RedisSetStreamFilter;
import com.romanm.websocket_and_redis.events.orders.OrderEventPublisher;
import com.romanm.websocket_and_redis.models.orders.Order;
import com.romanm.websocket_and_redis.services.redis.RedisService;
import com.romanm.websocket_and_redis.services.topics.Topic;
import com.romanm.websocket_and_redis.utils.KeyFormatter;
import com.romanm.websocket_and_redis.utils.OrderJsonConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;

import java.util.Date;

@Slf4j
@Service
public class RedisOrderPublisherV1 implements RedisOrderPublisher {
    private Topic<Order> topic;
    private RedisService redisService;
    private OrderJsonConverter orderJsonConverter;
    private OrderEventPublisher orderEventPublisher;

    @Autowired
    public RedisOrderPublisherV1(Topic<Order> topic,
                                 RedisService redisService,
                                 OrderJsonConverter orderJsonConverter,
                                 OrderEventPublisher orderEventPublisher,
                                 RedisSetStreamFilter redisSetStreamFilter) {
        this.topic = topic;
        this.redisService = redisService;
        this.orderJsonConverter = orderJsonConverter;
        this.orderEventPublisher = orderEventPublisher;
    }

    @Override
    public Order publishOrder(Order order) {

        //Выполнить блок кода в одной транзакции для публикации заказа
        return (Order) this.redisService.getRedisTemplate().execute(new SessionCallback() {
            @Override
            public Object execute(RedisOperations redisOperations) throws DataAccessException {

                try {
                    redisOperations.multi();

                      //1. Помещаем/создаем заявку пользователя-заказчика в специальное множество имени userkod
                      redisOperations.opsForZSet().add(KeyFormatter.hideHyphenChar(order.getUserkod()), orderJsonConverter.convertObjectToJson(order), new Date().getTime());
                      //2. Получаем транслированное имя региона и создаем структуру для чтения заказов исполнителями по регионам
                      redisOperations.opsForZSet().add(topic.getTopic(order), orderJsonConverter.convertObjectToJson(order), new Date().getTime());
                      //3. Отправить в канал websocket уведомление о смене статуса заказа (Новый заказ)
                      orderEventPublisher.publishOrderEvent(topic.getTopic(order), orderJsonConverter.convertObjectToJson(order));

                    redisOperations.exec();

                } catch (Exception e) {
                    log.error("publishOrder error:"+e.getLocalizedMessage());
                    redisOperations.discard();
                }

                return null;
            }
        });
    }

    @Override
    public Order deleteOrder(Order order) {
        //Выполнить блок кода в одной транзакции для удаления заказа
        return (Order) this.redisService.getRedisTemplate().execute(new SessionCallback() {
            @Override
            public Object execute(RedisOperations redisOperations) throws DataAccessException {

                try {
                    redisOperations.multi();
                        //1. Удалить заказ для userkod.
                        redisOperations.opsForZSet().remove(KeyFormatter.hideHyphenChar(order.getUserkod()), orderJsonConverter.convertObjectToJson(order));
                        //2. Удалить заказ для региона.
                        redisOperations.opsForZSet().remove(topic.getTopic(order), orderJsonConverter.convertObjectToJson(order));

                        order.setStatus(Order.STATUS.REJECTED);
                        //3. Отправить в канал websocket уведомление о смене статуса заказа (Заказ отменен)
                        orderEventPublisher.publishOrderEvent(topic.getTopic(order), orderJsonConverter.convertObjectToJson(order));

                    redisOperations.exec();

                } catch (Exception e) {
                    log.error("publishOrder error:"+e.getLocalizedMessage());
                    redisOperations.discard();
                }

                return order;
            }
        });


    }

}
