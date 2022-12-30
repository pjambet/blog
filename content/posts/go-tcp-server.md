---
title: "A concurrent TCP Server, in Go"
date: 2022-12-28T00:00:00Z
lastmod: 2022-12-28T00:00:00Z
tags : [ "dev", "go", "tcp servers" ]
categories : [ "dev" ]
layout: post
highlight: false
draft: true
summary: "A concurrent TCP server in Go, responding to Redis-like GET, SET, DEL & INCR commands."
---

## An arbitrary definition of "concurrent TCP server"

By the end of this post we will have a concurrent TCP server written in Go, but let's first define exactly what we're _actually_ trying to achieve.

The TCP server piece is not ambiguous, the server will be reachable over TCP, cool, but the concurrent part is a bit more subjective.

In this article we'll define "concurrent server" as a server that can accept connections from multiple clients and responds to them regardless of the order in which they connect and send requests. For instance, client C1 connects, client C2 connects, C2 can send a request and get a response no matter what C1 is doing, whether staying idle, disconnecting or sending requests as well.

{{% note %}}
I've never written Go code professionally, most of what you're seeing here is a lot of trial and error combined with finding code on the Internet.
{{% /note %}}

### Features

A TCP server by itself is not _that_ interesting to me, I want the server to keep the connections open and do
_something_. We're going to implement a very basic version of Redis where the server responds to simplified versions of the `GET`, `SET`, `DEL` & `INCR` commands. `GET` accepts a single argument, the key to be returned and `SET` accepts two argument, a key and a value. `DEL` accepts a single key argument and deletes the entry for that key, if any. Finally, `INCR` accepts a single argument and increments the existing value. If the value is not an integer, it's an error, if there are no values, it gets initialized to `1`, resulting in an identical outcome as calling `SET <key> 1`. Everything is a string under the hood, keys, values, like in Redis, the real one.

Another way to look at it is that we're building a hash map accessible over TCP.

Oh, and, finally, the last constraint, we're only using the standard library, nothing external.

## A Server that doesn't do much

We're going to build our server step by step, let's start with the "TCP server" part.

The following is a server that accepts connections, echoes back to the client what it received and keeps doing that until the client disconnects.

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

func handleConnection(client net.Conn) {
	fmt.Printf("Serving %s\n", client.RemoteAddr().String())

	for {
		netData, err := bufio.NewReader(client).ReadString('\n')
		if err != nil {
			fmt.Println("error reading:", err)
			break
		}

		temp := strings.TrimSpace(netData)
		fmt.Println("Received:", temp)
		client.Write([]byte(temp + "\n"))
	}

	fmt.Println("Closing client")
	client.Close()
}

