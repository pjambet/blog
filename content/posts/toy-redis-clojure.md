---
title: "A toy Redis Server, in Clojure"
date: 2023-12-29T18:36:30-05:00
lastmod: 2023-12-29T18:36:30-05:00
tags : [ "dev", "go", "tcp servers" ]
categories : [ "dev" ]
summary: "A toy Redis server in Clojure, responding to a small subset of commands."
layout: post
highlight: false
image: /images/clj-river.jpg
---

![???](/images/clj-river.jpg)

_This is the second entry in the "Building Toy Redises in X" series". In [the first article](/posts/toy-redis-go/) we used Go, in this one we'll use Clojure._

_One of the approaches explored in this article is very similar to the one used in Go, with the use of Channels, so reading it first might help._

{{% note %}}
I've never written Clojure code professionally, what you're about to read is the result of a slow and painful process of trial and error. Take everything with a grain of salt. And if you are an experienced Clojure developer and spot something outrageous, please reach out!
{{% /note %}}

## What we're building

We're building something very similar to what we built in the first article, you can check out the details [over there](/posts/toy-redis-go/#what-were-building) for more details

All the code in this post is in the `content/src/clj-tcp-server/` folder, available [on GitHub](https://github.com/pjambet/blog/tree/master/content/src/clj-tcp-server).

## Starting small, a TCP server and nothing else

One of the great things with Clojure is that you get access to everything that Java offers. So when it comes to starting a TCP server, we can use Java's [`ServerSocket` class][java-server-socket], instantiate it with the port number it should listen to, and use its [`accept` method][java-server-socket-accept] to accept new clients.

We get back a `Socket` instance, which we can conveniently use [`clojure.java.io/writer`][clj-java-writer] with, to get back an instance of `java.io.BufferedWriter`. With that writer instance, we can now write to the socket with the `write` method, and we need to call `flush`, to make sure the data gets written, and can be read on the other end.

Finally, we close the socket with `close`, because we'll deal with concurrency later in this post.

```clj
(ns tcp
  (:require [clojure.java.io :as io])
  (:import (java.net ServerSocket)))

(defn handle-client
  [client]
  (let [writer (io/writer client)]
    (.write writer "Hello 👋\n")
    (.flush writer)
    (.close client)))


(defn main
  []
  (with-open [server-socket (ServerSocket. 3000)]
     (loop []
       (let [client-socket (.accept server-socket)]
         (handle-client client-socket))
       (recur))))

(main)
```

You can run the program with `clj -M tcp.clj` and connect to it with `nc -v localhost 3000`.

## Keeping the connections open

Let's now make the server keep the connections open, wait for the clients to send _something_ over the wire, and respond back. We will use Clojure's `core/async` library to help with concurrency, for the main reason that I couldn't think of any other solutions.

`core/async` [shares a lot with Go's concurrency mechanisms][clj-and-go-sitting-in-a-tree] with coroutines, so if you read the previous entry in this series, this should all look pretty familiar.

{{% note %}}

If you're thinking "well, since we can use anything that exists in Java, we could use Java's non blocking IO library, java.nio, it's been available since Java 7". Well, you're probably right, I'm sure we _could_, but I will actually dedicate a whole article to that topic, about building a Toy Redis, in Java, using the nio package!

{{% /note %}}

Clojure lets us spin up new threads, which we could use to handle concurrent clients, but instead we'll use a higher level abstraction, `go` Blocks. If you've read the previous post, or are familiar with Go, this is very similar to coroutines created with the `go` keyword.

`core.async` provides the `go` macro, it asynchronously executes the body we give it. The following example starts a `go` block, prints immediately the first statement from the block, and then the one from the main thread, then sleeps for 5s and finally prints done:

```clj
(def sleep-time 5000)
(a/go
  (println (str "sleeping for " sleep-time "ms"))
  (Thread/sleep sleep-time)
  (println "done!"))
(println "Printing from main thread")
```

You can run this example from the REPL with `clj -Sdeps '{:deps {org.clojure/core.async {:mvn/version "1.6.681"}}}'` and then with:

```
Clojure 1.11.1
user=> (require '[clojure.core.async :as a])
nil
user=> (def sleep-time 5000)
#'user/sleep-time
user=> (a/go
  (println (str "sleeping for " sleep-time "ms"))
  (Thread/sleep sleep-time)
  (println "done!"))
(println "Printing from main thread")
#object[clojure.core.async.impl.channels.ManyToManyChannel 0x2b3ac26d "clojure.core.async.impl.channels.ManyToManyChannel@2b3ac26d"]
sleeping for 5000ms
user=> Printing from main thread
nil
user=> done!
```

We can now start a new go block for each new client, but first, let's require `core.async`:

```clj
(ns concurrent-server
  (:require [clojure.java.io :as io]
            [clojure.core.async :as a])
  (:import (java.net ServerSocket)))
;; ...
```

And we can now use `a/go`:

```clj
(defn handle-client
  [client]
  (a/go
    (loop []
      ;; ...
      )))
```

Now that each client is given its own go block, we can start reading from clients:

```clj
(loop []
  (let [request (.readLine (io/reader client))
        writer (io/writer client)]
    (do
      (.write writer "Hello 👋\n")
      (.flush writer)
      (recur))))
```

With `io/reader`, we get back an instance of `java.io.BufferedReader`, on which we can call the `.readLine` method, and get a `String` back. This change means that we now only write back to the client after we received something. In other words, the connection will stay open, and unused, until the client sends a line of text. After which it writes `"Hello 👋\n"` and waits for the next line of text.

There is an issue however, if the client disconnects before the server is stopped, an exception will be thrown, and uncaught, in the thread started by the go block. You can see this behavior by starting the server with: `clj -Sdeps '{:deps {org.clojure/core.async {:mvn/version "1.6.681"}}}' -M concurrent_server.clj`, connecting to it with `nc localhost 3000` from another terminal, and then closing the client with `Ctrl-C`. The following exception will show up in the server logs:

```
Exception in thread "async-dispatch-1" java.net.SocketException: Broken pipe
```

I didn't include the full stacktrace, but if you were to look at it, despite it being kinda cryptic, we can see that the error occurs when we attempt to write to the socket. We can prevent this by checking if the result of `.readLine` is `nil`, in which case, we can close the connection on the server's end, since the client is gone.

The following is the full version of `handle-client`:

```clj
(defn handle-client
  [client]
  (a/go
    (loop []
      (let [request (.readLine (io/reader client))
            writer (io/writer client)]
        (if (nil? request)
          (do
            (println "Nil response, closing client")
            (.close client))
          (do
            (.write writer "Hello 👋\n")
            (.flush writer)
            (recur)))))))
```

## Making the server stateful

The last step is to turn this whole thing stateful. We want the server to store data, so that other clients can read from it.

Clojure's collections are immutable, so we don't have that many options for our go blocks to share the same data structure to read and write on. In the previous chapter we initially tried an approach where we created a map in the `main` function and passed it to each coroutine, something like this:

```clj
(defn main
  []
  (with-open [server-socket (ServerSocket. 3000)]
    (loop []
      (let [client-socket (.accept server-socket)
            db {}]
        (handle-client client-socket db)
        (recur)))))
```

But then `handle-client` can't modify `db` in a way that would be seen by other go blocks. We can for instance add new entries to it:

```clj
(defn handle-client
  [client original-db]
  (a/go
    (loop [db original-db]
      (let [request (.readLine (io/reader client))
            writer (io/writer client)]
        (if (nil? request)
          (do
            (println "Nil response, closing client")
            (.close client))
          (let [updated-db (assoc db (.hashCode client) (System/currentTimeMillis))]
            (.write writer "Hello 👋\n")
            (.flush writer)
            (recur updated-db)))))))
```

In this example we store the timestamp of the most recent line we received from the client, where the key is the `.hashCode` value of the client. Yes, this is a very contrived example, the only purpose is to show that we have no easy way to make the updates made to the map visible to the outside. The `handle-client` function returns a channel, which we ignore, because we don't need it. Because we execute a go block, we cannot return data to the caller.

We will explore two approaches to address this issue:

- One very similar to the Go chapter, Channels!
- [Atoms](https://clojure.org/reference/atoms), clojure's builtin "[...] way to manage shared, synchronous, independent state"

### Channels

Let's start with the channel version, and we'll look at atoms next.

We'll create one channel in the main function, for all the go blocks to send the requests received from clients to a go block that will handle storing the data and send it back over a different channel, so that a response can be written back to the client. Let's see what it looks like, first the main function:

```clj
(defn main
  []
  (let [command-channel (a/chan)]
    (with-open [server-socket (ServerSocket. 3000)]
      (handle-db command-channel)
      (loop []
        (let [client-socket (.accept server-socket)]
          (handle-client client-socket command-channel)
          (recur))))))
```

You may have noticed that on top of passing `command-channel` to each call of `handle-client`, we also pass it to `handle-db`, before starting the loop. Let's take a look at that function:

```clj
(defn handle-db
  [command-channel]
  (a/go
    (loop [db {}]
      (let [resp (a/<! command-channel)
            timestamp (System/currentTimeMillis)
            updated-db (assoc db (.hashCode (:client resp)) timestamp)]
        (a/>! (:channel resp) timestamp)
        (recur updated-db)))))
```

The function runs entirely in a go block, and starts an infinite loop with a hash map. The first thing it does is wait for a message to be sent to the channel it was given as an argument. When it receives a message, it stores the current timestamp associated with the client that sent the message. It effectively stores the timestamp of the last time it processed a message sent by a client.

Then, it extracts the `:channel` field from the message, and write the timestamp back to it. Finally, it calls `recur` with the updated hash map, so that the next iteration sees the changes made to it.

Now, let's look at `handle-client`, how it sends messages to `command-channel`, and how it reads back what `handle-db` sends back after receiving the message:

```clj
(defn handle-client
  [client channel]
  (a/go
    (loop []
      (let [request (.readLine (io/reader client))
            writer (io/writer client)
            message {:channel (a/chan) :client client}] ;; (1)
        (if (nil? request)
          (do (println "Nil response, closing client")
              (.close client))
          (do (a/>! channel message) ;; (2)
              (let [result (a/<! (:channel message))] ;; (3)
                (.write writer (str "OK, " result "\n"))
                (.flush writer)
                (recur))))))))
```

Let's look at the three main changes:

- In `(1)`, we create a hash map, called `message`, with two keys, `:channel` is a newly built channel, so that `handle-db` can send back a message to us.
- In `(2)`, when we have a non-nil message, we send `message` to the the channel created in the `main` function.
- In `(3)`, we wait to get a response back from the `:channel` field of the message hash map, and store the result in the `result` variable. We then write back the content to the client, sending them the timestamp stored in the internal db.

This is another contrived example, storing the last timestamp of when we received _something_ from a client is not that useful. But we can use this architecture to build our Toy Redis!


## Putting everything together.

We want to support the following commands:

- `GET`: Accepts a string, and return the value stored for that key, if any
- `SET`: Accepts two strings, a key and a value, and sets the value for the key, overriding any values that may have been present
- `DEL`: Accepts a string and deletes the value that may have been there
- `INCR`: Accepts a single argument and increments the existing value. If the value is not an integer, it's an error, if there are no values, it gets initialized to `1`, resulting in an identical outcome as calling `SET <key> 1`.


#### Laying the foundations

For the final version of the server, the `main` function will look the same:

```clj
(defn main
  "Start a server and continuously wait for new clients to connect"
  []
  (println "About to start ...")
  (let [command-channel (a/chan)]
    (handle-db command-channel)
    (with-open [server-socket (ServerSocket. 3000)]
      (loop []
        (let [client-socket (.accept server-socket)]
          (handle-client client-socket command-channel))
        (recur)))))
```

We will however update the `handle-db` function to deal with the various commands:

```clj
(defn handle-db
  "Run a go block in which we continuously wait for clients to send commands,
  process them, and send back a response through teh channel included in the
  received hash map"
  [command-channel]
  (a/go (loop [db {}]
          (let [response (a/<! command-channel)
                command (response :command)
                key (response :key)
                value (response :value)
                chan-resp (response :resp)
                result (process-command db command key value)
                new-db (result :updated)
                response (result :response)]
            (a/>! chan-resp response)
            (recur new-db)))))
```

This is similar to what we looked at in the previous section, with more handling of the various elements of a command.

We start the same way, by creating a `go` block, in which we continuously loop over the hash map that will store all the server's data. What follows in the `let` call is a sequence of operations to process the command we received from the client through `command-channel`.

We pass the `command`, `key`, `value` & `chan-resp` variables to `process-command`, which we'll look at next, and we then extract two variables from whatever it returns, `new-db` & `response`. `new-db` is what we pass to `recur` to maintain the inner state of the database on the server. `response` is what we send back to the client through the `channel` that was included in the `request` hash map.

Let's now look at `process-command`:

```clj
(defn process-command
  "Perform various operations depending on the command sent by the client"
  [db command key value]
  (cond
    (= command :get)
    (if key
      (let [value (get db key "")]
        {:updated db :response value})
      {:updated db :response "ERR wrong number of arguments for 'get' command"})
    :else {:updated db :response "Unknown command"}))
```

To keep things short we've only included the handling of the `GET` command, we'll add more branches to that `cond` call later on.

With that `cond` call, we go over the handled commands, and fallback with the `Unknown command` error. Remember that whatever we return from the `process-command` function _must_ contain an `updated` field with the new data, and a `response` field, which will be sent back to the `go` block running for the client that sent the command.

The `GET` command does not modify the database, so we return it unchanged.

Before looking at the implementation of other commands, let's now look at the `handle-client` function, the one we call with each newly connected client in the `main` function:

```clj
(defn handle-client
  "Read from a connected client, and handles the various commands accepted by the server"
  [client-socket command-channel]
  (a/go (loop [resp-channel (a/chan)]
          (let [request (.readLine (io/reader client-socket))
                writer (io/writer client-socket)]
            (if (nil? request) ;; (1)
              (do
                (println "Nil request, closing")
                (a/close! resp-channel)
                (.close client-socket))
              (let [parts (string/split request #" ") ;; (2)
                    command (get parts 0)]
                (cond
                  (contains? valid-commands command)
                  (let [request (request-for-command command parts resp-channel)]
                    (a/>! command-channel request)
                    (let [value (a/<! resp-channel)]
                      (.write writer (str value "\n"))
                      (.flush writer)
                      (recur resp-channel)))
                  (= command "QUIT")
                  (do
                    (a/close! resp-channel)
                    (.close client-socket))
                  :else (do
                          (println "Unknown request:" request)
                          (recur resp-channel)))))))))
```

There's a lot happening in there, so let's break it down. We first start a `go` block, to make sure we do not block the main thread where the `main` function waits for new clients to connect.

We then loop with a newly created channel, `resp-channel`. The purpose of this channel is for the `handle-db` function to send back a response after processing commands.

In the loop, we create two variables, `request` & `writer`, which is similar to what we did earlier in the chapter. After creating these variables, the first thing we do in (1) is check if what we read is `nil`, if it is the client disconnected, so we close everything, the channel and the socket, and don't call `recur` to effectively exit from the loop and the let the function terminate.

If we did receive something, in (2) we first create two new variables, `parts` which is the result of splitting the line we read on spaces, and `command` which is the first item from the `parts` vector. When we receive something like `GET a` or `SET abc 123`, this would respectively make `command` hold the `"GET"` & `"SET"` strings.

Next we use the `cond` function to first check if the `command` variable represent a valid command. For that we check if is contained in the `valid-commands`, which is defined as follows:

```clj
(def valid-commands
  "Valid commands"
  #{"GET"})
```

In case it is _not_ a valid command, we have two more options, either `command` is equal to `QUIT`, in which case we do the same as what we do with `nil` results from clients, we close everything and don't `recur`. Finally, if the command is unknown, we do nothing and call `recur` to listen to any future commands from the connected client.

Back to the case where the command is a valid command, we use a helper function `request-for-command` to create the hash map we will send to `command-channel`:

```clj
(defn key-request
  "Helper to structure the basic parts of a command"
  [command key channel]
  {:command command :key key :resp channel})

(defn request-for-command
  "Return a structured representation of a client command"
  [command parts resp-channel]
  (cond
    (= command "GET")
    (key-request :get (get parts 1) resp-channel)))
```

This turns a string such as `GET a` into a hash map that looks like the following:

```clj
{:command :get :key "a" :resp {:ch #object["clojure..."]}}
```

Note that any arguments passed after `a` would be ignored, which is done for the sake of simplicity. A "real" server would instead validate that the command contains the correct number of arguments and returns an error if it doesn't.

We now have all the pieces to handle more commands!

#### SET

The `SET` commands requires two arguments, a key and a value. Let's first update the `valid-commands` constant:

```clj
(def valid-commands
  "Valid commands"
  #{"GET" "SET"})
```

Next we'll update the `request-for-command` function to return a hash map with all the details that `handle-db` needs:

```clj
(defn key-value-request
  "Helper to structure the various parts of a SET command"
  [command key value channel]
  (assoc (key-request command key channel) :value value))

(defn request-for-command
  "Return a structured representation of a client command"
  [command parts resp-channel]
  (cond
    (= command "GET")
    (key-request :get (get parts 1) resp-channel)
    (= command "SET")
    (key-value-request :set (get parts 1) (get parts 2) resp-channel)))
```

Note that we added a new helper function, `key-value-request`, to help with the creation of the hash map for `SET` commands where we need an additional argument for the value. The resulting hash map looks something like:

```clj
{:command :set :key "a" :value "123" :resp {:ch #object["clojure..."]}}
```

After making these changes, we now need to update `process-command` so that the `cond` function handles the case where `command` variable is `:set`:

```clj
(defn process-command
  "Perform various operations depending on the command sent by the client"
  [db command key value]
  (cond
    (= command :get)
    (if key
      (let [value (get db key "")]
        {:updated db :response value})
      {:updated db :response "ERR wrong number of arguments for 'get' command"})
    (= command :set)
    (if (and key value)
      {:updated (assoc db key value) :response "OK"}
      {:updated db :response "ERR wrong number of arguments for 'set' command"})
    :else {:updated db :response "Unknown command"}))
```

Under the `(=command :set)` branch, we first make sure we have values for `key` & `value`, if we don't we return an error. If we do have values, we use the `assoc` function to set the value in the DB, no matter what it was before.

Remember that because we return the result of `(assoc)` under the `:updated` field, the `handle-db` function will use that value in its `recur` call and effectively keep that value as the latest version of the DB.

#### DEL

We'll follow the same approach we did for `SET` here, we'll update `valid-commands` as well as `request-for-command`:

```clj
(def valid-commands
  "Valid commands"
  #{"GET" "SET" "DEL"})

(defn request-for-command
  "Return a structured representation of a client command"
  [command parts resp-channel]
  (cond
    (= command "GET")
    (key-request :get (get parts 1) resp-channel)
    (= command "SET")
    (key-value-request :set (get parts 1) (get parts 2) resp-channel)
    (= command "DEL")
    (key-request :del (get parts 1) resp-channel)))
```

The branch for `"DEL"` is very similar to `"GET"`, with the exception that the `:command` field is `:del` and not `:get`.

Next, we need to update `process-command`:

```clj
(defn process-command
  "Perform various operations depending on the command sent by the client"
  [db command key value]
  (cond
    (= command :del)
    (if key
      (if (contains? db key)
        {:updated (dissoc db key) :response "1"}
        {:updated db :response "0"})
      {:updated db :response "ERR wrong number of arguments for 'del' command"})
    :else {:updated db :response "Unknown command"}))
```

_Note that we omitted the branches for `:get` & `:set`._

Similarly to other commands, we check for the presence of `key` and return an error if it's absent. In the case where we do have a key, before deleting it from the DB we need to check if it exists or not. This is because we want to return `"1"` to the client if _something_ was deleted and `"0"` otherwise. This is similar to what Redis does ([docs](https://redis.io/commands/del/)), with the exception that Redis returns an integer, but we return a string, in order to keep things simpler.

#### INCR

Let's follow the same steps, update `valid-commands` & `request-for-command`

```clj
(def valid-commands
  "Valid commands"
  #{"GET" "SET" "DEL" "INCR"})

(defn request-for-command
  "Return a structured representation of a client command"
  [command parts resp-channel]
  (cond
    (= command "GET")
    (key-request :get (get parts 1) resp-channel)
    (= command "SET")
    (key-value-request :set (get parts 1) (get parts 2) resp-channel)
    (= command "INCR")
    (key-request :incr (get parts 1) resp-channel)
    (= command "DEL")
    (key-request :del (get parts 1) resp-channel)))
```

And finally, let's update `process-command`. Handling the `INCR` command requires a few more steps than the previous ones. If the key exists in the DB, we need to check that it represents an integer. If the value we found is `"2"` for instance, we should change it to `"3"`, but if the value is `"a"`, then it's an error. Once again, this behavior is copied from Redis.

In order to handle the String to Integer conversion, we'll use the [`valueOf` class method](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Integer.html#valueOf(java.lang.String)) from the `Integer` java class:

```clj
(defn atoi
  "Attempt to convert a string to integer, returns nil if it can't be parsed"
  [string]
  (try
    (Integer/valueOf string)
    (catch NumberFormatException _e
      nil)))
```

We make the function return `nil` when the value cannot be converted to an integer, which will more convenient that letting the exception blow up.

```clj
(defn process-command
  "Perform various operations depending on the command sent by the client"
  [db command key value]
  (cond
    (= command :incr)
    (if key
      (if (contains? db key)
        (let [current-number (atoi (get db key))
              new-number (when current-number (str (+ current-number 1)))]
          (if current-number
            {:updated (assoc db key new-number) :response new-number}
            {:updated db :response "ERR value is not an integer or out of range"}))
        {:updated (assoc db key "1") :response "1"})
      {:updated db :response "ERR wrong number of arguments for 'incr' command"})
    :else {:updated db :response "Unknown command"}))
```

_Note that we omitted the branches for `:get`, `:set` & `del`._

If `key` is not present in the DB, then we store `"1"` and call it a day. Otherwise, we use our newly created helper `atoi` to get the integer value from the DB. If we get a non-nil value from `atoi`, then we override the value in the DB with the new value.

{{% note %}}

We do not need to worry about race conditions where two clients would send an `INCR` command at the same time thanks to the channel architecture we're using. The `handle-db` function will process commands sent to it one at a time, which effectively serializes the processing of commands.

This means that there can only be one command processed at a time, and therefore we know that the value we're dealing with when processing the `INCR` command cannot be changed by another client.
{{% /note %}}

## Atoms

Clojure has a built-in type which happens to be very convenient with what we need to do here, atoms ([docs](https://clojure.org/reference/atoms)). With atoms we can completely remove the need for channels as all our `go` blocks can share the same variable and update it safely.

Before we update our server to use atoms, let's first take a quick look at how they work,

To work with atoms, you first create one, with the `atom` function:

```clj
(def database (atom {}))
```

You can then dereference it to get its content:

```clj
@database ;; {}
```

And update them with `swap!`, to which you pass the atom and a function to update its state:

```clj
(swap! database
       (fn [current-state]
         (assoc current-state "abc" "123")))
;; {"abc" "123"}
```

And we can confirm that the state was updated:

```clj
@database ;; {"abc" "123"}
```

### Updating our servers

The `main` function is now different since we don't need to create the `command-channel` variable:

```clj
(defn main
  "Start a server and continuously wait for new clients to connect"
  [& _args]
  (println "About to start ...")
  (let [db (atom {})]
    (with-open [server-socket (ServerSocket. 3000)]
      (loop []
        (let [client-socket (.accept server-socket)]
          (handle-client client-socket db))
        (recur)))))
```

Aside from not needing channels anymore, the other important difference is that we create the `db` variable with `(atom {})`. We also don't need the `handle-db` function anymore.

Let's then look at `handle-client`, now that it doesn't need a channel:

```clj
(defn handle-client
  "Read from a connected client, and handles the various commands accepted by the server"
  [client-socket db]
  (a/go (loop [] ;; (1)
          (let [request (.readLine (io/reader client-socket))
                writer (io/writer client-socket)]
            (if (nil? request)
              (do
                (println "Nil request, closing")
                (.close client-socket))
              (let [parts (string/split request #" ")
                    command (get parts 0)]
                (cond
                  (contains? valid-commands command)
                  (let [request (request-for-command command parts) ;; (2)
                        value (process-command db request)] ;; (3)
                    (.write writer (str value "\n"))
                    (.flush writer)
                    (recur))
                  (= command "QUIT")
                  (.close client-socket)
                  :else (do
                          (println "Unknown request:" request)
                          (recur)))))))))
```

There are three difference that are highlighted:

- In (1) we don't need to have any variables in the loop anymore
- In (2) we don't need to pass a channel to `request-for-command` anymore
- In (3) we now call a new function, `process-command` instead of sending the request to a channel

The `process-command` function is where a lot of the interesting changes are, but before looking at it, let's briefly look at the `request-for-command` and the other helpers. They're essentially identical to the previous versions, with the absence of channels:

```clj
(defn key-request
  "Helper to structure the basic parts of a command"
  [command key]
  {:command command :key key})

(defn key-value-request
  "Helper to structure the various parts of a SET command"
  [command key value]
  (assoc (key-request command key) :value value))

(defn request-for-command
  "Return a structured representation of a client command"
  [command parts]
  (cond
    (= command "GET")
    (key-request :get (get parts 1))
    (= command "SET")
    (key-value-request :set (get parts 1) (get parts 2))
    (= command "INCR")
    (key-request :incr (get parts 1))
    (= command "DEL")
    (key-request :del (get parts 1))))
```

And now, let's look at `process-command`:

```clj
(defn process-command
  "Perform various operations depending on the command sent by the client"
  [db request]
  (let [command (request :command)
        key (request :key)
        value (request :value)]
    (cond
      (= command :get)
      (if key
        (get @db key "")
        "ERR wrong number of arguments for 'get' command")
      (= command :set)
      (if (and key value)
        (do
          (swap! db (fn [current-state]
                      (assoc current-state key value)))
          "OK")
        "ERR wrong number of arguments for 'set' command")
      (= command :del)
      (if key
        (let [[old-value _] (swap-vals! db (fn [current-state]
                                             (dissoc current-state key)))]
          (if (contains? old-value key) "1" "0"))
        "ERR wrong number of arguments for 'del' command")
      (= command :incr)
      (if key
        (let [[_ new-value] (increment-number db key)
              number (atoi (get new-value key))]
          (if number
            number
            "ERR value is not an integer or out of range"))
        "ERR wrong number of arguments for 'incr' command")
      :else "Unknown command")))
```

For the `:get` case, we use the `get` function and [`deref`](https://clojure.github.io/clojure/clojure.core-api.html#clojure.core/deref) to read the value.

For `:set` we use `swap!` to override whatever is in the DB.

For `:del` things are starting to get a little trickier. This is because `swap!` returns the atom's new state, but in order to decide whether we need to return `"0"` or `"1"` we need to know if `key` was in `db` before the deletion.

We could have called `(if (contains? @db key))` before calling `swap!` but we would have been subject to race conditions. This is because two `go` blocks could have called that, both see the value present, and therefore both deciding to return `"1"` whereas in reality only one of the two would have actually deleted it.

In order to prevent this issue, we use `swap-vars!`, which does the same as `swap!` with the only difference that it only returns the atom's state _before_ the update. So we use `swap-vars!`, ignoring the new state, and checking if `key` was in the DB before the update.

Because the logic in the `(=command :incr)` branch was getting complicated, it was extracted to a separate function:

```clj
(defn increment-number
  "Wrap the lower level operations required to process an increment command"
  [db key]
  (swap-vals! db (fn [current-state]
                   (if (contains? current-state key)
                     (let [number (atoi (get current-state key))]
                       (if number
                         (assoc current-state key (str (+ number 1)))
                         current-state))
                     (assoc current-state key "1")))))
```

The logic is similar to the channel version, but with `swap!`. In `increment-number`, if the value we find in the DB cannot be converted to an integer, we left it untouched. This allows us to check the value after calling `increment-number` back in `process-command`, and check if we find an integer under `key`, if we don't we know that there was a value that cannot be converted an integer, and we return tha appropriate error message

## Conclusion

It could be interesting to compare the performance of the two implementations, the one with channels and the one with atoms. I'd guess that the atoms one is more performant, because of the lack of overhead from channels, but there might other factors at play.

Additionally, I think we could improve the performance of the atoms-based version by making `db` a "regular" hash map where every value is an atom. This is because when we call `swap!` on an atom, clojure will retry the operation if the value was changed while the function was running, this means that with many clients, we might have many retries as `db` gets updated by multiple clients.
With a "per-value" atom, we'd only have to retry if two clients are operating on the same key at the same time.

[clj-java-writer]:https://clojuredocs.org/clojure.java.io/writer
[2]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/io/Writer.html#close()
[3]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/io/BufferedWriter.html#flush()
[4]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/io/Writer.html#write(java.lang.String)
[5]:https://docs.oracle.com/javase/7/docs/api/java/io/BufferedWriter.html
[java-server-socket-accept]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/ServerSocket.html#accept()
[java-server-socket]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/ServerSocket.html#%3Cinit%3E(int)
[clj-and-go-sitting-in-a-tree]:https://clojure.org/news/2013/06/28/clojure-clore-async-channels#_history
