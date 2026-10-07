-- Moves delayed job ids that are due onto the ready list, atomically.
-- KEYS[1] = delayed zset, KEYS[2] = ready list
-- ARGV[1] = now (epoch millis), ARGV[2] = max ids to move
-- Redis runs a script as a single uninterrupted unit, so two callers can never move the same id.
local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
for _, id in ipairs(due) do
    redis.call('ZREM', KEYS[1], id)
    redis.call('LPUSH', KEYS[2], id)
end
return #due
