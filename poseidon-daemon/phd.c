#include <sys/types.h>
#ifndef _WIN32
#include <sys/select.h>
#include <sys/socket.h>
#else
#include <winsock2.h>
#endif
#include <string.h>
#include <microhttpd.h>
#include <stdio.h>

#include <stdlib.h>
#include <unistd.h>
#include <string.h>

#include "phd.h"

// ======================================================================
// DEBUG
// ======================================================================

static char *response_form = "<html><body><form method=\"POST\"><input type=\"text\" name=\"t\" /><input type=\"text\" name=\"d\" /><input type=\"submit\" value=\"go\"></form></body></html>";

// ======================================================================
// Static types and data structures
// ======================================================================

// Structure holding the state of a POST request being processed.
// The POST request contains an operation to be submitted.
typedef struct {
    struct MHD_PostProcessor* post_processor; // HMD's data processor
    char t[MAX_OP_TYPE_SIZE+1]; // Operation type (null-terminated string)
    char d[MAX_OP_DATA_SIZE+1]; // Operation data (null-terminated string)
    int error;
} req_state_t;

typedef struct {
    int new_event;
    char id[MAX_OP_ID_STR_SIZE];
    char* type;
    char* data;
} event_source_t;

static on_submit_callback_t on_submit_callback = NULL;

static char* own_pub_key = "";

static unsigned int seq_nr = 0;

static event_source_t event_source = {0, "", NULL, NULL};

// ======================================================================
// Static functions
// ======================================================================

// Send response data to the given connection.
static int send_response_data(struct MHD_Connection* connection,
                              unsigned int http_status_code,
                              void* data,
                              size_t size,
                              enum MHD_ResponseMemoryMode mode) {

    // Create response from string, enqueue it and destroy the response object.
    struct MHD_Response* response = MHD_create_response_from_buffer(size,
                                                                    data,
                                                                    mode);
    int ret = MHD_queue_response (connection, http_status_code, response);
    MHD_destroy_response (response);

    // Print output about success or failure
    if(ret == MHD_YES) {
        printf("Success.\n");
    }
    else if(ret == MHD_NO) {
        printf("Failure.\n");
    }
    else {
        printf("Unknown return value: %d\n", ret);
    }
    return ret;
}

// Send null-terminated string as response data.
static int send_static_string(struct MHD_Connection* connection,
                              char* data,
                              unsigned int http_status_code) {
    return send_response_data(connection,
                              http_status_code,
                              (void*)data,
                              strlen(data),
                              MHD_RESPMEM_PERSISTENT);
}

// Send null-terminated string as response data.
static int send_dynamic_string_free(struct MHD_Connection* connection,
                                    char* data,
                                    unsigned int http_status_code) {
    return send_response_data(connection,
                              http_status_code,
                              (void*)data,
                              strlen(data),
                              MHD_RESPMEM_MUST_FREE);
}

// Function called by MHD when parsing an operation received through a
// POST request.
static int posted_op_iterator(void* cls,
                              enum MHD_ValueKind kind,
                              const char* key,
                              const char* filename,
                              const char* content_type,
                              const char* transfer_encoding,
                              const char* data,
                              unsigned long int off,
                              size_t size) {

    // Get the structure containing data corresponding the POST request being parsed.
    req_state_t* req_state = (req_state_t*)cls;

    // DEBUG
    /* printf("Iterating over POST data...\n"); */
    /* printf("  key: %s\n", key); */
    /* printf("  data size: %lu\n", size); */
    /* printf("  offset: %lu\n", off); */

    // Save operation type
    if(strcmp(key, "t") == 0) {
        if(size > 0) {
            // Check if type string is not too long
            if(off+size <= MAX_OP_TYPE_SIZE) {
                // Copy received data into request state object
                memcpy(req_state->t+off, data, size);
                req_state->t[off+size] = '\0'; // Add a null byte to terminate the string.
            }
            else {
                fprintf(stderr, "POSTed value of size %lu+%lu exceeds MAX_OP_TYPE_SIZE of %lu.\n", off, size, MAX_OP_TYPE_SIZE);
                req_state->error = POST_ERROR_TYPE_SIZE;
                return MHD_NO;
            }
        }
    }
    // Save operation data
    else if(strcmp(key, "d") == 0) {
        if(size > 0) {
            // Check if data string is not too long
            if(off+size <= MAX_OP_DATA_SIZE) {
                memcpy(req_state->d+off, data, size);
                req_state->d[off+size] = '\0'; // Add a null byte to terminate the string.
            }
            else {
                fprintf(stderr, "POSTed value of size %lu+%lu exceeds MAX_OP_DATA_SIZE of %lu.\n", off, size, MAX_OP_DATA_SIZE);
                req_state->error = POST_ERROR_DATA_SIZE;
                return MHD_NO;
            }
        }
    }
    else {
        fprintf(stderr, "POSTed unknown key %s. Ignoring.\n", key);
    }

    return MHD_YES;
}

