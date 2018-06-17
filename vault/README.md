# Vault Application

This is a sample application providing a basic transaction system between nodes.
It is fully dependent of the [backend subproject](../backend).

## Configuration

Configuration of this project can be done either through the [provided configuration file](src/main/resources/application.conf) or through command line arguments.

### application.conf
```console
# A sample configuration
vault.node-ids: ["alice", "bob", "carol"]
vault.web-socket-route: "ws://localhost:59234/events"
vault.node-id-route: "http://localhost:59234/unique"
vault.log-route: "http://localhost:59234/log"
vault.benchmark: {type: Random}
vault.log-file: "vault.log"
```

- `node-ids` A non-empty list of node identifiers.
- `web-socket-route` The route of the WebSocket exchange.
- `node-id-route` The route of where the identifier of the current node can be retrieved.
- `log-route` The route where the log of the current node can be retrieved.
- `benchmark`(optional) The name of the benchmark to run in the format `type: benchmarkname`. Can be one of the following values :
    - lonesender
    - random
    - localroundrobin
    - roundrobin
- `log-file` The name or path of a file to which logging information will be written.


### Command line arguments 
> Note: Providing a list of nodes in the command line will override the values from the configuration file

When running the program, arguments can be given in the following format :

```console
nodeOffset [nodeA nodeB ...]
```

Where `nodeOffset` is the offset of the node in the given list of nodes and `nodeA nodeB ...` is the list of nodes overriding the value configuration value `node-ids`.

For example using `sbt run` :
```console
# This will launch a node with id `alice`
sbt "vault/run 0 alice bob"
```

> Important : for running a single node, provide some dummy value in the list of nodes and use id 0.

## Running

Running the application is done the following way

```console
sbt "vault/run nodeId [nodeA nodeB ...]"
```

### Frontend details

A frontend is automatically launched when starting a node instance. It is accessible at `http://localhost/PORT` where `PORT` is the result of the computation `8080 + nodeOffset`.