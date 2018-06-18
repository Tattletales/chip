Poseidon HTTP Daemon (PHD) implements an interface between Poseidon
and its applications. It is meant to be used as a library used by
Poseidon. It implements an HTTP server that receives operations from
applications through POST requests and notifies applications about
accepted operations through Server Sent Events.

PHD is built on top of libmicrohttpd that needs to be installed:

sudo apt-get install libmicrohttpd-dev

When building, the linker needs to receive the option -lmicrohttpd :

gcc -o phd -g phd.c -lmicrohttpd

The program contains a main function for testing too, so it can be
used as a stand-alone program.

To test the deamon in the command line : 

curl -d "t=Bero&d=Ceco" -X POST http://localhost:59234/

To debug it is also possible to access localhost:59234/form
