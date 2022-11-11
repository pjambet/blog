---
title: "Go Tcp Server"
date: 2022-05-07T09:30:58-04:00
lastmod: 2022-05-07T09:30:58-04:00
tags : [ "dev", "go" ]
categories : [ "dev" ]
layout: post
highlight: false
draft: true
summary: "A concurrent TCP server in go responding to Redis-like GET & SET commands."
---

## An arbirary definition of "concurrent TCP server"

By the end of the article we'll end up with a concurrent TCP server, but before doing so, I'd like to define exactly what we're _actually_ trying to achieve.

The TCP server piece is not ambiguous, the server will be reachable over TCP, cool, but the concurrent part is a bit more subjective.

In this article we'll define "concurrent server" as a server that can accept connections from multiple clients and respond to them regardless of the order in which they connect and send requests. For instance, client C1 connects, client C2 connects, C2 can send a request and get a response no matter what C1 is doing, whether staying idle, disconnecting or sending requests.

{{% note %}}
I've never written Go code professionally, most of what you're seeing here is a lot of trial and error combined with finding code on the Internet.
{{% /note %}}

### Features

A TCP server by itself is not _that_ interesting to me, I want the server to keep the connections open and do
_something_. We're going to implement a very basic version of Redis where the server responds to `GET` & `SET` commands. A valid `GET` command accepts a single argument, the key to be returned and a valid `SET` command accepts to argument, a key and a value.

Another way to look at it is that we're building a hash map accessible over TCP. 

## A Server that accepts new clients

```go
package main

import (
	"bufio"
	"fmt"
	"net"
	"os"
	"strings"
)

func handleConnection(c net.Conn) {
	fmt.Printf("Serving %s\n", c.RemoteAddr().String())

	for {
		netData, err := bufio.NewReader(c).ReadString('\n')
		if err != nil {
			fmt.Println("error reading:", err)
			break
		}

		temp := strings.TrimSpace(netData)
		fmt.Println("Received:", temp)
	}

	c.Close()
}

func main() {
	arguments := os.Args
	if len(arguments) == 1 {
		fmt.Println("Please provide a port number!")
		return
	}

	PORT := ":" + arguments[1]
	l, err := net.Listen("tcp4", PORT)
	if err != nil {
		fmt.Println(err)
		return
	}
	defer l.Close()

	for {
		c, err := l.Accept()
		if err != nil {
			fmt.Println(err)
			return
		}
		go handleConnection(c)
	}
}
```

We can run this server with `go run server.go 3000` (or any other available port), and connect to it from another terminal with `nc -v localhost 3000`.

In the `main` function we start by getting the port number from the command arguments, and we then use it to start a local server listening to that port over tcp. We then start an infinite loop where we call the blocking function `Accept`, and for every client it returns, we run the `handleConnection` function in its own goroutine.

The built-in goroutine mechanism gives us a lot to achieve the concurrency goal. The main goroutine is purposefully stuck in an infinite loop, in order to handle any number of clients, it waits for the next client to connect and when it does, gives it its own goroutine, where it is handled without interfering with the main goroutine where new clients may or may not connect later on.

The `handleConnection` function operates in a fairly similar manner. It also starts an infinite loop where it uses the `bufio` package to read lines of text sent by the client. The function will block until the client sends something, but because we're running this function in its goroutine, it doesn't interfere with any other clients, running in their own goroutine.

We're essentially heavily leaning into the go runtime, which takes care of running the goroutines, as long as we're careful to run blocking code in ways that doesn't interfere with other parts of the server, we don't have anything else to do to handle any number of clients.

{{% note %}}
"Any number of clients" is a stretch, there is _technically_ a limit. Handling clients is not free, it uses resources, for each client some memory is allocated for each new client and a file descriptor is used. Different OSes handle this their own way, but there is a limit of file descriptors a process can keep open, so our server cannot handle an "unlimited" number of clients.
{{% /note %}}

## Responding to commands

For each client we keep connected, we read what it sends, print it from the server process, and wait for the next line of text. We can build on top of this to handle `GET` & `SET` commands.


### Links:

- https://opensource.com/article/18/5/building-concurrent-tcp-server-go
- https://gobyexample.com/stateful-goroutines