// Callback provided to MHD for providing data to a response. On each
// call, waits until a new event is ready and gives it to MHD.
static long int event_sender(void* cls,
                             long unsigned int pos,
                             char* buf,
                             long unsigned int max) {

    event_source_t* es = (event_source_t*)cls;

    // If the new_event flag is set, push the event.
    if(es->new_event) {
        
        printf("Received new event: %s with id: %s\n", es->type, es->id);

        // Write event into buffer in SSE format and clear the new_event flag.
        // TODO: Make sure this is OK with respect to
        // synchronization. Either use some memory barriers, or a
        // wait-notify pattern.
        int printed = snprintf(buf, max, "id: %s\r\nevent: %s\r\ndata: %s\r\n",
                               es->id,
                               es->type,
                               es->data);
        es->new_event = 0;

        // Bail out if event does not fit into buffer.
        // Note the ">" and not ">=". The null byte is not relevant here.
        if(printed > max) {
            // If event data does not fit into buffer, return ERROR.
            fprintf(stderr,
                    "Failed to push event. Event size of %d too big for buffer of %lu.\n",
                    printed, max);
            return MHD_CONTENT_READER_END_WITH_ERROR;
        }
        else {
            return printed;
        }
    }
    else {
        return 0;
    }
}

static int start_event_sender(struct MHD_Connection *connection) {
    struct MHD_Response *response;

    printf("Registering SSE event sender.\n");

    // Setister event sender as callback that MHD calls for obtaining response data.
    response = MHD_create_response_from_callback(-1,
                                                 PREFERRED_SSE_CHUNK_SIZE,
                                                 event_sender,
                                                 &event_source,
                                                 NULL);
    // Set MIME type of response.
    MHD_add_response_header(response, "Content-Type", SSE_MIME_TYPE);

    // Enqueue response object.
    int ret = MHD_queue_response (connection, MHD_HTTP_OK, response);

    // Destroy the response object. Note that It will not be
    // physically destroyed here, as the MHD queue will still
    // bereferencing it (MHD keeps a reference counter for the
    // response).
    MHD_destroy_response(response);

    return ret;
}

// Initializes the state of a freshly received POST request.
// Returns MHD_YES on success, MHD_NO on failure
static int initialize_post_request(struct MHD_Connection *connection,
                            void** con_cls) {
    // Allocate memory.
    req_state_t* req_state = (req_state_t*)malloc(sizeof(req_state_t));
    if(req_state == NULL) {
        fprintf(stderr, "Memory allocation error while processing POST request.\n");
        return MHD_NO;
    }
    
    // Initialize request state object, creating the post processor.
    memset(req_state, 0, sizeof(req_state_t));
    req_state->error = POST_ERROR_OK;
    req_state->post_processor = MHD_create_post_processor(connection,
                                                          POST_PROCESSOR_BUFFER_SIZE,
                                                          posted_op_iterator,
                                                          (void*)req_state);
    if(req_state->post_processor == NULL) {
        free(req_state);
        return MHD_NO;
    }
    
    // Save request state to be used across calls to the handler and return.
    *con_cls = (void*)req_state;
    return MHD_YES;
}