func main() {
	arguments := os.Args
	if len(arguments) == 1 {
		fmt.Println("Please provide a port number!")
		return
	}

	port := ":" + arguments[1]
	server, err := net.Listen("tcp4", port)
	if err != nil {
		fmt.Println(err)
		return
	}
	defer server.Close()

	for {
		client, err := server.Accept()
		if err != nil {
			fmt.Println(err)
			return
		}
		go handleConnection(client)
	}
}
```

We can run this server with `go run server.go 3000` (or any other available port), and connect to it from another terminal with `nc -v localhost 3000`.

In the `main` function we start by getting the port number from the command arguments, and we then use it to start a local server listening to that port over tcp. We then start an infinite loop where we call the [blocking function `Accept`][accept-function], and for every client it returns, we run the `handleConnection` function in its own goroutine.

The built-in goroutine mechanism gives us a lot to achieve the concurrency goal stated earlier. The main goroutine is purposefully stuck in an infinite loop, in order to handle any number of clients, it waits for the next client to connect and when it does, gives it its own goroutine, where it is handled without interfering with the main goroutine where new clients may or may not connect later on.

The `handleConnection` function operates in a fairly similar manner. It also starts an infinite loop where it uses the `bufio` package to read lines of text sent by the client. The function will block until the client sends something, but because we're running this function in its goroutine, it doesn't interfere with any other clients, running in their own goroutines.

We're heavily leaning into the go runtime, which takes care of running the goroutines. As long as we're careful to run blocking code in ways that doesn't interfere with other parts of the server, we don't have anything else to do to handle any number of clients.

In order to understand the architecture of this approach, it's important to categorize all the goroutines started by the server in two groups, the first one has a single goroutine, started implicitly in the main function, we'll refer to it as the **main coroutine**, and the other group is for all the goroutines started for all the connected clients, we'll refer to them as the **client-specific coroutines**

{{% note %}}

"Any number of clients" is a stretch, there is _technically_ a limit. Handling clients is not free, it uses resources, some memory is allocated for each new client and a file descriptor is used. The specificities of the whole process vary from one OS to the next, but there is a limit of file descriptors a process can keep open, so our server cannot handle an "unlimited" number of clients.

That being said, the Go runtime is famous for being able to handle _a lot_ of goroutines. [This StackOverflow][so-goroutines] thread shows a small benchmark where 100,000 goroutines are running at the same time with a very small memory footprint.

{{% /note %}}


## Handling commands, a first attempt

**This version is subject to race conditions. Jump to [the next section](#a-race-condition-free-version) for a version protected from race conditions.**

Let's improve the `handleConnection` function to do something depending on the commands we receive from clients.

So far the function reads from the client connection, prints the content to STDOUT in the server process, and waits for the next line of text.

We now create a `map[string]string` in the `main` function, to act as our main database. We pass the map to each new goroutine, so that it can either read from it for `GET` commands, write to it for `SET` & `INCR` commands and delete from it for `DEL` commands.


```go
// [...]
func handleConnection(db map[string]string, client net.Conn) {
	defer client.Close()

	for {
		netData, err := bufio.NewReader(client).ReadString('\n')
		if err != nil {
			fmt.Println("error reading:", err)
			return
		}

		var response string
		commandString := strings.TrimSpace(netData)
		parts := strings.Split(commandString, " ")
		command := parts[0]

		switch command {
		case "STOP", "QUIT":
			return
		case "GET":
			if len(parts) > 1 {
				key := parts[1]

				response = db[key]
			} else {
				response = "ERR wrong number of arguments for 'get' command"
			}
		case "SET":
			if len(parts) > 2 {
				key := parts[1]
				value := parts[2]

				db[key] = value
				response = "OK"
			} else {
				response = "ERR wrong number of arguments for 'set' command"
			}
		case "INCR":
			if len(parts) > 1 {
				key := parts[1]
				value, ok := db[key]

				if ok {
					intValue, err := strconv.Atoi(value)
					if err != nil {
						response = "ERR value is not an integer or out of range"
					} else {
						response = strconv.Itoa(intValue + 1)
						db[key] = response
					}
				} else {
					response = "1"
					db[key] = response
				}
			} else {
				response = "ERR wrong number of arguments for 'incr' command"
			}
		case "DEL":
			if len(parts) > 1 {
				key := parts[1]
				_, ok := db[key]

				if ok {
					delete(db, key)
					response = "1"
				} else {
					response = "0"
				}
			} else {
				response = "ERR wrong number of arguments for 'del' command"
			}
		default:
			response = "ERR unknown command"
		}

		client.Write([]byte(response + "\n"))
	}
}

func main() {
	// [...]
	db := make(map[string]string)

	for {
		client, err := server.Accept()
		if err != nil {
			fmt.Println(err)
			return
		}
		go handleConnection(db, client)
	}
}
```

The various commands make use of [the various operations][map-docs] available for the map type:

- For the `GET` command, we return the value stored at `key`, with `db[key]`
- For the `SET` command, we either set the value at `key` with `value`, or replace what was there before with `db[key] = value`
- For the `INCR` command, we first check for the presence of the key in the map with `value, ok = db[key]`. If the value is present, we attempt to convert the `string` in the map to an `int` with [`strconv.Atoi`][docs-strconv-atoi]. If that works, we increment the `int` and put in the back in the map as a string with [`strconv.Itoa`][docs-strconv-itoa]. If the string cannot be converted to an int, such as `"a"` for instance, we do nothing and return an error string.
- For the `DEL` command, we either delete the value with `delete(db, key)` or do nothing if the key is not present in the map

{{% warning %}}

All the client coroutines use a reference to the same map. This is fine for a command such as `GET`, where it doesn't really matter if multiple clients run the same command. But for a command like `INCR`, things can go wrong. The following diagram illustrates the issue:

```
        ┌───────────────────┐ ┌───────────────────┐
        │                   │ │                   │
        │   C1 coroutine    │ │   C2 coroutine    │
        │                   │ │                   │
        └───────────────────┘ └───────────────────┘
                  │                     │
                  │     ┌────────┐      │
                  │     │db = {  │      │
                  │     │  key: 1│      │
                  │     │}       │      │
