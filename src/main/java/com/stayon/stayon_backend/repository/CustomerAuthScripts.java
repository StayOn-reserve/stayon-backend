package com.stayon.stayon_backend.repository;

/** All keys share a Redis Cluster hash tag so each script runs on a single slot. */
final class CustomerAuthScripts {
    private CustomerAuthScripts() { }

    static final String RATE = """
            for i,key in ipairs(KEYS) do
                if tonumber(redis.call('GET', key) or '0') >= tonumber(ARGV[(i-1)*2+1]) then return 0 end
            end
            for i,key in ipairs(KEYS) do
                if redis.call('INCR', key) == 1 then redis.call('EXPIRE', key, ARGV[(i-1)*2+2]) end
            end
            return 1
            """;

    // KEYS: challenge, active, reservation cooldown, phone cooldown, r-hour, r-day, p-hour, p-day.
    static final String BEGIN = """
            if redis.call('EXISTS', KEYS[3], KEYS[4]) > 0 then return 0 end
            local limits = {5,10,5,10}
            local ttl = {3600,86400,3600,86400}
            for i=5,8 do
                if tonumber(redis.call('GET', KEYS[i]) or '0') >= limits[i-4] then return 0 end
            end
            for i=5,8 do
                if redis.call('INCR', KEYS[i]) == 1 then redis.call('EXPIRE', KEYS[i], ttl[i-4]) end
            end
            local previous = redis.call('GET', KEYS[2])
            if previous then redis.call('DEL', previous) end
            redis.call('HSET', KEYS[1], 'id', ARGV[1], 'reservation', ARGV[2], 'phone', ARGV[3],
                'code', ARGV[4], 'identity', ARGV[5], 'status', 'PENDING', 'attempts', 0)
            redis.call('EXPIRE', KEYS[1], 180)
            redis.call('SET', KEYS[2], KEYS[1], 'EX', 180)
            redis.call('SET', KEYS[3], '1', 'EX', 60)
            redis.call('SET', KEYS[4], '1', 'EX', 60)
            return 1
            """;

    // KEYS: challenge, active. A delayed sender cannot revive an expired/replaced challenge.
    static final String SENT = """
            if redis.call('GET', KEYS[2]) == KEYS[1]
                and redis.call('HGET', KEYS[1], 'status') == 'PENDING' then
                redis.call('HSET', KEYS[1], 'status', 'READY')
            end
            return 1
            """;

    static final String INVALIDATE = """
            if redis.call('GET', KEYS[2]) == KEYS[1] then redis.call('DEL', KEYS[2]) end
            redis.call('DEL', KEYS[1])
            return 1
            """;

    // KEYS: challenge, active, cumulative phone failures, cumulative reservation failures, new session.
    static final String CONSUME = """
            if redis.call('GET', KEYS[2]) ~= KEYS[1]
                or redis.call('HGET', KEYS[1], 'status') ~= 'READY' then return 0 end
            if tonumber(redis.call('HGET', KEYS[1], 'attempts') or '5') >= 5 then return 0 end
            for i=3,4 do
                if tonumber(redis.call('GET', KEYS[i]) or '0') >= 5 then return 0 end
            end
            redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if redis.call('HGET', KEYS[1], 'code') ~= ARGV[1] then
                for i=3,4 do
                    if redis.call('INCR', KEYS[i]) == 1 then redis.call('EXPIRE', KEYS[i], 3600) end
                end
                return 0
            end
            if redis.call('HGET', KEYS[1], 'reservation') ~= ARGV[3]
                or redis.call('HGET', KEYS[1], 'phone') ~= ARGV[4] then return 0 end
            if redis.call('EXISTS', KEYS[5]) == 1 then return 0 end
            redis.call('HSET', KEYS[5], 'id', ARGV[2], 'reservation', ARGV[3], 'phone', ARGV[4],
                'scope', ARGV[5], 'expires', ARGV[6])
            redis.call('EXPIRE', KEYS[5], 900)
            redis.call('DEL', KEYS[1], KEYS[2])
            return 1
            """;
}
