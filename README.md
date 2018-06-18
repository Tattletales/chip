# Gossip-based applications

## Requirements

This project makes use of the [sbt build tool](https://www.scala-sbt.org/1.x/docs/Setup.html).
Additional requirements may be specified for each subproject individually.

## Project setup

Start by cloning this repository : 
```console
git clone https://github.com/Tattletales/chip
```

Then navigate to the project's root directory :
```console
cd /path/to/repo
```

This repository is organized in different subprojects. You can obtain a list of them by running

```console
sbt projects
```

To run `sbt` commands for a specific project, switch to this project by running :
```console
sbt "project nameOfProject"
```
Where `nameOfProject` is one of the names output by `sbt projects`.

## Subprojects

### Chip
A Tweeter-like application. See [here](chip/README.md) for a specific README detailing the application specific API.

### Vault
A bank account application. See [here](vault/README.md) for a specific README detailing the application API.

### GossipServer
A mock implementation of a gossip server used for testing purposes. Details the expected configuration. See [here](gossipServer/README.md) for a specific README.