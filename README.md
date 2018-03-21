# Gossip-based applications

## Requirements

- sbt build tool [available here](https://www.scala-sbt.org/1.x/docs/Setup.html)
- A working postgresql instance [downloadable here](https://www.postgresql.org/download/)

## Project setup

Start by cloning this repository : 
```console
git clone https://github.com/Tattletales/chip
```

Then navigate to the project's root directory :
```console
cd /path/to/chip
```

Start sbt to setup dependencies and compile the application (this will take some time as dependencies need to be downloaded):
```console
sbt compile
```

In order to be able to run the application you will need to edit the database credentials. In order to do that, please edit the file [Chip.scala](app/jvm/src/main/scala/chip/Chip.scala)

## Gossip daemon

The application expects an HTTP server to be running with the following configuration :

### Server configuration
- Address: `localhost`
- Port: `59234`

### HTTP Requests
- `GET /unique`
    - Retrieve a unique ID referencing the current node
- `POST /gossip/$type` with payload m in JSON format
    - Send a message `m` of type `$type` to every active node

### Server Sent Events

In order to be notified of new messages, the application interacts with the gossip daemon through [Server Sent Events](https://html.spec.whatwg.org/multipage/server-sent-events.html) (SSE).
The channel used by the application is `/events`.

#### SSE Fields

- `id`: the id of the event in the network
- `event`: the string value of `$type` parameter received through `POST /gossip/$type`
- `data`: the original payload received through `POST /gossip/$type`


## Chip
A Tweeter-like application. See [here](app/jvm/src/main/scala/chip) for a specific README detailing the application specific API.

## Vault
A bank account application. See [here](app/jvm/src/main/scala/vault) for a specific README detailing the application API.