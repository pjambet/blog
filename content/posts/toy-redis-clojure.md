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
image: /images/clojure-???.jpg
---

![???](/images/clojure-???.jpg)

_This is the second entry in the "Building Toy Redises in X" series". In [the first article](/posts/toy-redis-go/) we used Go, in this one we'll use Clojure._

_The approach used in this article is very similar to the one used in Go, with the use of Channels, so reading it first might help._

{{% note %}}
I've never written Clojure code professionally, what you're about to read is the result of a slow and painful process of trial and error. Take everything with a grain of salt. And if you are an experienced Clojure developer and spot something outrageous, please reach out!
{{% /note %}}

## What we're building

TODO: Write me

## Starting small, a TCP server

One great thing with Clojure is that you get get access to everything that Java offers. So when it comes to starting a TCP server, we can use Java's [`ServerSocket` class][java-server-socket], instantiate it with the port number it should listen to, and use its [`accept` method][java-server-socket-accept] to accept new clients. We get back a `Socket` instance, which we can conveniently use [`clojure.java.io/writer`][clj-java-writer] with, to get back an instance of `java.io.BufferedWriter`. With that writer instance, we can now write to the socket, and we need to call `flush`, to make sure the data gets written, and can be read on the other end.

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

You can run the program with `clj -M tcp.clj` and connect to it with `nc -v localhost 3000`

## Making the server do things

In this section, we'll keep the connection open,

[clj-java-writer]:https://clojuredocs.org/clojure.java.io/writer
[2]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/io/Writer.html#close()
[3]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/io/BufferedWriter.html#flush()
[4]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/io/Writer.html#write(java.lang.String)
[5]:https://docs.oracle.com/javase/7/docs/api/java/io/BufferedWriter.html
[java-server-socket-accept]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/ServerSocket.html#accept()
[java-server-socket]:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/ServerSocket.html#%3Cinit%3E(int)
