---
title: "A toy Redis Server, in Clojure"
date: 2023-02-06T21:59:55-06:00
lastmod: 2023-02-06T21:59:55-06:00
tags : [ "dev", "go", "tcp servers" ]
categories : [ "dev" ]
summary: "A toy Redis server in Clojure, responding to a small subset of commands."
layout: post
highlight: false
draft: true
image: /images/clj-river.jpg
---

![???](/images/clj-river.jpg)

_This is the second entry in the "Building Toy Redises in X" series". In [the first article](/posts/toy-redis-go/) we used Go, in this one we'll use Clojure._

_The approach used in this article is very similar to the one used in Go, with the use of Channels, so reading it first might help._

{{% note %}}
I've never written Clojure code professionally, what you're about to read is the result of a slow and painful process of trial and error. Take everything with a grain of salt. And if you are an experienced Clojure developer and spot something outrageous, please reach out!
{{% /note %}}

## What we're building

TODO: Write me

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

Let's now make the server keep the connections open, wait for the clients to send _something_ over the wire, and respond back. We will use Clojure's `core/async` library to help with concurrency, for the main reason that I couldn't think of any other solutions to do so.

`core/async` [shares a lot with Go's concurrency mechanisms][clj-and-go-sitting-in-a-tree] with couroutines, so if you read the previous entry in this series, this should all look pretty familiar.

{{% note %}}

If you're thinking "well, since we can use anything that exists in Java, we could use Java's non blocking IO library, java.nio, it's been available since Java 7". Well, you're probably right, I'm sure we _could_, but I will actually dedicate a whole article to that topic, about building a Toy Redis, in Java, using the nio package!

{{% /note %}}

Clojure lets us spin up new threads, which we could use to handle concurrent clients, but instead we'll use a higher level abstraction, Go Blocks. If you've read the previous post, or are familiar with Go, this is very similar to coroutines created with the `go` keyword.

`core.async` provides the `go` macro, it executes the body it's given asynchronously. The following example starts a go block, prints immediately the first statement from the block, and then the one from the main thread, then sleeps for 5s and finally prints done:

```clj
(a/go
  (println (str "sleeping for " 5000 "ms"))
  (Thread/sleep 5000)
  (println "done!"))
(println "Printing from main thread")
```

You can run start from the REPL with `clj -Sdeps '{:deps {org.clojure/core.async {:mvn/version "1.5.648"}}}` and then with:

```
Clojure 1.11.1
user=>(require '[clojure.core.async :as a])
nil
user=>(a/go (println (str "sleeping for " 5000 "ms")) (Thread/sleep 5000) (println "done!"))
#object[clojure.core.async.impl.channels.ManyToManyChannel 0x1b3a1e42 "clojure.core.async.impl.channels.ManyToManyChannel@1b3a1e42"]
user=> sleeping for 5000ms
done!
```

We can now start a new go block for each new client, but first, let's require it:

```clj
(ns concurrent-server
  (:require [clojure.java.io :as io]
            [clojure.core.async :as a])
  (:import (java.net ServerSocket)))
;; ...
```

And we can now us `a/go`:

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

There is an issue however, if the client disconnects before the server is stopped, an exception will be thrown, and uncaught, in the thread started by the go block. You can see this behavior by starting the server with: `clj -Sdeps '{:deps {org.clojure/core.async {:mvn/version "1.5.648"}}}' -M content/src/clj-tcp-server/concurrent_server.clj`, connecting to it with `nc localhost 3000` from another terminal, and then closing the client with `Ctrl-C`. The following exception will show up in the server logs:

```
Exception in thread "async-dispatch-1" java.net.SocketException: Broken pipe
```

I didn't include the full stacktrace, but if you were to look at it, despite it being kinda cryptic, we can see that the error occurs when we attempt to write to the socket. We can detect this by checking if the result of `readLine` is `nil`, in which case, we can close the connection on the server's end, since the client is gone.

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

## Conclusion

...

[clj-java-writer]:https://clojuredocs.org/clojure.java.io/writer
[2]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/io/Writer.html#close()
[3]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/io/BufferedWriter.html#flush()
[4]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/io/Writer.html#write(java.lang.String)
[5]:https://docs.oracle.com/javase/7/docs/api/java/io/BufferedWriter.html
[java-server-socket-accept]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/ServerSocket.html#accept()
[java-server-socket]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/ServerSocket.html#%3Cinit%3E(int)
[clj-and-go-sitting-in-a-tree]:https://clojure.org/news/2013/06/28/clojure-clore-async-channels#_history