┌────────────────┐│     └────────┘      │
│v = db[key] // 1││─────────────────────│
└────────────────┘│                     │┌─────────────────┐
                  │─────────────────────││v = db[key] // 1 │
     ┌───────────┐│                     │└─────────────────┘
     │v += 1 // 2││─────────────────────│
     └───────────┘│                     │┌───────────┐
                  │─────────────────────││v += 1 // 2│
     ┌───────────┐│                     │└───────────┘
     │db[key] = v││─────────────────────│
     └───────────┘│                     │┌───────────┐
                  │─────────────────────││db[key] = v│
                  │     ┌────────┐      │└───────────┘
                  │     │db = {  │      │
                  │     │  key: 2│      │
                  │     │}       │      │
                  ▼     └────────┘      ▼
```

We start with a map with a single key, where the value is `1`. Two clients each send an `INCR` command for the same key, the only valid outcome for this sequence of operations if for the final value to be `3`.

But with our implementation it is entirely possible for it to be `2`. The likelihood of this exact problem to occur is rare in the sense that the read operation from the map is immediately followed by the conversion to an int, the increment and the update in the map. But since we're running the code inside goroutines, we have zero control over the sequence of operations. We're at the mercy of the go runtime as well as the underlying OS.

We can illustrate this issue with the following ruby script. First start the server with `go server.go 3000` and separately open a ruby shell, with `irb -rsocket` since we're going to need what is under `socket`. We create an array with 100 sockets connected to our go server:

```ruby
sockets = 1.upto(100).map { TCPSocket.new("localhost", 3000) }
```

Let's then spin up one thread per socket and make it call `INCR a`:

```ruby
sockets.map { |s| Thread.new { s.puts("INCR a"); puts s.gets } }
```
_We call `gets` to read the response from the server_

For good measure, let's close all these sockets:

```ruby
sockets.each(&:close)
```

Results will vary from one machine to the next, as well as from one run to the next, but on my mabook air, I often see the last value printed being 98 or 99, instead of the expected 100.

In some cases the server might crash with the error:

```
fatal error: concurrent map writes
```

This is an even more explicit proof that "we're doing it wrong". As it turns out, go knows that weird things can happen when there are concurrent writes on a single map, and it [rejects it all together with a fatal error][go-map-fatal-error].

Let's fix this.

{{% /warning %}}

## A race-condition-free version

As mentioned earlier, the previous version was subject to race conditions. We can keep leaning on goroutines to improve it and avoid these issues.

We are going to introduce a new goroutine to handle all the operations on the map in a way that makes concurrent writes impossible. In order to achieve this we will need to use channels for our goroutines to communicate with each other. We will refer to this new coroutine as the **DB coroutine**. This is done with the `handleDB` function.

The key compoment of this new version is the use of a channel field as part of the data being transmitted back to the **DB coroutine**. This is what allows us to achieve bi-directional communication. In other words the **client-specific coroutines** can send data to the **DB coroutine** and the **DB coroutine** can send data back to the specific coroutine that send said data.

This version is inspired from [this example][stateful-goroutines] from Go by Example:

```go
// [...]
type Command int

const (
	Get  Command = iota + 1 // 1
	Set                     // 2
	Incr                    // 3
	Del                     // 4
)

type commandMessage struct {
	commandName     Command
	key             string
	value           string
	responseChannel chan string
}

func handleConnection(commandChannel chan commandMessage, client net.Conn) {
		// [...]
		switch command {
		case "STOP", "QUIT":
			break outer
		case "GET":
			if len(parts) > 1 {
				key := parts[1]
				commandMessage := commandMessage{
					commandName:     Get,
					key:             key,
					responseChannel: make(chan string)}

				commandChannel <- commandMessage
				response = <-commandMessage.responseChannel
			} else {
				response = "ERR wrong number of arguments for 'get' command"
			}
		case "SET":
			if len(parts) > 2 {
				key := parts[1]
				value := parts[2]
				commandMessage := commandMessage{
					commandName:     Set,
					key:             key,
					value:           value,
					responseChannel: make(chan string)}

				commandChannel <- commandMessage
				response = <-commandMessage.responseChannel
			} else {
				response = "ERR wrong number of arguments for 'set' command"
			}
		case "INCR":
			if len(parts) > 1 {
				key := parts[1]
				commandMessage := commandMessage{
					commandName:     Incr,
					key:             key,
					responseChannel: make(chan string)}

				commandChannel <- commandMessage
				response = <-commandMessage.responseChannel
			} else {
				response = "ERR wrong number of arguments for 'incr' command"
			}
		case "DEL":
			key := parts[1]
			commandMessage := commandMessage{
				commandName:     Del,
				key:             key,
				responseChannel: make(chan string)}

			commandChannel <- commandMessage
			response = <-commandMessage.responseChannel
		default:
			response = "ERR unknown command"
		}

		client.Write([]byte(response + "\n"))
	}

	client.Close()
}

