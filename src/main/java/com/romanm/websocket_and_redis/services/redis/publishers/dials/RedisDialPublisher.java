package com.romanm.websocket_and_redis.services.redis.publishers.dials;

import com.romanm.websocket_and_redis.models.dials.Dial;

public interface RedisDialPublisher {
    /**
     * * Публикация сделки
     * @param dial Dial
     * @return Dial
     */
    Dial publishDial(Dial dial);
}
