# Gossip Server
This module mocks a gossip system. 
It simulates all the daemons that run locally in a single server.
As a result, to know which daemon is sending an event there identifiers are encoded in the routes.

In order to simulate the latencies in the system, the system waits a random amount of time (between 0ms and 500ms) before
gossiping to every node and logging it. Warning: the `serverSentEvent` interpreter does not simulate latencies. 
Only the `webSocket` interpreter does.

The adress of the server is `localhost:59234`.

## Configuration
The identifiers of the nodes in the system have to be known in advance.
Therefore, they have to provided before running the server.

#### Configuration file
They can be provided in two ways: via program arguments or through the [configuration file](src/main/resources/application.conf).
It should be formatted as follows: `server.node-ids: ["alice", "bob", "carol"]`

#### Program arguments
It is also possible to pass them via program arguments. 
When this is the case the configuration file is ignored.

## Running
Use `sbt "run gossipServer [nodeA ... nodeB]"` to compile and run the module.