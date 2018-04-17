#ifndef PHD_H
#define PHD_H

#define POST_ERROR_OK 0
#define POST_ERROR_TYPE_SIZE 1
#define POST_ERROR_DATA_SIZE 2

// Port to be used when run as a stand-alone program.
// When used as a library, the port is an initialization parameter.
#define PORT 59234

#define POST_PROCESSOR_BUFFER_SIZE 32768

#define MAX_OP_ID_STR_SIZE 1024lu

#define MAX_OP_TYPE_SIZE 32lu

// This + 2 will be used as the chunk size for the MHD event sender.
#define MAX_OP_DATA_SIZE 4094lu

// This is most likely to be used for the "max" argument given to the
// event sender. However, MHD might still decide to use a smaller
// value.
#define PREFERRED_SSE_CHUNK_SIZE (MAX_OP_DATA_SIZE + 2)

// MIME type of the response to the GET request expecting an SSE
// stream. Should be "text/event-stream". Use other MIME types only
// for debugging.
#define SSE_MIME_TYPE "text/event-stream"

// Function to be called on operation submission.
//
//    seq_nr: Sequence number of the operation
//      data: Operation payload. This is expected to be freed by the
//            callback fater use.
// data_size: size (in bytes) of the buffer data points to
//
// Returns a null-terminated string to be sent as a response to the
// application's HTTP POST request. free() will be called on the
// returned pointer after use.
typedef char* (*on_submit_callback_t)(unsigned int seq_nr, char* data, size_t data_size);

// Starts the HTTP server.
//               port: Port to listen on.
//            own_key: Own public key to give to the application when
//                     it asks for it (null-terminated string).
// on_submit_callback: Function to be called when an operation is
//                     submitted by the application.
void* phd_start(int port, char* own_key, on_submit_callback_t on_submit_callback);

// Stops the HTTP server and performs cleanup.
void phd_stop(void* daemon);

// Push an accepted operation to the application.
int push_event(char* pub_key, unsigned int seq_nr, char* data, size_t size);

// Sets the on_submit callback that will be called whenever an
// operation is submitted by the application.
void on_submit(on_submit_callback_t callback);

// Tell the library the own ID (public key) as a null-terminated
// string. The library copies it to its internal buffer, so that the
// memory pointed to by pub_key can be freed after set_own_pub_key
// returns.
int set_own_pub_key(char* pub_key);

// This function can be used as a parameter for on_submit. It
// immediately triggers an event notifying the application about the
// operation it just submitted and returns "OK" to the POST request.
char* echo_op_handler(unsigned int seq_nr, char* data, size_t data_size);

#endif

