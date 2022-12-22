---
title: "A concurrent Go TCP Server"
date: 2022-05-07T09:30:58-04:00
lastmod: 2022-05-07T09:30:58-04:00
tags : [ "dev", "go" ]
categories : [ "dev" ]
layout: post
highlight: false
draft: true
summary: "A concurrent TCP server in Go responding to Redis-like GET & SET commands."
---

## An arbirary definition of "concurrent TCP server"

By the end of this post we will have a concurrent TCP server written in Go, but before doing so, let's first define exactly what we're _actually_ trying to achieve.

The TCP server piece is not ambiguous, the server will be reachable over TCP, cool, but the concurrent part is a bit more subjective.

In this article we'll define "concurrent server" as a server that can accept connections from multiple clients and respond to them regardless of the order in which they connect and send requests. For instance, client C1 connects, client C2 connects, C2 can send a request and get a response no matter what C1 is doing, whether staying idle, disconnecting or sending requests.

{{% note %}}
I've never written Go code professionally, most of what you're seeing here is a lot of trial and error combined with finding code on the Internet.
{{% /note %}}

### Features

A TCP server by itself is not _that_ interesting to me, I want the server to keep the connections open and do
_something_. We're going to implement a very basic version of Redis where the server responds to simplified versions of the `GET` & `SET` commands. `GET` accepts a single argument, the key to be returned and `SET` accepts two argument, a key and a value. Everything is a string, keys, values, like in Redis, the real one.

Another way to look at it is that we're building a hash map accessible over TCP.

Oh, and, finally, the last constraint, we're only using the standard library, nothing external.

## A Server that accepts new clients

The following is a server that accepts connections, echoes back to the client what it received until the client disconnects.

It's mainly copied from [this blog post][concurrent-tcp-server-go] by Mihalis Tsoukalos, with some modifications.

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
		c.Write([]byte(temp + "\n"))
	}

	fmt.Println("Closing c")
	c.Close()
}

func main() {
	arguments := os.Args
	if len(arguments) == 1 {
		fmt.Println("Please provide a port number!")
		return
	}

	port := ":" + arguments[1]
	l, err := net.Listen("tcp4", port)
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

The built-in goroutine mechanism gives us a lot to achieve the concurrency goal stated earlier. The main goroutine is purposefully stuck in an infinite loop, in order to handle any number of clients, it waits for the next client to connect and when it does, gives it its own goroutine, where it is handled without interfering with the main goroutine where new clients may or may not connect later on.

The `handleConnection` function operates in a fairly similar manner. It also starts an infinite loop where it uses the `bufio` package to read lines of text sent by the client. The function will block until the client sends something, but because we're running this function in its goroutine, it doesn't interfere with any other clients, running in their own goroutine.

We're essentially heavily leaning into the go runtime, which takes care of running the goroutines, as long as we're careful to run blocking code in ways that doesn't interfere with other parts of the server, we don't have anything else to do to handle any number of clients.

{{% note %}}
"Any number of clients" is a stretch, there is _technically_ a limit. Handling clients is not free, it uses resources, some memory is allocated for each new client and a file descriptor is used. Different OSes handle this their own way, but there is a limit of file descriptors a process can keep open, so our server cannot handle an "unlimited" number of clients.

That being said, the Go runtime is famous for being able to handle _a lot_ of goroutines. [This StackOverflow][so-goroutines] thread shows a small benchmark where 100,000 goroutines are running at the same time with a very small memory footprint.
{{% /note %}}


## Responding to commands

For each client we keep connected, we read what it sends, print it from the server process, and wait for the next line of text. We can build on top of this to handle `GET` & `SET` commands.

The main change is ...

The example is inspired from [this example][stateful-goroutines]

```go
// [...]

type op struct {
	key   string
	value string
	resp  chan string
}

func handleConnection(channel chan op, c net.Conn) {
	for {
		// [...]
		if temp == "STOP" || temp == "QUIT" {
			break
		} else if strings.HasPrefix(temp, "GET") {
			parts := strings.Split(temp, " ")
			if len(parts) > 1 {
				key := parts[1]
				op := op{
					key:  key,
					resp: make(chan string)}
				channel <- op
				res := <-op.resp
				c.Write([]byte(res + "\n"))
			}
		} else if strings.HasPrefix(temp, "SET") {
			parts := strings.Split(temp, " ")
			if len(parts) > 2 {
				key := parts[1]
				value := parts[2]
				op := op{
					key:   key,
					value: value,
					resp:  make(chan string)}
				channel <- op
				res := <-op.resp
				c.Write([]byte(res + "\n"))
			}
		}
	}
	c.Close()
}

func main() {
	// [...]

	m := make(map[string]string)
	channel := make(chan op)

	go func() {
		for {
			select {
			case res := <-channel:
				if len(res.value) > 0 {
					m[res.key] = res.value
					res.resp <- "OK"
				} else {
					res.resp <- m[res.key]
				}
			}
		}
	}()

	for {
		// [...]
		go handleConnection(channel, c)
	}
}
```

This new version relies on [Go channels][go-channels]. In the main function, we now create a channel for a type we also added, `op`. This type is a holder for three values, a key and a value, both strings, and a field called `resp` a string channel.

In order to understand to understand the architecture of this approach, it's important to categorize all the goroutines started by the server in two groups, the first one has a single goroutine, started directly in the main function, which we'll refer to as the main coroutines, and the other group is for all the goroutines started for all the connected clients.

The channel we create in the main function will be passed to each goroutine started when new clients connect. This will be used for all these clients goroutines to send messages back to the main coroutine. Without anything else, the main goroutine would have no way to send messages back to any of the client goroutines. This is the problem that the `resp` goroutine inside `op` instances solves, but we'll get back to it later when we look at how the data is shared between all the coroutines.

We also create our main data store in the main function, a `map[string]string`. When we receive a `GET` command, we'll read from that map, and for a `SET` command, we'll add a key/value pair to it.

### Links:

- [https://opensource.com/article/18/5/building-concurrent-tcp-server-go][concurrent-tcp-server-go]
- [https://gobyexample.com/stateful-goroutines][stateful-goroutines]


[concurrent-tcp-server-go]:https://opensource.com/article/18/5/building-concurrent-tcp-server-go
[so-goroutines]:https://stackoverflow.com/questions/8509152/max-number-of-goroutines
[stateful-goroutines]:https://gobyexample.com/stateful-goroutines
[go-channels]:https://go.dev/doc/effective_go#channels
