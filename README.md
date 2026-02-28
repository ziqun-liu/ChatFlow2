## I. Local development

Defaults in `config.properties` are used. Rename `config.properties.example`
to `config.properties`.

#### Start RabbitMQ

```bash
# Do this only at the first time of development configuring
# Docker
docker run -d --name rabbitmq \
  -p 5672:5672 -p 15672:15672 \
  rabbitmq:3-management

# Use browser to open http://localhost:15672
# username/password: guest/guest
```

#### Setup queues

```bash
# local, run this comman in ONE line
RABBITMQ_HOST=localhost RABBITMQ_USER=guest RABBITMQ_PASS=guest ./deployment/rabbitmq-setup.sh
```

#### Start server-v2

```bash
export TOMCAT_DIRECTORY=~/Library/Tomcat
```

```bash
# local ChatFlow2/
cd server-v2/
mvn clean package
```

```bash
mv target/server-v2-1.0-SNAPSHOT.war target/server.war
cp target/server.war $TOMCAT_DIRECTORY/webapps/
```

```bash
$TOMCAT_DIRECTORY/bin/startup.sh
```

```bash
# Verify running
curl http://localhost:8080/server/health
```

#### Start consumer

```bash
# local ChatFlow2/
cd consumer
mvn clean package
```

```bash
java -jar ./target/consumer-1.0-SNAPSHOT.jar
```

#### Start client

```bash
cd client
mvn clean package
```

```bash
java -jar ./target/client-1.0-SNAPSHOT.jar
```

---

## II. Cloud developement

Environment variables are managed automatically by deployment scripts.
Before running deployment scripts, update the variable values in `deployment/deploy-server.sh`
and `deployment/consumer-setup.sh`. Then run the following shell scripts.

```bash
chmod +x deployment/deploy-all.sh
```

```bash
./deployment/deploy-all.sh
```

| Variable         | Used by             | Description                         |
|------------------|---------------------|-------------------------------------|
| RABBITMQ_HOST    | server-v2, consumer | RabbitMQ EC2 private IP             |
| RABBITMQ_USER    | server-v2, consumer | RabbitMQ username                   |
| RABBITMQ_PASS    | server-v2, consumer | RabbitMQ password                   |
| SERVER_ID        | server-v2           | Instance identifier (server-1~4)    |
| SERVER_URLS      | consumer            | Comma-separated server-v2 base URLs |
| CONSUMER_THREADS | consumer            | Number of consumer threads          |
| WS_URI           | client              | WebSocket server URL                |

# Architecture

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                           Load Test Client                              │
│                                                                         │
│   MessageGenerator → LinkedBlockingQueue → SenderWorker × N            │
│                                                │                        │
│                                           WebSocket                     │
└────────────────────────────────────────────────│────────────────────────┘
                                                 │
                                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      AWS Application Load Balancer                      │
└────────┬─────────────────┬─────────────────┬─────────────────┬──────────┘
         │                 │                 │                 │
         ▼                 ▼                 ▼                 ▼
  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
  │  server-v2  │   │  server-v2  │   │  server-v2  │   │  server-v2  │
  │  (EC2-A1)   │   │  (EC2-A2)   │   │  (EC2-A3)   │   │  (EC2-A4)   │
  │             │   │             │   │             │   │             │
  │ ServerEndpt │   │ ServerEndpt │   │ ServerEndpt │   │ ServerEndpt │
  │ ChannelPool │   │ ChannelPool │   │ ChannelPool │   │ ChannelPool │
  │ MsgPublshr  │   │ MsgPublshr  │   │ MsgPublshr  │   │ MsgPublshr  │
  │ BroadcastSv │   │ BroadcastSv │   │ BroadcastSv │   │ BroadcastSv │
  └──────┬──────┘   └──────┬──────┘   └──────┬──────┘   └──────┬──────┘
         │                 │                 │                 │
         └─────────────────┴─────────────────┴─────────────────┘
                                     │ publish
                                     │ routing key: room.{roomId}
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          RabbitMQ (EC2-B)                               │
│                                                                         │
│   Exchange: chat.exchange (topic)                                       │
│                                                                         │
│   room.1  room.2  room.3  ...  room.20                                  │
│   [████]  [████]  [████]  ...  [████]                                   │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │ consume
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          consumer (EC2-C)                               │
│                                                                         │
│   ConsumerMain                                                          │
│       └── ConsumerPool                                                  │
│               ├── RoomConsumer thread (room.1  ~ room.5 )               │
│               ├── RoomConsumer thread (room.6  ~ room.10)               │
│               ├── RoomConsumer thread (room.11 ~ room.15)               │
│               └── RoomConsumer thread (room.16 ~ room.20)               │
│                         │                                               │
│                         ▼                                               │
│               ServerNotifier                                            │
│               HTTP POST /broadcast to all server-v2 instances           │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │ HTTP POST /broadcast
                                  ▼
  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
  │  server-v2  │   │  server-v2  │   │  server-v2  │   │  server-v2  │
  │  (EC2-A1)   │   │  (EC2-A2)   │   │  (EC2-A3)   │   │  (EC2-A4)   │
  │             │   │             │   │             │   │             │
  │ BroadcastSv │   │ BroadcastSv │   │ BroadcastSv │   │ BroadcastSv │
  │ rooms map   │   │ rooms map   │   │ rooms map   │   │ rooms map   │
  └──────┬──────┘   └──────┬──────┘   └──────┬──────┘   └──────┬──────┘
         │                 │                 │                 │
         └─────────────────┴─────────────────┴─────────────────┘
                                     │ WebSocket broadcast
                                     │ (only to sessions held by each instance)
                                     ▼
                          WebSocket Clients (chat users)
```
