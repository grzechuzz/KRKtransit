#!/bin/sh
set -eu

acl_file=/run/redis/users.acl
handshake='+hello +client|setname +client|setinfo'
observations='krktransit:realtime:observations:v1:*'

umask 077
cat > "$acl_file" <<ACL
user default off
user admin on >$REDIS_ADMIN_PASSWORD ~* &* +@all
user ingestion on >$REDIS_INGESTION_PASSWORD resetchannels -@all ~$observations +ping +xadd $handshake
user stop-events on >$REDIS_STOP_EVENTS_PASSWORD resetchannels -@all ~krktransit:stop-events:* %R~$observations $handshake +ping +xread +xinfo|stream +get +set +hgetall +hset +del +expire +multi +exec +eval +time +hget +hdel +zscore +zadd +zrangebyscore +zremrangebyscore +zcard +zrange +zrem +pttl +pexpire
user live-vehicles on >$REDIS_LIVE_VEHICLES_PASSWORD resetchannels -@all %R~$observations +time +xread $handshake
ACL

exec redis-server --aclfile "$acl_file" --maxmemory 1gb --maxmemory-policy noeviction --appendonly no --save ''