static int parse_post_request(struct MHD_Connection* connection,
                          void** con_cls,
                          const char *upload_data,
                          size_t *upload_data_size) {

    // Get request state carried over by MHD
    req_state_t* req_state = (req_state_t*)*con_cls;

    // If there is any uploaded data available, parse it using the MHD post processor.
    if(*upload_data_size != 0) {
        MHD_post_process(req_state->post_processor, upload_data, *upload_data_size);
        *upload_data_size = 0;
        return MHD_YES;
    }
    else {
        fprintf(stderr, "No POST data to populate post request. This should never happen!\n");
        return MHD_NO;
    }
}

// Returns a pointer to a freshly allocated buffer of data
// representing the posted operation, and writes its size to the
// location pointed to data_size. This buffer is to be passed to
// Poseidon.
// Returns NULL on error (if memory allocation fails).
static char* serialize_request(req_state_t* req_state, size_t* data_size) {
    // Get sizes of operation type and data strings.
    size_t lt = strlen(req_state->t) + 1; // +1 for terminating null character
    size_t ld = strlen(req_state->d) + 1; // +1 for terminating null character

    // Allocate memory for serialized operation
    char* data = malloc(lt + ld);
    if(data != NULL) {
        // Copy operation type and data into allocated buffer
        strcpy(data, req_state->t);
        strcpy(data+lt, req_state->d);
        // Set buffer size
        *data_size = lt + ld;
    }
    
    return data;
}

static int process_post_request(struct MHD_Connection* connection,
                         void** con_cls) {
    int ret;

    // Get request state carried over by MHD
    req_state_t* req_state = (req_state_t*)*con_cls;

    // If an error occured while parsing the HTTP POST request
    if(req_state->error != POST_ERROR_OK) {
        fprintf(stderr, "Not processing erroneous request. Error code: %d\n", req_state->error);
        if(req_state->error == POST_ERROR_TYPE_SIZE || req_state->error == POST_ERROR_DATA_SIZE) {
            ret = send_static_string(connection, "ERROR", 413); // HTTP 413: Payload too large
        }
        else {
            ret = send_static_string(connection, "ERROR", 500); // HTTP 500: Internal server error
        }
    }
    // HTTP POST request parsed correctly, but no callback set to process its data
    else if(on_submit_callback == NULL) {
        fprintf(stderr, "Ignoring submitted operation. No 'on submit' callback set.\n");
        ret = send_static_string(connection, "IGNORE\n", MHD_HTTP_OK);
    }
    // POST data parsed correctly and callback set
    else {

        // Serialize operation (consisting of type and payload) to a single buffer.
        size_t data_size = 0;
        char* data = serialize_request(req_state, &data_size);
        if(data == NULL) {
            fprintf(stderr, "Failed to serialize POSTed request.\n");
            ret = send_static_string(connection, "ERROR", 500); // HTTP 500: Internal server error
        }
        // Pass serialized data to the lower layer through a callback.
        else {
            // ATTENTION: data is expected to be freed by the callback!
            char* result = on_submit_callback(seq_nr, data, data_size);
            seq_nr++;
            // TODO: Consider other options thatn
            // send_dynamic_string_free(), which implies a call to
            // malloc() and free() on every operation submission. As
            // the result might be the same most of the time, this is
            // a potential waste of time.
            ret = send_dynamic_string_free(connection, result, MHD_HTTP_OK);
        }
    }

    // Request state no longer needed.
    free(req_state);

    return ret;
}