func handleDB(commandChannel chan commandMessage) {
	db := make(map[string]string)

	for {
		select {
		case command := <-commandChannel:
			switch command.commandName {
			case Get:
				command.responseChannel <- db[command.key]
			case Set:
				db[command.key] = command.value
				command.responseChannel <- "OK"
			case Incr:
				value, ok := db[command.key]
				var response string

				if ok {
					intValue, err := strconv.Atoi(value)
					if err != nil {
						response = "ERR value is not an integer or out of range"
					} else {
						response = strconv.Itoa(intValue + 1)
						db[command.key] = response
					}
				} else {
					response = "1"
					db[command.key] = response
				}
				command.responseChannel <- response
			case Del:
				_, ok := db[command.key]
				var response string

				if ok {
					delete(db, command.key)
					response = "1"
				} else {
					response = "0"
				}
				command.responseChannel <- response
			}
		}
	}
}

func main() {
	// [...]
	commandChannel := make(chan commandMessage)

	go handleDB(commandChannel)

	for {
		client, err := server.Accept()
		if err != nil {
			fmt.Println(err)
			return
		}
		go handleConnection(commandChannel, client)
	}
}
```

Let's first talk about the new types we're adding:

We create a new type, `Command`, to act as en enum-like list of all the commands supported by the server.

We also create a new struct type, `CommandMessage`. The first field will be of type `Command`, which, as just discussed, identifies the command being handled. The next two `string` fields identify the data of the command, we always have a `key`, and we a have `value` in the case of a `SET` command. It was simpler to always have the field, regardless of the command, and have its value populated only if it's a `SET` command.

The last field is the channel previously mentioned, `responseChannel`. This will allow the **DB coroutine** to respond back to the **client coroutine** that sent the `CommandMessage` in the first place. Remember that channels are bidirectional, when we create the channel in the **client goroutine**, no other goroutine can write to it. By including it in the message we send to `commandChannel`, the **DB coroutine** can write to it, and we can get retrieve that content from the **client goroutine**:

```go
// In the GET case:

// We send the message to DB coroutine, which includes a freshly created channel
commandChannel <- commandMessage
// We wait for the DB coroutine to write the channel we just created
response = <-commandMessage.responseChannel
```

With this new pattern, instead of having each **client goroutine** perform the sequence of operations specific to each command, they instead send a message to the **DB coroutine** with the data necessary to perform the operation, and wait for a response. This approach protects us from race conditions because there is a single goroutine processing the messages written to `commandChannel`. Sending message to `commandChannel` results in them being added to the channel's buffer, and in turn the **DB coroutine** will process these messages one by one through the `select` statement.

You can run the ruby script we used above and notice that the final outcome will always be 100, and the go server will never crash with `fatal error: concurrent map writes` 🎉

## Want more?

I started a repo where I'm trying to do the same thing in various languages, go check it out if for instance you're curious about how to do this with node, python or ruby: [pjambet/tcp-servers][gh-tcp-servers]

The code from this article is [on GitHub][github-code].

### Links:

- [https://opensource.com/article/18/5/building-concurrent-tcp-server-go][concurrent-tcp-server-go]
- [https://gobyexample.com/stateful-goroutines][stateful-goroutines]


[concurrent-tcp-server-go]:https://opensource.com/article/18/5/building-concurrent-tcp-server-go
[so-goroutines]:https://stackoverflow.com/questions/8509152/max-number-of-goroutines
[stateful-goroutines]:https://gobyexample.com/stateful-goroutines
[go-channels]:https://go.dev/doc/effective_go#channels
[gh-tcp-servers]:https://github.com/pjambet/tcp-servers
[go-label]:https://go.dev/ref/spec#Label_scopes
[map-docs]:https://go.dev/blog/maps#working-with-maps
[docs-strconv-atoi]:https://pkg.go.dev/strconv#Atoi
[docs-strconv-itoa]:https://pkg.go.dev/strconv#Itoa
[accept-function]:https://pkg.go.dev/net#TCPListener.Accept
[go-map-fatal-error]:https://github.com/golang/go/blob/9123221ccf3c80c741ead5b6f2e960573b1676b9/src/runtime/map.go/#L414-L416
[github-code]:https://github.com/pjambet/blog/tree/master/content/src/go-tcp-server