static int answer_to_connection (void *cls, struct MHD_Connection *connection,
                                 const char *url, const char *method,
                                 const char *version, const char *upload_data,
                                 size_t *upload_data_size, void **con_cls) {
  /* struct MHD_Response *response; */
  int ret;
  
  printf("Processing request: %s\n", version);

  // Handle get request
  if(strcmp(method, "GET") == 0) {
      printf("GET %s\n", url);

      if(strcmp(url, "/form") == 0) { // DEBUG
          ret = send_static_string(connection, response_form, MHD_HTTP_OK);
      }
      else if(strcmp(url, "/unique") == 0) {
          ret = send_static_string(connection, own_pub_key, MHD_HTTP_OK);
      }
      else if(strcmp(url, "/events") == 0) {
          ret = start_event_sender(connection);
      }
      else {
          fprintf(stderr, "URL not supported: %s\n", url);
          ret = send_static_string(connection, "404 NOT FOUND", MHD_HTTP_NOT_FOUND);
      }
  }
  else if(strcmp(method, "POST") == 0) {
      printf("POST (%lu) %s\n", *upload_data_size, url);

      // If this is the first call to the handler, initialize the request state.
      if(*con_cls == NULL) {
          return initialize_post_request(connection, con_cls);
      }
      // If this POST request is already initialized, parse its data if available.
      else if(*upload_data_size != 0) {
          return parse_post_request(connection, con_cls, upload_data, upload_data_size);
      }
      // If POST request is initialized but there is no more data,
      // this means that all data has been parsed.
      else {
          return process_post_request(connection, con_cls);
      }
  }
  else {
      printf("Unsupported HTTP method: %s\n", method);
      return MHD_NO;
  }

  return ret;
}

// ======================================================================
// Exported functions
// ======================================================================
 
int phd_set_own_pub_key(char* pub_key) {
    // Allocate own buffer for own key.
    own_pub_key = (char*)malloc(strlen(pub_key) + 1);
    if(own_pub_key == NULL) {
        return 0;
    }
    // Copy provided key to own buffer.
    else {
        strcpy(own_pub_key, pub_key);
        return 1;
    }
}

int phd_push_event(char* pub_key, unsigned int seq_nr, char* data, size_t size) {
    printf("Pushing event.\n");
    
    // Wait until previous event has been sent to the application.
    while(event_source.new_event);

    // Try to print operation ID to event source object.
    int printed = snprintf(event_source.id, MAX_OP_ID_STR_SIZE, "%s-%u", pub_key, seq_nr);

    // Bail out if buffer too small.
    if(printed >= MAX_OP_ID_STR_SIZE) {
        fprintf(stderr,
                "Failed to push event. Operation ID of size %d too large for buffer of %lu\n",
                printed,
                MAX_OP_ID_STR_SIZE);
        return 0; // Failure
    }
    
    // Put pointers to data into event source object.
    // data points to two null-terminated strings: type and data (payload)
    event_source.type = data;
    event_source.data = data+strlen(data) + 1;

    // Set the new_event flag, notifying the event sender about the
    // new event and wait until the new event has been sent to the application.
    // TODO: Make sure this is OK with respect to
    // synchronization. Either use some memory barriers, or a
    // wait-notify pattern.
    event_source.new_event = 1;

    return 1; // Success
}

void phd_on_submit(on_submit_callback_t callback) {
    on_submit_callback = callback;
}

char* phd_echo_op_handler(unsigned int seq_nr, char* data, size_t data_size) {
    phd_push_event(own_pub_key, seq_nr, data, data_size);

    char* resp = (char*)malloc(strlen("OK\r\n") + 1);
    strcpy(resp, "OK\r\n");
    return resp;
}

void* phd_start(int port, char* own_key, on_submit_callback_t on_submit_callback) {
    phd_set_own_pub_key(own_key);
    
    phd_on_submit(on_submit_callback);
    
    struct MHD_Daemon* daemon = MHD_start_daemon (MHD_USE_SELECT_INTERNALLY, PORT, NULL, NULL,
                                                  &answer_to_connection, NULL, MHD_OPTION_END);
    return daemon;
}

void phd_stop(void* daemon) {
    MHD_stop_daemon((struct MHD_Daemon*)daemon);
}

// ======================================================================
// main()
// ======================================================================

int main(int argc, char** argv) {
    void* daemon;
    
    phd_start(PORT, "MyOwnKey", phd_echo_op_handler);
    
    while(1) {
        (void)getchar();
        char buffer[1024];
        sprintf(buffer, "SomeType");
        sprintf(buffer + strlen("SomeType") + 1, "SomeData");
        phd_push_event("SomePubKey", 42, buffer, strlen("SomeType SomeData "));
    }
    
    phd_stop(daemon);
    return 0;
}

